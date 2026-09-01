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
"""

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
    anchor = sess.get("anchor", {})
    check("clock anchor triple",
          all(k in anchor for k in ("anchor_frame_ts", "anchor_unix_ms", "anchor_monotonic_ns")),
          f"frame_ts={anchor.get('anchor_frame_ts')}")
    cam = sess.get("camera", {})
    check("camera config", bool(cam.get("width")) and bool(cam.get("height")),
          f"{cam.get('width')}x{cam.get('height')} {cam.get('update_mode')}")
    dev = sess.get("device", {})
    check("device info", bool(dev.get("model")), f"{dev.get('manufacturer')} {dev.get('model')}")
    dur = sess.get("duration_s")
    if not isinstance(dur, (int, float)):
        check("duration_s", False, str(dur))

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
        check("container duration matches session duration (±2 s)",
              dur is not None and abs(fmt_dur - dur) <= 2.0,
              f"container {fmt_dur:.2f}s vs session {dur:.2f}s")
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
        check("counter invariant: observed == submitted + dropped",
              obs == sub + drp, f"{obs} = {sub} + {drp}")
        check("no silent frame drops (dropped == 0)", drp == 0, str(drp))
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
                if tbstr != "?":
                    denom = int(tbstr.split("/")[-1]) if "/" in tbstr else 1
                    quant_us = 1e6 / denom
                    check("max PTS residual within one container timebase quantum",
                          amax <= quant_us + 1, f"max {amax:.1f} us vs quantum {quant_us:.1f} us")
                    check("p95 PTS residual < 50 us (sub-frame)", p95 < 50, f"{p95:.1f} us")
                check("no negative residuals (container never before app)",
                      min(diff) >= 0, f"min {min(diff)/1000:.1f} us")

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
    poses = load_json(poses_p)
    if not poses:
        check("poses non-empty", False)
        finish(dur)
        return
    check("poses non-empty", True, f"{len(poses)} records")

    frame_ts = [p["frame_ts"] for p in poses]
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
        check("pose cadence plausible (10-60/s)", 10 <= cadence <= 60,
              f"{cadence:.1f} poses/s over {span_s:.1f}s")

    # trajectory plausibility (raw ARCore coordinates, segment 0)
    tr = [p["translation"] for p in tracking]
    if len(tr) >= 2:
        max_speed, max_ang = 0.0, 0.0
        jumps = 0
        for a, b in zip(poses, poses[1:]):
            dt = (b["frame_ts"] - a["frame_ts"]) / 1e9
            if dt <= 0:
                continue
            d = math.dist(a["translation"], b["translation"])
            max_speed = max(max_speed, d / dt)
            dq = quat_dist(a["rotation"], b["rotation"])
            max_ang = max(max_ang, math.degrees(dq) / dt)
            if d > 2.0 and dt < 1.0:
                jumps += 1
        note(f"max speed {max_speed:.2f} m/s, max angular rate {max_ang:.0f} °/s, "
             f"jumps>2m<1s: {jumps}")
        check("max speed plausible (< 5 m/s)", max_speed < 5, f"{max_speed:.2f} m/s")
        check("max angular rate plausible (< 900 °/s)", max_ang < 900, f"{max_ang:.0f} °/s")
        check("no trajectory discontinuities", jumps == 0, f"{jumps}")
        bbox = [[min(c) for c in zip(*tr)], [max(c) for c in zip(*tr)]]
        extent = [b2 - b1 for b1, b2 in zip(bbox[0], bbox[1])]
        note(f"trajectory extent (segment 0): x {extent[0]:.2f} m, "
             f"y {extent[1]:.2f} m, z {extent[2]:.2f} m")

    # clock relationships — report, don't assert (P3)
    if probe and vs and all(t > 0 for t in cam_ts):
        pts = get_pts_ns(video_p, vs)
        if pts:
            lo, hi = min(pts), max(pts)
            clo, chi = min(cam_ts), max(cam_ts)
            overlap = min(hi, chi) - max(lo, clo)
            span = min(hi - lo, chi - clo)
            print("\nclock domains (relationships only, no invariant claimed)")
            print(f"  MP4 video PTS range:      {lo/1e9:12.3f} s .. {hi/1e9:.3f} s "
                  f"(span {span/1e9:.3f}s, {len(pts)} samples)")
            print(f"  androidCameraTimestamp:   {clo/1e9:12.3f} s .. {chi/1e9:.3f} s "
                  f"(span {(chi-clo)/1e9:.3f}s, {len(cam_ts)} samples)")
            check("android-camera ts and MP4 PTS spans overlap", overlap > 0,
                  f"overlap {overlap/1e9:.3f}s of {span/1e9:.3f}s span"
                  + ("  -> same clock domain likely, residual unmeasured (P3)"
                     if overlap > 0.8 * span else ""))
            if not (0.8 * span < overlap <= span + 0.05):
                note("spans do not nearly coincide — domains likely differ; "
                     "derive the transform in P3 before any invariant")

    finish(dur)


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
    """Angle between two ARCore quaternions (x, y, z, w), radians."""
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
    print("\nRESULT: OK — session passes P1 validation")


if __name__ == "__main__":
    main()
