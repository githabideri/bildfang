# Bildfang Development Roadmap

Living document. Updated as work progresses; decisions, measurements, and
results are recorded under **Status** and in the per-phase notes. The
ordering is binding: no learned/assisted feature starts until the capture
package and its timestamps pass end-to-end validation.

**Guiding principle:** Bildfang has two responsibilities:

1. **Capture trustworthy raw spatial observations** (authoritative layer).
2. **Help the user acquire data that reconstructs well** (advisory layer).

A future capture-quality model may be wrong. It must never corrupt,
filter, discard, or silently alter the preserved raw capture.

```text
                  BILDFANG
                     |
        +------------+-------------+
        |                          |
        v                          v
 Capture / Preservation      Capture Assistance
        |                          |
 video / poses / IMU       tracking / blur / VPR
 intrinsics / clocks        coverage / loop closure
        |                          |
        +------------+-------------+
                     |
                     v
                 Bildwerk
```

## Status

| Phase | Title | Status | Notes |
|-------|-------|--------|-------|
| P0 | Freeze preview baseline | **DONE** | `8674383` verified on Pixel 7 / GrapheneOS 17 / ARCore 1.54 (2026-09-01) |
| P1 | First end-to-end capture | **IN PROGRESS — geometry fix shipped 2026-09-02, Step-9 human capture pending** | MediaCodec self-encode path verified on both fleet Pixels (2026-09-01). **2026-09-02: the washroom capture's video geometry was found to be wrong** (encoder GL viewport not updated on the encoder-surface switch + preview UVs rendered into the wrong encoder canvas → camera content on the left, flat fill on the right). It is **INVALID for photometric benchmark, VALID for trajectory analysis with caveats**. P1.1 below fixes the geometry, orientation, intrinsics, metadata, and storage; the 10–20 s human verification capture (Step 9) is the remaining P1 gate. |
| P1.1 | Recorder geometry / orientation / intrinsics / metadata / storage (2026-09-02) | **BUILT + headless-verified 2026-09-02; human Step-9 capture pending** | See the P1.1 section below. Headless 4 s acceptance capture on the 9 Pro: 906×1406 portrait encoder canvas (was 1920×1080 landscape), orientation frozen at START, affine encoded→source mapping persisted, honest rectilinear refusal (−90° texture rotation → no rectilinear K exists), 7/7 camera-metadata keys with values, EIS explicitly OFF, counter invariants exact, border scan 100% active on all sampled frames (bug signature gone). |
| P2a | MediaCodec timestamp/mux round-trip | **ON-DEVICE VERIFIED on both fleet devices, 2026-09-01** | Full encoder pipeline works on both Exynos devices after fixing: (1) three MediaCodec API bugs (missing `CONFIGURE_FLAG_ENCODE`, `createEncoderByType()` given a codec name, no `COLOR_FormatSurface` — the "GrapheneOS encoder lockdown" diagnosis is **REJECTED**: stock camera records on both devices); (2) on this device/driver, our android-`EGL14` encoder-surface path returned no usable config (even the loosest request; a NULL-list query was rejected outright, `err=0x3000`) — **observed platform/path incompatibility on the tested Pixel 9 Pro / GrapheneOS 17 build**, full driver root cause not yet established — worked around by capturing the javax `EGL10` instance GLSurfaceView itself uses (via the window-surface factory) and driving the encoder surface through it; the driver also rejects custom *context* factories and sentinel indices in `releaseOutputBuffer` (the format-change event must simply be skipped); (3) submit-rate overflow: the encoder input queue holds a few buffers, so 60 fps submissions die after ~3 s — submissions are throttled to the encoder rate (skips counted as drops, never silent); (4) `String.format` without `Locale.US` wrote decimal commas into `poses.json` (de-AT device locale) — invalid JSON. **Tech debt (not pre-P1):** hand-rolled JSON serialization should eventually move to a real serializer (`kotlinx.serialization` or equivalent) so device locale can never touch machine-readable output again. **Container PTS is driver-assigned** on this path (no javax binding for `eglPresentationTimeANDROID`): p50 14.7 ms / p95 30.1 ms / max 54.3 ms residual vs the app's camera-clock PTS, bounded and index-aligned — `frames.json` + persisted origin remain the authoritative timestamp source (by design). | camera ts → normalized PTS → encoder PTS → MP4 PTS → ffprobe; quantify residual. Unblocks P1. |
| P2b | ARCore native custom-track playback round-trip | **dead end on this fleet (2026-09-01)** | both Pixels: ARCore 1.54 native recorder fatal; see verdict below |
| P3 | Clock-domain model | **DONE** | `capture-format.md` rewritten: named domains (arcore_frame / android_camera / android_monotonic / wall_clock / sensor / container_pts), guaranteed/measured/unknown, no epoch claims; `frame_timestamp_raw_ns` stored per pose |
| P4 | De-contradict capture-format.md clocks | **DONE** | same rewrite; "one shared clock" invariant removed; IMU + invariants sections aligned with the domain model |
| P5 | Raw poses + trajectory_discontinuity | **IN PROGRESS** | multi-signal discontinuity detection → `poses/discontinuities.json` (informational, not a verdict); `translation_raw` + explicit SE(3) segment transform deferred until after v1 live-verify (schema freeze, step 9) |
| P6 | Intrinsics: source-tagged, validated scaling | not started | |
| P7 | Full preservation package + manifest | not started | now on top of the MediaCodec recorder: `video/camera.mp4` + authoritative `frames.json`, counters in manifest, manifest written last |
| P8 | Raw IMU logging | not started | |
| P9 | Cheap capture-health signals | not started | |
| P10 | Local visual continuity | not started | |
| P11 | Coverage model | not started | |
| P12 | Learned VPR research/benchmark | not started | |
| P13 | Geometrically verified loop closure | not started | |
| P14 | VPR↔pose drift cross-check | not started | |
| P15 | Optional depth capture | not started | |
| P16 | Semantic detection (only if it solves a concrete problem) | not started | |

