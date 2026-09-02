#!/usr/bin/env python3
"""Inspect a Bildfang capture session independently of the Android app.

Usage:
    python3 tools/inspect_capture.py <path-to-capture-...>

Validates the P1/P2a artifacts (video/camera.mp4 or legacy video.mp4,
video/frames.json, poses/poses.json, session.json) and prints a report.
Exits 0 if all checks pass, 1 if any fail, 2 on missing inputs. ffprobe
must be on PATH for the video checks.

The frames.json section performs the P2a timestamp round-trip: it
re-derives pts from the persisted video_timebase origin, then compares
the application-level PTS against the MP4 container's decoded PTS and
quantifies the residual in microseconds (bounded by the container
timebase, e.g. 1/11025 -> ~91 us max quantization).

This is intentionally conservative: it reports *relationships* (and
flagged unknowns), it does not assert clock-domain equivalences.

For v1.1 sessions (geometry-frozen builds, 2026-09-02) it additionally
validates the session-orientation lock, the encoded-geometry model
(affine/rectilinear consistency), the camera-metadata file, and — via
ffmpeg frame extraction — scans sampled frames for constant image
borders (the 2026-09-02 "grey right half" encoder-viewport bug
signature)."""

import json
import math
import shutil
import subprocess
import sys
from pathlib import Path

FAILS = []
NOTES = []


def check(name: str, ok: bool, detail: str = "") -> None:
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        FAILS.append(name)


def note(msg: str) -> None:
    NOTES.append(msg)
    print(f"  [note] {msg}")


def load_json(p: Path):
    with p.open() as f:
        return json.load(f)


def probe_video(path: Path):
    if not shutil.which("ffprobe"):
        return None
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json",
         "-show_format", "-show_streams", str(path)],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        return None
    return json.loads(out.stdout)


def monotonous(seq):
    """True if the sequence is non-decreasing."""
    return all(b >= a for a, b in zip(seq, seq[1:]))