## P0 — Freeze and validate the current preview baseline (DONE)

Baseline: `8674383`. Working on Pixel 7 / GrapheneOS 17 / ARCore 1.54:

**Device-identity correction (2026-09-01, via device properties):** the
fleet's `pixel-7` (serial 27251FDH2006FG) is actually a **Pixel 6**
(`panther`) with **Exynos 2100 / GS201** (Chinese variant) and a
Mali-G710 GPU — the name is a mislabel, and the earlier "Tensor G2 /
Mali r54" notes were wrong. The `pixel-9-pro` (`caiman`, serial
48141FDAP006YB) is the **EU variant with Exynos 2400 / zumapro**, not
Tensor G4 (which `ro.soc.model` misleadingly reports). **Consequence:
both fleet phones are Exynos devices sharing the same media-driver
family — this is why the same Exynos-specific workarounds below work
unchanged on both, and why "a second, different SoC" was never actually
tested. All "Pixel 7" mentions in this document mean that device.**
ARCore session, live tracking, OES camera preview, correct display
orientation, `transformCoordinates2d` UV mapping, ~30 fps camera,
~60 fps GL rendering, pose collection.

Root-cause notes to keep (do not regress, do not refactor this path until
P1 validation is complete):

1. Vertex buffer must be **direct** + **native byte order**.
2. The device may return an **ES 3.x context despite requesting ES2**
   (Pixel 7 / Mali returns ES 3.2 for client version 2).
3. OES external-texture sampling in ES 3.00 requires
   `#version 300 es` + `#extension GL_OES_EGL_image_external_essl3 :
   require` + `samplerExternalOES` (the GLSL-100 extension compiles but
   samples black on Mali; `samplerExternalOES` is not core until ES 4.00).
4. `setDisplayGeometry` is `(rotation, width, height)` — rotation is
   `Surface.ROTATION_x` of the display, not a pixel dimension.
5. The camera texture binding must survive the session lifecycle:
   re-register `setCameraTextureName` on every resume and set
   `TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES` explicitly
   (`Frame.getCameraTextureName()` returns 0 otherwise in 1.54).

## P1 — First real end-to-end capture (IN PROGRESS — MediaCodec path)

```text
START -> walk through room 30-90 s -> STOP -> pull files
```

The session must be inspectable **independently of the Android app**.
**Decision 2026-09-01 (ChatGPT review of `4186c4f`): the ARCore native
recorder is no longer a dependency.** MediaCodec self-encoding is the
primary, portable, preservation-grade path (our recorder, our timestamps,
our muxing); ARCore native dataset recording becomes an optional
capability (P2b) where it works.

**P1 is DONE only after an actual 30–90 s walk produces a pulled-off-device
capture that passes all of:**

- MP4 playable
- expected resolution
- expected approximate frame rate
- sensible duration
- strictly monotonic video PTS
- no unexplained duplicate timestamps
- no black/corrupt/repeated frames
- poses exported (`poses/poses.json`)
- `frames.json` exported (authoritative source-frame → encoded-frame map)
- every encoded frame traceable to a source camera timestamp
- every encoded frame associable with a pose, or explicitly marked otherwise
- trajectory physically plausible
- frame/drop counters consistent (`camera_frames_observed` / `submitted` /
  `encoded` / `muxed` / `dropped`)
- ffprobe output sane
- start and stop do not truncate the container

**Plus:**

- one **2–3 minute stress recording**: no memory growth, no encoder
  starvation, no GL degradation, no severe thermal collapse, valid
  finalized MP4;
- one **interrupted capture**: must be clearly marked incomplete, never
  silently appear valid (manifest last, P7).

Tooling: `tools/inspect_capture.py` — canonical session validator
(ffprobe + JSON checks, human-readable report); extend it for
`frames.json` + counters as part of P1. `tools/plot_trajectory.py`
already exists.

The capture layout is now P7's from the start (MediaCodec lets us choose
it): `video/camera.mp4` + `video/frames.json`, `poses/poses.json` (+
`discontinuities.json`), `session.json`, and `manifest.json` last.

### Status — 2026-09-01 (night session, Pixel 7, dark room)

**`Session.startRecording()` fails with `com.google.ar.core.exceptions.FatalException`**
(maps from `AR_STATUS_ERROR_FATAL`, decoded from the SDK class). The session
survives afterwards (preview keeps running), the camera HAL (Lyric) is
healthy and never even asked to reconfigure during the failure — the error
is raised inside ARCore's own recorder setup, ~5 ms after its stream probes:

```text
recorder_util.cc:68] Disable recording hinge angle as it's not supported!
session.cc:4349] Color stream recording enabled, but not recording because
                 color stream resolution matches motion tracking resolution.
session.cc:4387] Tried to record stereo images, but couldn't find a secondary stream to record
session.cc:4404] Tried to record depth images, but could not find a stream to record.
→ FatalException (no message, no further diagnostics in any log)
```

ARCore native source is closed; the public SDK repo has no sources, and the
GitHub issue tracker has no matching report.

**Bisection performed (all on-device, identical failure in every case):**

| Variable | Tested | Verdict |
|----------|--------|---------|
| Custom `Track` in RecordingConfig | removed it | still fatal — not the cause |
| Output URI: external `file://` (`/sdcard/Android/data/...`) | internal `filesDir` | still fatal — not the cause |
| `Config` (update mode / texture mode / geospatial) | bare default config | still fatal — not the cause |
| `CameraConfig` (highest-res CPU image vs default) | default | still fatal — not the cause |
| OES camera texture binding | always present in these tests | untested in isolation (needed for preview) |
| **Tracking state / scene** | dark room, lux ~0.15, VIO never left initialization (`VisualInertialState is kNotTracking`), `frame.track().pose` never non-null | **untested — only remaining variable** |

**Working hypothesis:** the ARCore 1.54 recorder requires a tracking state
(or scene) the dark room cannot provide, or the recorder is broken on this
device/OS combination. Distinguishable tomorrow in a lit room:

1. **Lit-room test (tomorrow, primary):** user walks the room; if
   `startRecording` succeeds with tracking active → the dark/no-tracking
   state was the blocker. Document as a requirement: *recording may require
   active tracking on ARCore 1.54*.
2. **If it fails in a lit, tracking scene:** device/OS-level ARCore 1.54
   recorder bug on GrapheneOS. Then: (a) test Pixel 9 Pro (ARCore not yet
   installed there; needs user to install via Play, then sideload test),
   (b) fallback video path: app-side encoding of `frame.acquireCameraImage()`
   (YUV) via MediaCodec/MediaMuxer — heavier CPU, but fully under our
   control and still synchronized to ARCore's frame clock; poses.json +
   `androidCameraTimestamp` remain the sync spine.

Also verified tonight (useful regardless):

- **Button geometry for adb automation** (Pixel 7, 1080×2400, 3-button nav):
  START center ≈ (275, 2178), STOP center ≈ (804, 2178). `uiautomator dump`
  does **not** work while the GL loop runs (UI never reaches idle state).
- `svc power stayon true` keeps the screen on (phone is charging) — usable
  for unattended capture windows; revert with `stayon false` when done.
- The app's status text is the fastest diagnostic surface when logcat is
  quiet: screenshot → brighten crop → read.
- `Frame.getCameraTextureName()` returns the registered texture (1) only
  when `TextureUpdateMode.BIND_TO_TEXTURE_EXTERNAL_OES` is set explicitly
  and the texture is re-registered on resume; 0 otherwise.

**Also completed overnight (independent of P1):**

- **P3 + P4 done** — `capture-format.md` rewritten around named clock
  domains with guaranteed/measured/unknown classification; no
  Unix-epoch claim for `Frame.getTimestamp()`; the `android_camera`
  ↔ `container_pts` relationship is explicitly *to be measured in P1/P2*,
  not assumed.
- **P5 (detectable half) done** — discontinuity events are now recorded
  with all firing signals (`tracking_recovered` / `translation_jump` /
  `rotation_jump`) in `poses/discontinuities.json` as *informational* data,
  and every pose stores the raw `frame_timestamp_raw_ns` alongside the
  session-relative value. All unit-tested; build green.
- **App left ready on the phone**: clean build installed, session resumed,
  screen kept on via `svc power stayon true` (charging).
  **Morning test — just tap START and watch the room.**

### Status — 2026-09-01 (morning, 09:39 UTC, lit room, ACTIVE TRACKING)

**Decisive result: the tracking hypothesis is dead.**

The phone was pointed at a well-lit scene (wooden floor) with the tracker
**live** (status line: `TRACKING · 31.6 fps`, poses flowing). Tapping START
reproduced the identical failure, now with the full stack captured:

```text
09:39:55.653  input tap 275 2178                      (START)
09:39:55.728  W native: recorder_util.cc:68] Disable recording hinge
             angle as it's not supported!             (harmless info line)
09:39:55.730  E bildfang: startRecording failed
09:39:55.730  com.google.ar.core.exceptions.FatalException
09:39:55.730    at com.google.ar.core.Session.nativeStartRecording(Native Method)
09:39:55.730    at com.google.ar.core.Session.startRecording(Session.java:2)
09:39:55.730    at app.bildfang.MainActivity.startRecording(MainActivity.kt:602)
```

No files were created; the session survived (preview kept running, no
`session.cc:1139` fatal, camera HAL untouched). The native recorder prints
**no reason** before dying — the failure is inside `nativeStartRecording`'s
own initialization, ~2 ms after entry, before any encoder or camera work.