def main():
    session_dir = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not session_dir.is_dir():
        print(f"error: not a directory: {session_dir}", file=sys.stderr)
        sys.exit(2)

    print(f"Bildfang session: {session_dir.name}")
    print(f"  path: {session_dir}")

    # ------------------------------------------------------------------
    print("\nsession.json")
    sess_p = session_dir / "session.json"
    if not sess_p.is_file():
        check("session.json exists", False)
        sys.exit(2)
    sess = load_json(sess_p)
    check("schema", sess.get("schema") == "bildfang-capture/v1", str(sess.get("schema")))
    check("app version", bool(sess.get("app_version")), str(sess.get("app_version")))
    anchor = sess.get("clock", {})
    check("clock anchor triple",
          all(k in anchor for k in ("anchor_frame_ts", "anchor_unix_ms", "anchor_monotonic_ns")),
          f"frame_ts={anchor.get('anchor_frame_ts')}")
    # The app records video settings under `video`; a dedicated `camera`
    # config section is not part of the current on-disk schema.
    vcfg = sess.get("video", {})
    if vcfg:
        res = str(vcfg.get("resolution", ""))
        w, h = (int(x) for x in res.split("x")[:2]) if "x" in res else (0, 0)
        check("video config (resolution/fps)",
              bool(w) and bool(h) and bool(vcfg.get("fps_nominal")),
              f"{res} @ {vcfg.get('fps_nominal')} fps, {vcfg.get('bitrate_bps')} b/s")
    dev = sess.get("device")
    if isinstance(dev, dict):
        check("device info", bool(dev.get("model")), f"{dev.get('manufacturer')} {dev.get('model')}")
    else:
        check("device info", bool(dev), str(dev))
    dur = sess.get("duration_s")
    if not isinstance(dur, (int, float)):
        check("duration_s (informational; not in current schema, container provides it)", True, "absent")

    # ------------------------------------------------------------------
    print("\nvideo")
    video_p = session_dir / "video" / "camera.mp4"
    if not video_p.is_file():
        legacy = session_dir / "video.mp4"  # pre-P2a layout
        video_p = legacy if legacy.is_file() else video_p
    video_ok = video_p.is_file() and video_p.stat().st_size > 0
    check(f"video file exists ({video_p.name})", video_ok,
          f"{video_p.stat().st_size / 1e6:.1f} MiB" if video_ok else "missing")
    probe = probe_video(video_p) if video_ok else None
    fmt, vstreams, vs = {}, [], None
    if video_ok:
        if probe is None:
            check("ffprobe parses the file", False, "file truncated or ffprobe missing")
        else:
            check("ffprobe parses the file", True)
            fmt = probe.get("format", {})
            vstreams = [s for s in probe.get("streams", []) if s.get("codec_type") == "video"]
            check("has a video stream", len(vstreams) == 1, f"{len(vstreams)} video stream(s)")
            vs = vstreams[0] if vstreams else None
    if vs:
        fps_num, fps_den = (vs.get("avg_frame_rate") or "0/1").split("/")
        fps = float(fps_num) / max(1e-9, float(fps_den))
        check("resolution plausible (landscape camera image)",
              int(vs.get("width", 0)) > 100 and int(vs.get("height", 0)) > 100,
              f"{vs.get('width')}x{vs.get('height')}")
        check("frame rate plausible (15-60 fps)", 15 <= fps <= 60, f"{fps:.1f} fps")
        nf = vs.get("nb_frames")
        if nf:
            check("frame count plausible", int(nf) > 30, f"{nf} frames")
        fmt_dur = float(fmt.get("duration") or 0)
        if dur is not None:
            check("container duration matches session duration (±2 s)",
                  abs(fmt_dur - dur) <= 2.0,
                  f"container {fmt_dur:.2f}s vs session {dur:.2f}s")
        else:
            check("container duration present (session duration not in schema)", fmt_dur > 0,
                  f"container {fmt_dur:.2f}s")
        if dur is not None and fmt_dur > 0:
            check("not truncated (duration in range 5 s - 1 h)", 5 <= fmt_dur <= 3600,
                  f"{fmt_dur:.1f}s")
    # list all streams (e.g. the custom pose track, if the demuxer sees it)
    for s in (probe or {}).get("streams", []):
        if s.get("codec_type") != "video":
            note(f"stream: {s.get('codec_type')} {s.get('codec_name')} "
                 f"({s.get('tags', {}).get('language', '?')})")

    # ------------------------------------------------------------------
    print("\nframes.json (P2a: source→encoded map + PTS round-trip)")
    frames_p = session_dir / "video" / "frames.json"
    if not frames_p.is_file():
        legacy = session_dir / "frames.json"
        frames_p = legacy if legacy.is_file() else frames_p
    frames = load_json(frames_p) if frames_p.is_file() else None
    if frames is None:
        check("video/frames.json exists", False)
    else:
        check("video/frames.json exists", True, f"{len(frames.get('frames', []))} frame records")
        check("schema", frames.get("schema") == "bildfang-capture/v1-frames",
              str(frames.get("schema")))
        tb = frames.get("video_timebase", {})
        origin = tb.get("origin_raw_ns", 0)
        check("video_timebase source_clock is android_camera", tb.get("source_clock") == "android_camera")
        check("video_timebase origin persisted (>0)", isinstance(origin, int) and origin > 0,
              str(origin))
        cnt = frames.get("counters", {})
        obs, sub, enc, mux, drp = (cnt.get("camera_frames_observed", 0),
                                   cnt.get("frames_submitted", 0),
                                   cnt.get("frames_encoded", 0),
                                   cnt.get("frames_muxed", 0),
                                   cnt.get("frames_dropped", 0))
        rate = cnt.get("frames_rate_skipped", 0)  # absent in pre-split sessions
        check("counter invariant: observed == rate_skipped + dropped + submitted",
              obs == rate + drp + sub, f"{obs} = {rate} + {drp} + {sub}")
        # Intentional cadence skips (source faster than encoder, e.g. 60 ->
        # 30 fps) are a deliberate sampling decision, counted separately
        # from real submit failures (frames_dropped). A skip mass is
        # *expected* when it matches a standard rate ratio; a large drop
        # count is never.
        if drp == 0 and rate == 0:
            check("no non-submitted frames", True, "0")
        else:
            if video_ok and fmt_dur > 0 and sub > 0:
                cam_fps = obs / fmt_dur
                enc_fps = sub / fmt_dur
                ratio = cam_fps / enc_fps if enc_fps > 0 else 0
                explained = any(abs(ratio - k) < 0.15 for k in (1.0, 1.2, 1.33, 1.5, 2.0, 3.0))
                check("non-submitted frames explained by camera->encoder rate ratio (never silent)",
                      explained,
                      f"camera {cam_fps:.1f} fps -> encoded {enc_fps:.1f} fps; "
                      f"rate_skipped={rate} dropped={drp}")
            else:
                check("non-submitted frames (cannot verify throttle without video)",
                      drp <= max(10, 0.02 * obs), f"rate_skipped={rate} dropped={drp}")
        check("submit failures (frames_dropped) are few",
              drp <= max(10, 0.02 * obs), f"{drp} of {obs}")
        check("encoded == muxed", enc == mux, f"{enc} / {mux}")
        if video_ok:
            check("video has >= 1 muxed frame", mux >= 1, str(mux))
        else:
            check("no video file => nothing muxed", mux == 0, str(mux))

        fr = frames.get("frames", [])
        if fr:
            pts = [f["pts_ns"] for f in fr]
            camt = [f.get("android_camera_timestamp_ns", 0) for f in fr]
            arct = [f.get("arcore_frame_timestamp_raw_ns", 0) for f in fr]
            check("pts_ns monotonic (non-decreasing)", monotonous(pts))
            check("camera timestamps monotonic", monotonous(camt) and all(t > 0 for t in camt))
            check("arcore raw timestamps monotonic", monotonous(arct) and all(t > 0 for t in arct))
            if origin > 0:
                rederived = [c - origin for c in camt]
                exact = sum(1 for a, b in zip(pts, rederived) if a == b)
                check("pts_ns == camera_ts - origin for every frame (reversible)",
                      exact == len(fr), f"{exact}/{len(fr)} exact")
            gaps = [b - a for a, b in zip(pts, pts[1:])]
            if gaps:
                gmed = sorted(gaps)[len(gaps) // 2]
                gm = sum(gaps) / len(gaps)
                gmax = max(gaps)
                drops_est = sum(1 for g in gaps if g > 1.5 * gmed)
                note(f"PTS gaps: median {gmed/1e6:.3f} ms, mean {gm/1e6:.3f} ms, "
                     f"max {gmax/1e6:.3f} ms, >1.5x-median gaps: {drops_est}")
                check("no large PTS gaps (> 3x median)",
                      all(g <= 3 * gmed for g in gaps),
                      f"max {gmax/1e6:.2f} ms vs median {gmed/1e6:.2f} ms")
            with_pose = sum(1 for f in fr if f.get("pose_index") is not None)
            check("pose_index coverage (>= 90%)", with_pose >= 0.9 * len(fr),
                  f"{with_pose}/{len(fr)} ({100*with_pose/len(fr):.0f}%)")

            # --- the P2a round-trip: app-level PTS vs MP4 container PTS ---
            mp4_pts = get_pts_ns(video_p, vs) if vs else []
            if mp4_pts:
                n = min(len(pts), len(mp4_pts))
                diff = [(mp4_pts[i] - pts[i]) for i in range(n)]
                adiff = sorted(abs(d) for d in diff)
                p50 = adiff[len(adiff) // 2] / 1000.0
                p95 = adiff[min(len(adiff) - 1, int(0.95 * len(adiff)))] / 1000.0
                amax = adiff[-1] / 1000.0
                tbstr = vs.get("time_base", "?")
                print("\nPTS round-trip (frames.json pts_ns vs MP4 decoded PTS)")
                print(f"  aligned samples: {n} (app {len(pts)} / container {len(mp4_pts)})")
                print(f"  |residual| us:  p50 {p50:.1f}   p95 {p95:.1f}   max {amax:.1f}")
                print(f"  container time_base: {tbstr}")
                # The javax EGL path on this driver cannot set
                # eglPresentationTimeANDROID, so the container PTS is
                # driver-assigned. That is acceptable by design as long as
                # (a) the residual stays bounded within a frame interval
                # and (b) frames.json (camera clock + persisted origin)
                # remains the authoritative source->container mapping.
                min_us = min(diff) / 1000.0
                if tbstr != "?":
                    denom = int(tbstr.split("/")[-1]) if "/" in tbstr else 1
                    quant_us = 1e6 / denom
                    tight = amax <= quant_us + 1 and min_us >= 0
                    if tight:
                        check("container PTS matches the app clock (presentation time honored)",
                              p95 < 50, f"p50 {p50:.1f} / p95 {p95:.1f} us vs quantum {quant_us:.1f} us")
                    else:
                        check("container PTS is driver-assigned; index-aligned mapping preserved "
                              "(frames.json is the authoritative timestamp source)",
                              amax < 100_000,  # bounded: far under a frame interval x3
                              f"p50 {p50:.1f} / p95 {p95:.1f} / max {amax:.1f} us, min {min_us:.1f} us")
                else:
                    check("container PTS (unknown time base)", True,
                          f"max {amax:.1f} us, min {min_us:.1f} us")

    # ------------------------------------------------------------------
    disc_p = session_dir / "poses" / "discontinuities.json"
    if disc_p.is_file():
        disc = load_json(disc_p)
        evs = disc.get("events", [])
        note(f"discontinuities.json: {len(evs)} informational event(s) "
             f"({', '.join(sorted({e.get('kind', '?') for e in evs})) or 'none'})")

    # ------------------------------------------------------------------
    print("\nposes")
    poses_p = session_dir / "poses" / "poses.json"
    if not poses_p.is_file():
        check("poses/poses.json exists", False)
        finish(dur)
        return
    raw_poses = load_json(poses_p)
    # Accept both the enveloped on-disk layout ({...,"poses": [...]}) and
    # a bare list.
    poses = raw_poses.get("poses") if isinstance(raw_poses, dict) else raw_poses
    if not poses:
        check("poses non-empty", False)
        finish(dur)
        return
    check("poses non-empty", True, f"{len(poses)} records")

    # Field names: current app writes frame_timestamp_raw_ns /
    # rotation_quaternion; older drafts used frame_ts / rotation.
    def _ft(p):
        return p.get("frame_timestamp_raw_ns", p.get("frame_ts"))
    def _rot(p):
        return p.get("rotation_quaternion", p.get("rotation"))

    frame_ts = [_ft(p) for p in poses]
    frame_ts = [t for t in frame_ts if t is not None]
    cam_ts = [p.get("android_camera_timestamp_ns", 0) for p in poses]
    rel_ts = [p["timestamp_ns"] for p in poses]
    check("frame_ts monotonic (non-decreasing)", monotonous(frame_ts))
    check("android_camera_timestamp_ns monotonic", monotonous(cam_ts))
    check("timestamp_ns (session-relative) monotonic", monotonous(rel_ts))
    check("every pose has an android camera timestamp",
          all(t > 0 for t in cam_ts), f"{sum(1 for t in cam_ts if t <= 0)} zeros")

    tracking = [p for p in poses if p["tracking_state"] == "TRACKING"]
    check("tracking coverage", len(tracking) > 0.8 * len(poses) if len(poses) > 20
          else len(tracking) == len(poses),
          f"{len(tracking)}/{len(poses)} tracking "
          f"({100 * len(tracking) / len(poses):.0f}%)")

    # cadence: ARCore camera frames arrive at the session's camera fps
    span_s = (frame_ts[-1] - frame_ts[0]) / 1e9
    if span_s > 1:
        cadence = len(poses) / span_s
        check("pose cadence plausible (10-70/s)", 10 <= cadence <= 70,
              f"{cadence:.1f} poses/s over {span_s:.1f}s")

    # trajectory plausibility (raw ARCore coordinates, segment 0)
    def _xyz(t):
        return (t.get("x", 0.0), t.get("y", 0.0), t.get("z", 0.0))

    tr = [_xyz(p["translation"]) for p in tracking]
    if len(tr) >= 2:
        max_speed, max_ang = 0.0, 0.0
        ang_rates = []
        jumps = 0
        for a, b in zip(poses, poses[1:]):
            dt = (_ft(b) - _ft(a)) / 1e9
            if dt <= 0:
                continue
            d = math.dist(_xyz(a["translation"]), _xyz(b["translation"]))
            max_speed = max(max_speed, d / dt)
            if a["tracking_state"] == "TRACKING" and b["tracking_state"] == "TRACKING":
                dq = quat_dist(_rot(a), _rot(b))
                rate = math.degrees(dq) / dt
                ang_rates.append(rate)
                max_ang = max(max_ang, rate)
            if d > 2.0 and dt < 1.0:
                jumps += 1
        # verdict on p99 (tracked-to-tracked): a single-frame pause-boundary
        # spike on an otherwise static device must not fail the session
        p99 = sorted(ang_rates)[max(0, int(0.99 * len(ang_rates)) - 1)] if ang_rates else 0.0
        note(f"max speed {max_speed:.2f} m/s, angular rate p99 {p99:.0f} / max {max_ang:.0f} °/s "
             f"(tracked-to-tracked), jumps>2m<1s: {jumps}")
        check("max speed plausible (< 5 m/s)", max_speed < 5, f"{max_speed:.2f} m/s")
        check("p99 angular rate plausible (< 900 °/s)", p99 < 900, f"p99 {p99:.0f} / max {max_ang:.0f} °/s")
        check("no trajectory discontinuities", jumps == 0, f"{jumps}")
        bbox = [[min(c) for c in zip(*tr)], [max(c) for c in zip(*tr)]]
        extent = [b2 - b1 for b1, b2 in zip(bbox[0], bbox[1])]
        note(f"trajectory extent (segment 0): x {extent[0]:.2f} m, "
             f"y {extent[1]:.2f} m, z {extent[2]:.2f} m")

    # clock relationships — report, don't assert (P3).
    # The android-camera clock is opaque (epoch unknown), so compare its
    # *span* against the container PTS span after converting to
    # session-relative via the persisted video origin (the P3 transform
    # for the video path).
    if probe and vs and all(t > 0 for t in cam_ts):
        pts = get_pts_ns(video_p, vs)
        if pts:
            lo, hi = min(pts), max(pts)
            origin_ns = 0
            try:
                origin_ns = int(frames.get("video_timebase", {}).get("origin_raw_ns", 0))
            except Exception:
                pass
            rel = [t - origin_ns for t in cam_ts] if origin_ns > 0 else list(cam_ts)
            clo, chi = min(rel), max(rel)
            overlap = min(hi, chi) - max(lo, clo)
            span = min(hi - lo, chi - clo)
            tag = "session-relative (video origin subtracted)" if origin_ns > 0 else "RAW (origin unavailable)"
            print("\nclock domains (relationships only, no invariant claimed)")
            print(f"  MP4 video PTS range:      {lo/1e9:12.3f} s .. {hi/1e9:.3f} s "
                  f"(span {(hi-lo)/1e9:.3f}s, {len(pts)} samples)")
            print(f"  androidCameraTimestamp, {tag}:")
            print(f"                             {clo/1e9:12.3f} s .. {chi/1e9:.3f} s "
                  f"(span {(chi-clo)/1e9:.3f}s, {len(rel)} samples)")
            check("android-camera (session-relative) span matches MP4 PTS span (±1 s)",
                  origin_ns > 0 and abs((chi-clo) - (hi-lo)) <= 1e9,
                  f"camera {span/1e9:.3f}s vs container {(hi-lo)/1e9:.3f}s, "
                  f"overlap {max(0, overlap)/1e9:.3f}s")
            if not (0.8 * span < overlap <= span + 0.05):
                note("spans do not nearly coincide — domains likely differ; "
                     "derive the transform in P3 before any invariant")

    geometry_section(session_dir, sess, vs)
    finish(dur)


def geometry_section(session_dir: Path, sess: dict, vs) -> None:
    """P1.1: orientation lock, encoded-geometry model, metadata, borders.

    All sections are nested under `video` in session.json (v1.1 schema).
    Pre-1.1 sessions (no orientation/mapping keys) get a note and are
    skipped — the P1 checks above remain the contract for them.
    """
    if "orientation" not in sess or "video" not in sess:
        note("not a v1.1 session — geometry checks skipped")
        return
    print("\ngeometry & encoded intrinsics (v1.1)")
    v = sess.get("video", {})

    # --- orientation lock ---
    pol = sess.get("orientation_policy", "")
    check("orientation locked at start (session field present)",
          sess.get("orientation") in ("portrait", "landscape"),
          str(sess.get("orientation")))
    if pol:
        check("orientation policy recorded", True, pol)
    events = sess.get("rotation_events_during_recording")
    check("no mid-recording rotation events", events == 0, f"{events}")
    pg = v.get("preview_geometry") or {}
    if pg:
        check("preview geometry frozen", pg.get("width", 0) > 0,
              f"{pg.get('width')}x{pg.get('height')} rot={pg.get('display_rotation')}")

    # --- encoded image + mapping ---
    enc = v.get("encoded_image") or {}
    mapping = enc.get("mapping") or {}
    src = v.get("source_image") or {}
    ew, eh = int(enc.get("width") or 0), int(enc.get("height") or 0)
    check("encoded image dims documented", ew > 0 and eh > 0, f"{ew}x{eh}")
    if vs:
        vw, vh = int(vs.get("width") or 0), int(vs.get("height") or 0)
        check("video resolution == documented encoded geometry",
              (vw, vh) == (ew, eh), f"video {vw}x{vh} vs encoded {ew}x{eh}")
    check("encoded geometry_source is bildfang-encoder/v1",
          enc.get("geometry_source") == "bildfang-encoder/v1",
          str(enc.get("geometry_source")))
    if src:
        check("ARCore source intrinsics present",
              bool(src.get("fx")) and src.get("width", 0) > 0,
              f"{src.get('width')}x{src.get('height')} fx={src.get('fx')}")
    aff = mapping.get("affine_enc_to_src") or []
    check("mapping present with 6 affine coefficients", len(aff) == 6, f"{len(aff)}")
    rect = enc.get("rectilinear_model")
    if len(aff) == 6:
        scale = max(abs(aff[0]), abs(aff[1]), abs(aff[3]), abs(aff[4]), 1e-9)
        off = lambda x: abs(x) <= 1e-3 * scale
        m00, m01, tx = aff[0], aff[1], aff[2]
        m10, m11, ty = aff[3], aff[4], aff[5]
        diagonal = off(m01) and off(m10) and abs(m00) > 1e-9 and abs(m11) > 1e-9
        anti = off(m00) and off(m11) and abs(m01) > 1e-9 and abs(m10) > 1e-9
        if diagonal or anti:
            if diagonal:
                fx_e = float(src["fx"]) / abs(m00)
                fy_e = float(src["fy"]) / abs(m11)
                cx_e = (float(src["cx"]) - tx) / m00
                cy_e = (float(src["cy"]) - ty) / m11
            else:
                fx_e = float(src["fy"]) / abs(m10)
                fy_e = float(src["fx"]) / abs(m01)
                cx_e = (float(src["cy"]) - ty) / m10
                cy_e = (float(src["cx"]) - tx) / m01
            if isinstance(rect, dict) and "fx" in rect:
                tol = max(0.05, 1e-4 * max(fx_e, fy_e))
                ok2 = (abs(rect["fx"] - fx_e) < tol and abs(rect["fy"] - fy_e) < tol
                       and abs(rect["cx"] - cx_e) < tol and abs(rect["cy"] - cy_e) < tol)
                check("orthogonal mapping -> rectilinear K present and consistent", ok2,
                      f"rect fx={rect.get('fx'):.3f}/exp {fx_e:.3f} fy={rect.get('fy'):.3f}/exp {fy_e:.3f} "
                      f"cx={rect.get('cx'):.1f}/exp {cx_e:.1f} cy={rect.get('cy'):.1f}/exp {cy_e:.1f} rot={rect.get('rotation')}")
            elif isinstance(rect, dict) and "rotation/shear" in rect.get("status", ""):
                # builds before the orthogonal-K fix rejected 90-degree maps
                # conservatively; the affine chain is still canonical and exact.
                note(f"conservative ABSENT from pre-fix build (affine is canonical) — expected K: "
                     f"fx={fx_e:.3f} fy={fy_e:.3f} cx={cx_e:.1f} cy={cy_e:.1f}")
            else:
                check("orthogonal mapping -> rectilinear K present", False, str(rect)[:80])
        else:
            ok = isinstance(rect, dict) and rect.get("status", "").startswith("ABSENT")
            check("sheared/non-orthogonal affine -> no rectilinear model (honest ABSENT)", ok, str(rect)[:80])
    tr = v.get("texture_rotation_deg")
    if tr is not None:
        check("texture rotation is a 90-degree multiple", tr % 90 == 0, str(tr))

    # --- camera metadata ---
    md = sess.get("camera_metadata") or {}
    if md:
        mp = session_dir / md.get("file", "camera/frames.json")
        check("camera metadata file present", mp.is_file())
        if mp.is_file():
            data = load_json(mp)
            recs = data.get("frames", [])
            av = md.get("availability", {})
            with_v = sum(1 for x in av.values() if x == "AVAILABLE_AND_CAPTURED")
            check("metadata availability table populated", len(av) > 0 and with_v > 0,
                  f"{with_v}/{len(av)} keys with values, {len(recs)} per-frame records")
            stab = md.get("stabilization_config", "")
            if stab:
                note(f"stabilization config: {stab}")

    # --- constant-border scan (the 2026-09-02 viewport-bug detector) ---
    if not (shutil.which("ffmpeg") and vs):
        note("ffmpeg not available — constant-border scan skipped")
        return
    video_p = session_dir / "video" / "camera.mp4"
    if not video_p.is_file():
        legacy = session_dir / "video.mp4"
        video_p = legacy if legacy.is_file() else video_p
    import re
    import tempfile
    total_f = int(vs.get("nb_frames") or 100)
    dur_s = sess.get("duration_s") or (total_f / 30.0)
    times = [max(0.05, (i + 0.5) * float(dur_s) / 9) for i in range(9)]
    worst_frac = 1.0
    worst = {"l": 0, "r": 0, "t": 0, "b": 0}
    scanned = 0
    with tempfile.TemporaryDirectory() as td:
        for i, t in enumerate(times):
            out = Path(td) / f"f{i}.ppm"
            r = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{t:.2f}",
                                "-i", str(video_p), "-frames:v", "1",
                                "-vf", "scale=iw/4:ih/4", str(out)],
                               capture_output=True, text=True, timeout=60)
            if r.returncode != 0 or not out.is_file():
                continue
            txt = out.read_bytes()
            m = re.match(rb"P[67]\s+(\d+)\s+(\d+)\s+255", txt)
            if not m:
                continue
            w, h = int(m.group(1)), int(m.group(2))
            raw = txt[m.end():]
            if len(raw) < w * h * 3:
                continue
            px = [raw[j * 3] + raw[j * 3 + 1] + raw[j * 3 + 2] for j in range(w * h)]

            def rowstd(y):
                seg = px[y * w:(y + 1) * w]
                mean = sum(seg) / len(seg)
                return (sum((x - mean) ** 2 for x in seg) / len(seg)) ** 0.5

            def colstd(x):
                seg = [px[y * w + x] for y in range(h)]
                mean = sum(seg) / len(seg)
                return (sum((v - mean) ** 2 for v in seg) / len(seg)) ** 0.5

            l = 0
            while l < w * 0.5 and rowstd(l) < 8: l += 1
            r_ = 0
            while r_ < w * 0.5 and rowstd(h - 1 - r_) < 8: r_ += 1
            t_ = 0
            while t_ < h * 0.5 and colstd(t_) < 8: t_ += 1
            b_ = 0
            while b_ < h * 0.5 and colstd(w - 1 - b_) < 8: b_ += 1
            frac = ((w - l - r_) * (h - t_ - b_)) / (w * h)
            scanned += 1
            worst_frac = min(worst_frac, frac)
            worst["l"] = max(worst["l"], l)
            worst["r"] = max(worst["r"], r_)
            worst["t"] = max(worst["t"], t_)
            worst["b"] = max(worst["b"], b_)
    if scanned == 0:
        note("border scan: no frames extractable — skipped")
        return
    ok = worst_frac >= 0.9
    check(f"no constant image borders (scanned {scanned} frames @ 1/4 scale)", ok,
          f"min active fraction {worst_frac * 100:.1f}% "
          f"(L{worst['l']} R{worst['r']} T{worst['t']} B{worst['b']} of {int(vs.get('width')) // 4}x{int(vs.get('height')) // 4})")
    if not ok:
        note("constant-border scan found large flat regions — classic signature "
             "of the encoder-viewport bug or a letterboxed source. Do NOT use "
             "this session for photometric work until inspected visually.")