**Verdict:** ARCore 1.54's native MP4 recorder is broken on
Pixel 7 / GrapheneOS 17 — independent of tracking state, scene lighting,
`CameraConfig`, custom tracks, and URI source. (Possible GrapheneOS
seccomp/dmabuf interaction on the recorder's native side, but the app
process has no access to ARCore's internals to confirm; the camera
preview path works fine under the same seccomp profile.)

**Remaining options (decision pending, see open items):**

1. **Test the Pixel 9 Pro** — different SoC/HAL (Tensor G5); if its
   ARCore is certified *and* its recorder works, the room capture can
   proceed on that device and the Pixel 7 bug is a reported-then-deferred
   upstream issue. Open question: is the 9 Pro even on ARCore's certified
   device list at all? (absent from all public lists checked — the
   on-device `checkAvailability()` line is the only answer.)
2. **MediaCodec self-encode fallback** (the design doc's original plan, and
   the P7 target anyway): encode `Image`/`HardwareBuffer` frames ourselves
   with `Frame.getAndroidCameraTimestamp()` as presentation PTS, mux via
   MediaMuxer, and write IMU/poses/intrinsics files ourselves. More code,
   but full control of every clock domain and it removes the dependency on
   a broken native component.
3. **File an upstream bug** (Google ARCore / GrapheneOS) with the bisection
   log above — low priority, but cheap to do once the format is frozen.


## P1.1 — Recorder geometry, orientation, intrinsics, metadata, storage (2026-09-02)

**The bug found in the first real spatial capture (washroom, 2026-09-01).**
The encoded video was not a valid image of the ARCore source frame: the GL
viewport was still set to the preview dimensions when the encoder surface was
made current (never updated on the encoder-surface switch), and the preview
UV quad — computed for the portrait preview display geometry — was rendered
into a landscape encoder canvas. Result: camera content on the left, flat
grey/black fill on the right. Every photometric conclusion drawn from that
capture (sharpness, motion blur as the reconstruction blocker, exposure) is
**void**; the trajectory analysis remains valid with caveats (the poses come
from ARCore, not from the video).

**The fix (this phase), implemented 2026-09-02:**

1. **Viewport discipline** — the encoder canvas gets its own
   `glViewport(0, 0, encW, encH)` before every encoder render; the preview
   surface and viewport are restored in a `finally` block on *every* exit
   path (normal, error, early return), with a read-back invariant check
   logged on mismatch.
2. **Orientation frozen per session** — the capture orientation is decided
   at START (portrait → 1080×1920-class canvas, landscape → its transpose),
   persisted in `session.json`; physical rotation during recording is
   counted (`rotation_events_during_recording`) but never applied. The
   activity no longer declares a fixed screen orientation; it owns its
   geometry. (ARCore display geometry is part of the frozen state —
   `setDisplayGeometry` is not re-called while frozen.)
3. **Preview geometry ≠ encoder geometry** — the encoder canvas is the
   display canvas in the frozen orientation (even dimensions), and the
   documented transform from ARCore source image → encoded pixels is
   derived from the actual frozen render quad, not by scaling ARCore
   intrinsics. Persisted as `video.encoded_image.mapping.affine_enc_to_src`
   (6-coefficient 2-D affine, top-left pixel origin).
4. **Encoded-image intrinsics, honestly** — a rectilinear K for the encoded
   image exists *only* when the affine is a pure per-axis scale/flip
   (diagonal linear part). For the fleet devices the sensor is rotated
   −90° relative to the portrait display, so **no rectilinear K exists**;
   the canonical model is the chain
   `encoded px → affine → source px → K_src⁻¹ → camera ray → ARCore pose`.
   `session.json` records this refusal explicitly (never a guessed K).
   The measured texture rotation is persisted (`texture_rotation_deg`,
   derived from the fitted affine — ARCore 1.54's `Camera` exposes no
   sensor-orientation getter).
5. **Camera metadata** — per-frame `Frame.getImageMetadata()` (Camera2-
   derived in ARCore 1.54): exposure time, sensitivity, frame duration,
   rolling-shutter skew, video/OIS stabilization mode, scaler crop region.
   Only values the HAL actually reports are stored; a per-key availability
   table (three-state) goes into `camera/frames.json` and the session
   summary. **EIS is explicitly OFF** for captures (it changes image
   geometry); an A/B experiment with EIS on is a later, controlled step.
6. **Storage via SAF** — a user-selectable capture root
   (`ACTION_OPEN_DOCUMENT_TREE`, persistable permission via reflection —
   the API-34 SDK stubs in our toolchain lack the method, the runtime has
   it) with an app-private external fallback until the user picks one.
   No `MANAGE_EXTERNAL_STORAGE`. Sessions copy to the SAF root at finalize.
7. **Session browser** — in-app list of sessions (name, size, complete/
   incomplete) with delete; `SESSIONS` and `STORAGE` buttons in the layout.
8. **Validator upgrade** (`tools/inspect_capture.py`) — orientation/
   dimension consistency, mapping/rectilinear consistency (recomputed from
   the persisted affine + source K), metadata-file checks, and a
   **constant-border scan** of sampled frames that fails on the
   flat-fill/grey-side signature of the viewport bug.
9. **Headless acceptance mode** — `am start -n app.bildfang/.MainActivity
   --ei bf_autotest <seconds>` (optionally `--ez bf_autotest_need_tracking
   true`) records a fixed-length capture without any UI input; this is the
   regression primitive for the BF-T01 ladder below.

**Acceptance results (2026-09-02, Pixel 9 Pro / GrapheneOS 17 / ARCore 1.56,
headless 4 s):** encoder canvas 906×1406 portrait (matches the display
surface 906×1407, even-rounded); `texture_rotation_deg = −90` (measured
from the fitted affine; the 9 Pro texture is 1920×1080 with fx≈1390.8);
affine `[0, 1.1929, 121.39, −1.1921, 0, 1080.0]` (encoded 906×1406 → a
1678×1080 region of the 1920×1080 source texture, rotated −90° — the
aspect difference is the expected portrait crop, not a bug); rectilinear
model honestly `ABSENT`; 7/7 metadata keys `AVAILABLE_AND_CAPTURED`
(exposure ≈16.7 ms, ISO ≈1150, duration ≈33.3 ms, skew ≈4.5 ms, video-
stab mode 0 = off, OIS mode, crop region); counter invariant exact
(observed 240 = rate-skipped 121 + dropped 0 + submitted 119; encoded 119
= muxed 119); constant-border scan 100% active on all 9 sampled frames.
Unit tests: `SessionGeometryTest` (affine fit, 90° case, rectilinear
exact/refuse) and `CameraMetaJsonTest` — 25 tests, all green.

**Remaining P1 gate — Step 9 (human, 10–20 s):** a short walk capture by
the user in a normal room: the video must fill the frame in portrait,
show no flat/grey area, and `inspect_capture.py` must pass including the
border scan and (with the phone actually moving) the tracking-coverage
check. Only after that: BF-T01/T02 short-pipeline tests, then the
reconstruction work that has been deferred since the bug was found.

**BF-T01…T05 standard test ladder (permanent regression, shortest first):**

| Test | Content | Gate |
|------|---------|------|
| BF-T01 | 10 s headless static capture (phone on a table, textured background) | border scan, counters, intrinsics, metadata |
| BF-T02 | 10 s slow pan (hand-held) | + tracking coverage, pose cadence |
| BF-T03 | 30 s room walk, 3–4 stops | + trajectory continuity, discontinuity report sane |
| BF-T04 | interrupted capture (stop via force-stop mid-recording) | session marked incomplete, no corrupt MP4, files consistent |
| BF-T05 | full room (≈ washroom scale) | end-to-end; only after T01–T04 pass; feeds the BF pipeline |

No long reconstruction run (COLMAP/VGGT/MASt3R/GS) is justified until T01–T02
pass; multiple GPU-hours are only spent after T05.

## P2a — MediaCodec recorder: architecture, timestamp round-trip (REQUIRED)

**The primary, portable, preservation-grade recorder.** Replaces the
ARCore-native recorder (broken on Pixel 7 / GrapheneOS 17 — see P1
status) with one where frame timestamps and muxing are under our control.

### Architecture — Surface-based, no CPU image path

Do **not** use `Frame.acquireCameraImage()` / CPU YUV conversion as the
recording path (CPU image acquisition measurably throttles ARCore).

```text
ARCore GL_TEXTURE_EXTERNAL_OES
             |  (one OES texture per GL context, Session.setCameraTextureNames)
      +------+------+
      |             |
      v             v
   preview      MediaCodec input Surface (same GL update loop,
                 eglMakeCurrent onto a second context bound to the
                 encoder surface)
                     |
                     v
                  H.264  -->  MediaMuxer  -->  video/camera.mp4
```

- one `session.update()` per loop; after it, render the preview quad to
  the preview surface **and** (if recording) the same frame to the encoder
  surface — no second update, no dropped frames by design
- recorder abstraction so the activity is not coupled to either
  implementation:

```kotlin
interface VideoRecorder {
    fun start(...)     // returns config (timebase origin, counters handle)
    fun submitFrame(...)  // called from the GL update loop per new frame
    fun stop()         // flush encoder + finalize muxer (atomic)
    fun status()       // counters, state, warnings
}
// MediaCodecRecorder      -- default, portable, preservation-grade
// ArCoreDatasetRecorder   -- capability-dependent supplemental (P2b)
```

### Timestamp design (P3 domain model, applied to video)

For each camera frame retain the raw values:

- `Frame.getTimestamp()` (`arcore_frame`, epoch unknown/opaque)
- `Frame.getAndroidCameraTimestamp()` (`android_camera`)

**Video PTS is session-relative, derived from the camera clock — never
the raw camera timestamp as MP4 PTS:**

```text
video_pts_ns = android_camera_timestamp_ns
             - first_encoded_android_camera_timestamp_ns
```

Set it on the encoder input surface via the EGL presentation-time
mechanism (`eglPresentationTimeANDROID` before `eglSwapBuffers`) *before*
sending the buffer. Persist the exact origin in `session.json` so the
transformation is explicit and reversible:

```json
"video_timebase": {
  "source_clock": "android_camera",
  "origin_raw_ns": 1234567890123456,
  "unit": "ns"
}
```

### Encoder target (first version — conservative)