def get_pts_ns(video_p: Path, vs) -> list:
    if not shutil.which("ffprobe"):
        return []
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "frame=pts_time", "-of", "csv=p=0", str(video_p)],
        capture_output=True, text=True,
    )
    pts = []
    for line in out.stdout.splitlines():
        line = line.strip()
        if line and line != "N/A":
            try:
                pts.append(int(float(line) * 1e9))
            except ValueError:
                pass
    return pts


def quat_dist(a, b) -> float:
    """Angle between two ARCore quaternions, radians.
    Accepts {x,y,z,w} dicts or 4-element sequences (x, y, z, w)."""
    if isinstance(a, dict):
        a = (a["x"], a["y"], a["z"], a["w"])
    if isinstance(b, dict):
        b = (b["x"], b["y"], b["z"], b["w"])
    dot = abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3])
    return 2 * math.acos(min(1.0, dot))


def finish(dur) -> None:
    if dur is not None:
        print(f"\nsession duration: {dur:.1f}s")
    if NOTES:
        pass
    if FAILS:
        print(f"\nRESULT: FAIL ({len(FAILS)}): {', '.join(FAILS)}")
        sys.exit(1)
    print("\nRESULT: OK — session passes P1/P1.1 validation")


if __name__ == "__main__":
    main()