- AVC / H.264, **hardware encoder**, Surface input, 30 fps target
- rate control: CQ if the device encoder supports it and behaves
  consistently, else high-bitrate VBR; CBR only with a concrete reason
- deliberately generous initial bitrate, measured not assumed:
  ~50–80 Mbit/s for 4K30, ~15–30 Mbit/s for 1080p30 (reconstruction
  quality > file size)
- short GOP: I-frame interval ≈ 1 s
- no advanced encoding features until timestamp behavior is verified

### No silent drops

Counters, always in diagnostics/export metadata:

```
camera_frames_observed  frames_submitted_to_encoder
frames_encoded          frames_muxed          frames_dropped
```

`video/frames.json` is the **authoritative** mapping between source
observation and encoded media. One entry per encoded frame:

```json
{
  "idx": 482,
  "pts_ns": 16066712345,
  "android_camera_timestamp_ns": 834129912345678,
  "arcore_frame_timestamp_raw_ns": 1782345678901234567,
  "pose_index": 482
}
```

If pose/frame correspondence is not exactly 1:1, encode the actual
relationship (or explicit null) — never pretend it is. The encoder may
transform representation; it may **not** hide timing or frame-loss
behavior. Metadata must always answer offline: which camera observation
produced this frame, when it was acquired, which pose belongs to it,
what was dropped, and which clock transformation was applied.

### The round-trip test (P2a acceptance)

Prove the chain end-to-end on-device and quantify the residual at each
hop:

```text
android camera timestamp
    -> normalized encoder presentation time
    -> MediaCodec output BufferInfo.presentationTimeUs
    -> MediaMuxer MP4 PTS
    -> ffprobe / MediaExtractor
```

Expected: identity or a constant, small, documented offset (muxer
rounding). Any larger drift is a bug, not a property.

### On-device verification — Pixel 9 Pro, 2026-09-01 (GrapheneOS 17)

**Status: pipeline works end-to-end; one documented deviation (driver-
assigned container PTS).**

- 10 s probe: 293–298 encoded frames @ 30 fps, 28–30 MB H.264,
  muxer start, finalize, `frames.json` all correct. Counters are
  semantically split (2026-09-01, per review): `frames_rate_skipped`
  for the deliberate 60→30 fps cadence selection, `frames_dropped`
  reserved for real submit failures; invariant `observed =
  rate_skipped + dropped + submitted`, `submitted >= encoded >= muxed`.
- 30 s tracked capture (phone held at arm's length, textured wall):
  **1778 poses, 100 % TRACKING, 900 encoded frames, 93.5 MB;
  `tools/inspect_capture.py` → "RESULT: OK — session passes P1
  validation"** (counter invariants exact, PTS gaps median 33.332 ms /
  max 33.412 ms, camera-span vs container-span 29.965 s vs 29.988 s,
  max speed 0.08 m/s, zero discontinuities).
- Deviation from the round-trip expectation above: the Exynos driver
  assigns container PTS itself (presentation time cannot be set through
  the only working EGL binding — see status table). Measured residual
  vs the app's camera-clock PTS: p50 14.7 ms, p95 30.1 ms, max 54.3 ms
  over 900 aligned frames — bounded (≈1–2 frame intervals) and
  index-aligned, so `frames.json` (camera ts + persisted origin) is the
  authoritative mapping, exactly as the design anticipated for the
  "unknown" container-PTS domain. If a future device/driver honors
  presentation time again, the same check tightens automatically.
- Pixel 6 `pixel-7` (Exynos 2100 / Mali-G710) also verified the same
day (10 s + 30 s, all checks pass; container-PTS residual even smaller:
p50 4.0 ms / max 17.5 ms). Both fleet devices turned out to be Exynos
(see the device-identity correction under P0), so the Exynos-specific
findings apply to both and the javax-EGL capture trick needed no change
on either.

## P2b — ARCore native dataset recording (optional / capability-dependent)

`RecordingConfig` + MP4 dataset URI + custom `Track(UUID)` + per-frame
`Frame.recordTrackData(...)`: data written to a Frame is returned at the
same Frame during dataset playback, and `TrackData.getFrameTimestamp()` is
defined to equal the recording Frame's `Frame.getTimestamp()` — the
canonical ARCore-native synchronization test **where the native recorder
works**.

### Verdict — 2026-09-01: dead end on this fleet

| Device | ARCore certified | Preview / tracking | Native recorder |
|---|---|---|---|
| Pixel 6 `pixel-7` (Exynos 2100 / Mali-G710) / GrapheneOS 17 | yes (Play Store) | 30 fps, TRACKING | **fatal** at `nativeStartRecording` (~2 ms) |
| Pixel 9 Pro (EU, Exynos 2400 / zumapro) / GrapheneOS 17 | **yes** (Play Store offered it; `checkAvailability()` → ARCore ready) | **35 fps, TRACKING** | **fatal** — same signature (`recorder_util.cc:68` hinge-angle info line, then `FatalException` 3 ms later, no files; tested 15:09 UTC with a bare `RecordingConfig`, no custom track, active tracking) |

Two different **Exynos** SoCs (2100 and 2400), same OS family, identical
failure inside ARCore's recorder initialization (the "different SoC" the
original bisection intended — Tensor vs Exynos — was never actually
available: both fleet phones are Exynos, see the P0 identity
correction). (Amended 2026-09-01 after ChatGPT review: the earlier
reading — "GrapheneOS blocks video encoder components" — is **rejected**:
the stock camera records video on both devices on these exact builds, so
general encoder availability is not the issue.) The native recorder's
death is an unresolved **ARCore/GrapheneOS interaction** (its own native
encoder path, `recorder_util.cc`, dying before any frame — possibly a
specific component, policy, or an ARCore bug on this OS); it no longer
casts doubt on MediaCodec encoders in general. Per the 2026-09-01
decision: **stop debugging the native recorder.** P2b stays in the
documentation only — if a future GrapheneOS/ARCore release fixes it, it
becomes a supplemental capability on supported stock-GMS devices; it
does not block, and does not gate, Bildfang v1. `poses.json` remains the
independent sidecar; redundancy is intentional.

## P3 — Fix the clock model before freezing capture/v1

Do **not** describe `Frame.getTimestamp()` as Unix epoch — ARCore's time
base is explicitly undefined. Treat clocks as named domains:

```text
arcore_frame, android_camera, android_monotonic, wall_clock, sensor, container_pts
```

Do not imply two domains are identical until empirically demonstrated and
documented. Each timestamp stores value + unit + clock domain (or the
domain is unambiguous from schema docs).

Preserve raw at defined anchor points:

- `Frame.getTimestamp()`
- `Frame.getAndroidCameraTimestamp()`
- `SystemClock.elapsedRealtimeNanos()`
- wall-clock start/end (human provenance)

Never transform away the original. Session-relative values may be added
as *derived* convenience fields (e.g. `frame_timestamp_raw_ns`,
`frame_timestamp_relative_ns`, `android_camera_timestamp_ns`).

**Do not yet assert android-camera timestamp == encoded MP4 PTS.**
Derive the relationship empirically (ffprobe + ARCore playback +
TrackData timestamps + androidCameraTimestamp) and report:

```text
ARCore frame timestamp: ...
Android camera timestamp: ...
MP4 PTS: ...
relationship: PTS = ...
max observed residual = ...
```

Only then may `capture-format.md` state an invariant.

## P4 — Remove contradictory clock claims from capture-format.md

The doc currently contains both "multiple clock domains / ARCore clock
unknown" and the invariant "all *_ns fields share one monotonic clock".
Remove/rewrite. Distinguish:

- **Guaranteed**: ordering; custom-track↔Frame association; units;
  known clock domain
- **Measured**: Android-camera-timestamp ↔ MP4 PTS relationship
- **Unknown/opaque**: ARCore frame timestamp epoch

## P5 — Pose schema and world-frame discontinuities

Current `PoseJson` subtracts each segment's first translation but keeps
the original quaternion — not a complete SE(3) transform if the world
frame's orientation changed. Do not manufacture a normalized frame this
way. Prefer **raw ARCore camera poses**:

```text
translation_raw, rotation_raw, tracking_state, segment
```

Normalized poses, if useful, are *derived data* with a complete SE(3)
transform. Never overwrite the raw trajectory.

The `>2 m jump < 1 s` heuristic is only a **provisional anomaly
detector** — rename the concept to `trajectory_discontinuity` with
multiple signals:

- TRACKING → PAUSED → TRACKING transition
- translation jump
- angular jump
- implausible translational / angular velocity
- ARCore tracking failure reason where available
- elapsed time since last valid tracking frame

Event shape (informational, not a verdict — downstream decides whether
it means reset / relocalization / bad pose / fast motion):

```json
{
  "type": "trajectory_discontinuity",
  "frame": 842,
  "reason": ["tracking_recovered", "translation_jump"],
  "translation_jump_m": 2.41,
  "rotation_jump_deg": 37.2,
  "dt_ms": 66.7
}
```

## P6 — Intrinsics: document what is actually provided

ARCore `CameraIntrinsics` provides focal length, principal point, image
dimensions. Stop presenting `k1,k2,p1,p2,k3` as ARCore data (that model
belongs to Camera2 `CALIBRATION_REVIEW` metadata). Record `source`
explicitly (`"arcore"` vs `"camera2"`); if Camera2 distortion is added
later, document the model precisely.

Do not blindly scale intrinsics from the ARCore image to the encoded
video until validated: same physical camera, crop, zoom, sensor region,
optical geometry.

## P7 — Complete preservation-grade capture/v1

After timing + pose semantics are verified:

```text
capture-.../
|
+-- video/
|   +-- camera.mp4
|   +-- frames.json
|
+-- poses/
|   +-- poses.json
|
+-- imu/
|   +-- imu.csv
|
+-- camera/
|   +-- intrinsics.json
|
+-- metadata/
|   +-- device.json
|
+-- session.json
+-- manifest.json
```

Manifest written **last**; includes: schema version, app version, git
commit, device model, ARCore version where obtainable, start/end, file
sizes, SHA-256 per payload, warnings/discontinuities, completeness
status. Atomic finalization; an interrupted capture stays distinguishable
from a finalized one.

## P8 — Raw IMU logging

Preserve raw accelerometer + gyroscope `SensorEvent` samples with their
native timestamps. No resampling in the preservation layer. Measure the
actual sample rate; document what was observed, not an assumption.

## P9 — First capture-health signals (no neural networks)

Cheap, explainable signals:

- ARCore: `TrackingState`, `TrackingFailureReason`, feature availability
- motion (from poses/IMU): translational velocity, angular velocity,
  acceleration
- camera metadata where available: exposure, ISO, focal state
- image quality at low rate: sharpness/gradient energy, blur estimate,
  luminance, clipping

UI shows actionable messages (`GOOD`, `MOVE SLOWER`, `LOW VISUAL DETAIL`,
`TOO DARK`, `TRACKING LOST`); metric values are preserved in diagnostic
metadata. **Frames are never discarded because of these scores.**

## P10 — Local visual continuity

"Can the current frame still be linked visually to the recent capture?"
(Not loop closure.) Low-rate comparison of the current image against
recent keyframes (optical flow / ORB / geometric verification):

```text
continuity_score, verified_matches, inlier_ratio
-> visual chain healthy / weak / broken
```

Independent of ARCore tracking so the two systems cross-check.

## P11 — Coverage model

From the ARCore trajectory (position + orientation + keyframes): which
regions observed, from how many orientations, in-place rotation vs
useful parallax, revisits. Feedback: `MOVE INTO THE ROOM`, `VIEW THIS
AREA FROM ANOTHER ANGLE`, `GOOD PARALLAX`, `RETURN TOWARD START`. No
metric room reconstruction in the app.

## P12 — Learned Visual Place Recognition (only now)

"Does the current view correspond to a place seen much earlier?" VPR,
not object detection — do **not** start with YOLO. Research/benchmark
compact VPR for Pixel 7 / 9 Pro: EigenPlaces-style global descriptors,
compact/mobile VPR, distilled DINO-derived descriptors,
LiteRT/TFLite/ONNX runtimes. Benchmark latency / RAM / power / thermal /
descriptor size / quality. 1–2 Hz is enough for loop candidates.

## P13 — Geometrically verify loop candidates

Never declare loop closure from neural similarity alone:

```text
VPR global descriptor -> candidate retrieval -> local feature matching
-> RANSAC geometric verification -> verified loop closure
```

Combined with ARCore pose proximity. High-confidence event requires
several agreeing signals (place similarity + geometric verification +
pose consistency). UI: `LOOP CLOSED`.

## P14 — Detect ARCore drift by cross-checking VPR and pose

```text
VPR: same place as frame 120    ARCore: 2.8 m away   -> pose may have drifted
ARCore: 12 cm from start        VPR: no similarity  -> return-to-start unverified
```

Do not silently correct the trajectory in v1 — record the disagreement;
correction belongs in Bildwerk/offline reconstruction.

## P15 — Optional depth capture

After baseline stability. ARCore raw depth + confidence + its own
timestamps (depth updates slower than camera frames — preserve native
cadence). Optional, capability-gated, must not degrade RGB/pose
reliability.

## P16 — Semantic detection only if it solves a concrete problem

Possible later uses: people/dynamic objects, screens, mirrors, windows,
problematic reconstruction regions. Optional scene-understanding
metadata; must not block the core roadmap.

## Capture Assistance architecture

Keep intelligence modular:

```text
CaptureSignal
    timestamp, source, metric, value, confidence

CaptureAdvisory
    severity, code, human_message, supporting_signals
```

Signal examples: `arcore.tracking`, `motion.angular_velocity`,
`image.sharpness`, `vision.local_continuity`, `coverage.parallax`,
`vpr.revisit_similarity`, `loop.geometric_inliers`.

Advisory examples: `MOVE_SLOWER`, `INSUFFICIENT_FEATURES`,
`TRACKING_INTERRUPTED`, `GAIN_PARALLAX`, `REVISIT_START`, `LOOP_CLOSED`,
`POSSIBLE_POSE_DRIFT`.

## User experience target

The capture screen stays simple — not a telemetry dashboard:

```text
        CAPTURE GOOD
Tracking        OK
Visual chain    OK
Coverage        GOOD
     Return toward start
```

then

```text
       LOOP CLOSED ✓
Capture has a verified revisit.
You may stop or continue coverage.
```

Detailed metrics live behind a diagnostics view and in exported metadata.

## Immediate implementation order

Strictly, unless an earlier step exposes a blocker:

1. P1 real START → walk → STOP capture
2. inspect MP4 + JSON off-device
3. P2 custom-track playback round-trip
4. P3/P4 establish and document clock domains
5. P5 raw pose/discontinuity semantics
6. P6 validate intrinsics assumptions
7. P7 complete manifest/package
8. P8 raw IMU
9. freeze a trustworthy capture-format v1 baseline
10. P9 simple capture-health feedback
11. P10 local visual continuity
12. P11 coverage
13. P12/P13 learned VPR + verified loop closure
14. P14 drift cross-check
15. optional depth/semantics later

After every phase: document what was measured on real hardware;
distinguish verified fact from assumption; add tests where practical;
update this file; do not proceed when the next phase depends on an
unverified invariant.

**Bildfang is a trustworthy spatial recorder first, an intelligent
capture assistant second.**
