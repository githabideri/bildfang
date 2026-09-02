# Bildfang Capture Format — `bildfang-capture/v1`

One capture session = one self-describing folder. Nothing outside the folder
is needed to understand what was captured, by which device, and how the
files relate to each other.

```
capture-YYYYMMDDTHHMMSS-<6hex>/
├── video/
│   ├── camera.mp4
│   └── frames.json            # Phase 2: video pts ↔ ARCore frame index
├── poses/
│   └── poses.json             # one entry per ARCore-tracked frame
├── imu/
│   └── imu.csv
├── camera/
│   └── intrinsics.json
├── metadata/
│   └── device.json
└── manifest.json
```

**Naming:** the folder name equals `capture_id`. The `YYYYMMDDTHHMMSS` part
is the session start in UTC; `<6hex>` is 6 random hex chars for uniqueness.
This matches Bildwerk's spatial-capture session naming so a Bildfang session
folder can be dropped into a Bildwerk ingestion flow without renaming.

**Encoding:** all text files UTF-8. JSON: 2-space indent, one entry per line
where reasonable. CSV: LF line endings, CRLF-free.

> **Reading this document:** sections marked **[planned]** describe the
> target `bildfang-capture/v1` schema. What build 0.3.x actually writes is
> documented in **`session.json` — what build 0.3.0 actually writes**
> further down; where the two differ, that section wins for current
> captures. The ARCore-native-recording design described in places is a
> **dead end on the current fleet** (see ROADMAP P2b) and is kept only as
> history.

---

## The clocks — named domains, not one clock

**Do not assume any two clock domains are identical.** Every timestamp in
a bildfang capture belongs to exactly one *named clock domain*; the
relationship between domains is either **guaranteed by API contract**,
**measured** (documented with the residual), or **unknown** (treated as
opaque, raw value preserved).

| Domain name | Source | Status of epoch |
|-------------|--------|-----------------|
| `arcore_frame` | `Frame.getTimestamp()` | **Unknown/opaque.** ARCore documents no epoch. On ARCore 1.54 / Pixel 7 (2026-09-01) it is observed to be a fixed, monotonic value around ≈1.78×10¹⁸ ns — an observation, not a contract; do not code against it. |
| `android_camera` | `Frame.getAndroidCameraTimestamp()` | HAL camera clock (the timestamp the camera HAL attaches to each image). Its relation to the encoded video PTS is **measured in P1/P2** (ffprobe PTS + ARCore playback TrackData timestamps) and documented with a residual before any invariant is claimed. |
| `android_monotonic` | `SystemClock.elapsedRealtimeNanos()` (also `SensorEvent.timestamp`) | Monotonic since boot; **keeps counting through device sleep** (unlike `uptimeNanos()`). Shared by the IMU samples and by bildfang's own sampling; **not** shared with `arcore_frame`. Sensor *samples* can stop while the sensor is suspended — gaps in the data, not in the clock. |
| `wall_clock` | `System.currentTimeMillis()` / ISO 8601 UTC | Human provenance only (session start/end, `created_utc`). |
| `sensor` | `SensorEvent.timestamp` | On Android this *is* `android_monotonic`; listed separately so a future platform change is a schema note, not a silent break. |
| `container_pts` | MP4 presentation timestamps | **Current MediaCodec/EGL10 path: driver-assigned** (the javax EGL has no `eglPresentationTimeANDROID` binding; the app cannot write encoder-frame presentation times). Measured offset vs `android_camera` in P2a (index-aligned, bounded residual; `frames.json` stays the authoritative timestamp source). The ARCore-native path (PTS derived from camera timestamps) is a dead end on this fleet. |

**What bildfang stores:** raw values in each domain are always preserved
(`frame.timestamp` raw, `Frame.getAndroidCameraTimestamp()`,
`SensorEvent.timestamp`, wall-clock anchors). Session-relative values are
*derived* conveniences computed from a documented anchor, never a
replacement of the raw values.

The anchor is the first frame delivered to the app; `session.json` stores
the triple that pins the domains at one instant:

```json
"clock": {
  "anchor_frame_ts":     1780000000000000000,  // arcore_frame  (raw, epoch unknown)
  "anchor_unix_ms":      1788212345678,        // wall_clock
  "anchor_monotonic_ns": 4321000000000         // android_monotonic
}
```

so any consumer can derive `frame_ts = anchor_frame_ts + (t_relative)` and
cross to the other domains through the same anchor — without ever assuming
the domains are equal.

**Guaranteed / measured / unknown:**

- **Guaranteed** (API contract): ordering of frames and of poses; a pose
  and an embedded custom-track record written to the same `Frame` belong
  to the same camera frame; units are as documented; each field's domain
  is as tabled above.
- **Measured** (done, P2a 2026-09-01): the relationship between
  `android_camera` and `container_pts` on the current MediaCodec path —
  index-aligned, bounded residual (p50 ≈ 3–55 ms depending on device/driver;
  recorded per session), **driver-assigned** container PTS, never free-
  running: `frames.json` presentation values (camera-clock-derived) are
  authoritative, container PTS is measured and reported, never asserted
  equal.
- **Unknown/opaque**: the epoch of `arcore_frame`. Never asserted, never
  transformed away — the raw value is always stored alongside the derived
  one.

> **Legacy (v0.1/v0.2.x plans):** one shared `elapsedRealtimeNanos` clock
> across all files, `manifest.json` with sha256 hashes, an ARCore-native
> `video.mp4` embedding IMU and a custom JSON pose track, separate
> `imu.csv` / `intrinsics.json`. Parts of that remain the *target* for
> later versions as clearly-labeled derived conveniences on top of the raw
> domain values — never as a claim about the sources. The current app
> (v0.3.x) writes the self-describing session folder described in the
> `session.json` section below (MediaCodec-encoded `video/camera.mp4`,
> authoritative `video/frames.json`, `poses/poses.json`,
> `camera/frames.json` camera metadata).

## `manifest.json` **[planned — not written by current builds]**

Written last, atomically (write to `manifest.json.tmp` then rename). A
session without a complete `manifest.json` is invalid/incomplete. The
current build's completeness marker is instead a fully-populated
`session.json` (written after the video finalize); `sha256` file hashes
remain a target for a future build.

```json
{
  "schema": "bildfang-capture/v1",
  "capture_id": "capture-20260831T120000-ab12cd",
  "app": {
    "name": "Bildfang",
    "version": "0.1.0",
    "commit": "abc1234"
  },
  "device": {
    "manufacturer": "Google",
    "model": "Pixel 9 Pro",
    "android_version": "15",
    "sdk_int": 35
  },
  "arcore": {
    "play_services_version": "x.y.z",
    "tracking_features": ["motion", "imu_fusion"],
    "environment": "OUTDOOR"
  },
  "started_at": "2026-08-31T12:00:00.000Z",
  "ended_at": "2026-08-31T12:02:41.520Z",
  "duration_ns": 161520000000,
  "monotonic": {
    "clock": "SystemClock.elapsedRealtimeNanos",
    "boot_time_at_start_ms": 4321000,
    "session_start_ns": 4321000000000
  },
  "files": [
    { "path": "video/camera.mp4",  "type": "video",    "size_bytes": 629145600, "sha256": "…" },
    { "path": "video/frames.json", "type": "frame_index", "size_bytes": 182334, "sha256": "…" },
    { "path": "poses/poses.json",  "type": "pose",     "size_bytes": 912345,  "sha256": "…" },
    { "path": "imu/imu.csv",       "type": "imu",      "size_bytes": 402334,  "sha256": "…" },
    { "path": "camera/intrinsics.json", "type": "intrinsics", "size_bytes": 321, "sha256": "…" },
    { "path": "metadata/device.json", "type": "device", "size_bytes": 210, "sha256": "…" }
  ],
  "created_at": "2026-08-31T12:02:41.830Z"
}
```

`file.type` values: `video`, `frame_index`, `pose`, `imu`, `intrinsics`,
`device`. `sha256` makes the session auditable end-to-end (hashes are
computed by the app on export; downstream re-verification is a `sha256sum`
away).

## `poses/poses.json` — the most important output

JSON array; one entry per ARCore-tracked camera frame (typically 30 Hz, so
≈1800–3600 entries per minute → a few hundred KB; plain array, not JSONL,
chosen for tooling ergonomics at this size).

```json
{
  "schema": "bildfang-capture/v1-poses",
  "coordinate_system": "arcore-world-v1",
  "clock": "session-relative_ns (anchor in session.json)",
  "units": { "translation": "meters", "rotation": "quaternion x,y,z,w" },
  "poses": [
    {
      "i": 0,
      "timestamp_ns": 12345678901234,
      "android_camera_timestamp_ns": 9876543210,
      "translation": { "x": 0.0, "y": 0.0, "z": 0.0 },
      "rotation_quaternion": { "x": 0.01, "y": 0.22, "z": 0.03, "w": 0.97 },
      "tracking_state": "TRACKING"
    }
  ]
}
```

- `timestamp_ns` — ARCore frame timestamp, **relative to the session
  anchor** (see "The clocks"; not the raw ARCore epoch).
- `translation` — camera optical center position in the **ARCore world
  frame**, meters. See [coordinate-system.md](coordinate-system.md).
- `rotation_quaternion` — rotation **from camera local frame to world
  frame**, Hamilton convention, stored x,y,z,w.
- `tracking_state` — `TRACKING` | `PAUSED` | `STOPPED`. `PAUSED` means
  ARCore lost tracking (still recorded — it is diagnostic data, not
  garbage). Consumers should drop or interpolate across `PAUSED` runs.
- **World-frame resets:** if ARCore resets the world frame (rare; e.g.
  re-initialization after a long `PAUSED` period), the app increments a
  `segment` field and continues appending to the same array (entries carry
  `"segment": 1, 2, …`, default `0`). Consumers must only interpolate
  within a segment.

## `camera/intrinsics.json` **[planned]**

```
{
  "schema": "bildfang-capture/v1-intrinsics",
  "model": "pinhole",
  "arcore_image": {
    "width": 640, "height": 480,
    "fx": 321.4, "fy": 321.7,
    "cx": 320.0, "cy": 240.0
  },
  "camera_id": "back"
}
```

- ARCore's `CameraIntrinsics` API exposes **only** width, height, fx, fy,
  cx, cy — for the ARCore image size. It exposes **no distortion
  coefficients** (there is no 5-coefficient k1/k2/p1/p2/k3 getter on the
  ARCore intrinsics API; radial/tangential distortion data lives in
  Camera2 metadata and is not available through the ARCore camera object
  on the tested fleet devices). If a future build captures Camera2-
  metadata distortion, it goes into `camera/frames.json` alongside the
  other per-frame metadata, never into a fabricated field.
- The earlier draft of this section showed a `video` block with
  "ARCore intrinsics **scaled** from the ARCore image size to the video
  resolution". **That claim is wrong and has been removed**: scaling is
  invalid whenever the encoded mapping rotates or translates the source
  image (which it does on both fleet devices). The correct encoded-image
  geometry is the affine chain in `session.json` →
  `video.encoded_image.mapping`, with a derived rectilinear K only when
  mathematically valid (see below).
- If the session used multiple cameras (zoom switch), `camera_id` and the
  intrinsics block repeat per camera; v0.1 captures with a single fixed
  camera per session.

## `imu/imu.csv` **[planned — P8, not captured by current builds]**

```
timestamp_ns,ax,ay,az,gx,gy,gz
4321001234567,0.02,9.81,-0.11,0.001,0.002,-0.001
```

- Rows come from `SensorManager` (`TYPE_ACCELEROMETER`,
  `TYPE_GYROSCOPE`), sampled at `SENSOR_DELAY_GAME` (≈50 Hz; raised to
  `SENSOR_DELAY_FASTEST` in a later version if the device allows).
- `timestamp_ns` = `SensorEvent.timestamp` — domain `android_monotonic`
  (on Android, `SensorEvent.timestamp` is `elapsedRealtimeNanos`). This is
  a *different* domain than the ARCore frame clock; the two are
  reconciled offline through the session anchor, never by assuming
  equality.
- Acceleration in m/s² (gravity included), angular velocity in rad/s.
- If one sensor is missing on a device, the affected columns are empty
  (`4321…,0.02,9.81,-0.11,,,,`).
- ARCore's pose already fuses IMU; this file is the **unfused raw trace**
  — it exists so future pipelines can re-fuse independently (and so a
  pipeline can cross-check ARCore's trajectory against raw sensors).

## `video/`

- `camera.mp4` — H.264 (or the device's best practically-encodable codec),
  highest quality the device allows at a stable frame rate (v0.1 target:
  30 fps; 4K if the device sustains it, otherwise 1080p).
- `frames.json` (Phase 2) — maps each encoded video frame to its presentation timestamp

  ```json
  {
    "schema": "bildfang-capture/v1-frame-index",
    "container": "mp4",
    "codec": "h264",
    "resolution": [3840, 2160],
    "fps_nominal": 30,
    "pts_domain": "container_pts (source domain verified in P1/P2)",
    "frames": [
      { "idx": 0, "presentation_ns": 4321002000000 },
      { "idx": 1, "presentation_ns": 4321033300000 }
    ]
  }
  ```

  **Timestamp semantics (current MediaCodec/EGL10 path):** the MP4
  container PTS is **driver-assigned** — the javax EGL binding has no
  `eglPresentationTimeANDROID`, so the app cannot write per-frame
  presentation times into the container. The `presentation_ns` values in
  this file are the **authoritative** per-frame timestamps, derived from
  the camera clock (`androidCameraTimestamp − origin`); the container PTS
  is measured against them (bounded, index-aligned residual — see
  `tools/inspect_capture.py`), never assumed equal.

## `metadata/device.json`

```json
{
  "schema": "bildfang-capture/v1-device",
  "manufacturer": "Google",
  "model": "Pixel 9 Pro",
  "android_version": "15",
  "sdk_int": 35,
  "os": "GrapheneOS 20260701" ,
  "screen": { "width_px": 1280, "height_px": 2856, "density_dpi": 423 },
  "sensors": {
    "accelerometer": "lsm6dso",
    "gyroscope": "lsm6dso",
    "imu_rate_hz": 50
  },
  "camera": {
    "back": {
      "physical_id": "…",
      "sensors": ["color", "imaging"],
      "available_resolutions": [[3840, 2160], [1920, 1080]]
    }
  },
  "wall_clock_at_start": "2026-08-31T12:00:00Z",
  "boot_time_at_start_ms": 4321000
}
```

No `android_id`, no serial, no account identifiers — device identity is
kept at model level (the capture stays portable and non-identifying).

## Interruptions & invariants

- **Device sleep:** if the device suspends mid-capture, sensor *samples*
  stop (the sensors are suspended) while the `android_monotonic` clock
  value **keeps counting** (it does not pause during sleep; only
  `uptimeNanos()` pauses). Gaps therefore appear in the data streams,
  not in the clock. The app marks gaps in the session summary and
  continues poses in the same segment (no world reset). Consumers must
  not assume `t(n+1) - t(n)` is bounded in any domain.
- **Stop** writes files in this order: imu → poses → frames/video-index →
  intrinsics → device → manifest (manifest last = completeness marker).
- **Invariants consumers may rely on:**
  1. every `*_ns` field belongs to the named domain documented for it
     (see "The clocks"); no field is implicitly shared across domains;
  2. timestamps are non-decreasing within a segment, per domain;
  3. every ARCore `TRACKING` frame of the session has a pose entry, and a
     pose and an embedded track record written to the same `Frame`
     describe the same camera frame;
  4. raw domain values are always stored next to derived session-relative
     values; derived values are computed only from documented anchors;
  5. the manifest lists every file with size + sha256.

## `session.json` — what build 0.3.x actually writes (v1, 2026-09-02)

Build 0.3.0 (the first build of the geometry-frozen recorder) writes the
following `session.json` fields in addition to the sections above. All
geometry fields are **frozen at START** of the capture; nothing in this
schema changes mid-recording.

```
orientation                      "portrait" | "landscape" — chosen at START,
                                  frozen for the whole session
orientation_policy               "frozen at START; physical rotation during
                                  recording is logged, never applied"
rotation_events_during_recording int  (display-rotation change events while
                                  recording; 0 in a compliant capture)
video.orientation                same as above (the encoded video's)
video.preview_geometry           {width, height, display_rotation} — the GL
                                  surface size + display rotation at freeze
video.texture_rotation_deg       measured rotation (degrees, signed,
                                  90-multiple) of the ARCore camera texture
                                  relative to the source image, derived from
                                  the fitted mapping affine. ARCore 1.54
                                  exposes no sensor-orientation getter; the
                                  affine is the measurement.
video.source_image               {width, height, fx, fy, cx, cy} — ARCore
                                  Camera.getTextureIntrinsics() at START:
                                  the image the GL quad samples
video.source_camera_image        same, from getImageIntrinsics() (raw sensor
                                  image; equal to source_image on current
                                  fleet devices)
video.encoded_image.width/height encoder canvas in the frozen orientation
                                  (display canvas, even-rounded)
video.encoded_image.mapping      {kind, affine_enc_to_src[6], convention}
video.encoded_image.rectilinear_model  see "The encoded-image model" below
video.video_timebase             {source_clock: "android_camera",
                                  origin_raw_ns, unit: "ns"}
video.counters                   {camera_frames_observed, frames_submitted,
                                  frames_encoded, frames_muxed, frames_dropped,
                                  frames_rate_skipped}
camera_metadata.file             "camera/frames.json" (per-frame records)
camera_metadata.stabilization_config  e.g. "EIS OFF (explicitly set, ...)"
camera_metadata.availability     per-key three-state table:
                                  AVAILABLE_AND_CAPTURED |
                                  SUPPORTED_BUT_UNAVAILABLE_ON_DEVICE |
                                  NOT_EXPOSED_BY_CURRENT_API
storage.root / storage.sessions_path  where sessions are written (app-external
                                  default or a user-picked SAF tree)
```

**Counter invariants** (checked by `tools/inspect_capture.py`):
`camera_frames_observed == frames_rate_skipped + frames_dropped +
frames_submitted`; `frames_submitted >= frames_muxed`; `frames_encoded`
may exceed `frames_submitted` by at most the encoder drain at stop. `frames_rate_skipped`
is *deliberate* cadence sampling (source faster than the encoder rate);
`frames_dropped` is *failure* only. Neither is silent: both are counted
and persisted.

### The encoded-image model (read this before using the video)

The encoded pixels are related to the ARCore source image by a documented
2-D affine transform (top-left pixel origin):

```
p_src = M · p_enc + t        M = [[m00, m01], [m10, m11]], t = [tx, ty]
camera ray  = K_src⁻¹ · [p_src, 1]      (ARCore camera frame)
world pose  = ARCore device pose at the frame's camera timestamp
```

A rectilinear (zero-skew) intrinsic matrix for the encoded image **exists
for every 0/90/180/270-degree axis permutation** of the source image with
independent scale and translation — those are ordinary pinhole images in
encoded pixel coordinates:

```
diagonal M (0°/180°):          anti-diagonal M (90°/270°): axes swap
  fx_e = fx_s / |m00|            fx_e = fy_s / |m10|
  fy_e = fy_s / |m11|            fy_e = fx_s / |m01|
  cx_e = (cx_s − tx) / m00       cx_e = (cy_s − ty) / m10
  cy_e = (cy_s − ty) / m11       cy_e = (cx_s − tx) / m01
```

`rectilinear_model` in `session.json` carries the derived K plus the
rotation (fleet devices: **270°**, i.e. `texture_rotation_deg = −90`),
and the affine chain above remains the canonical description. `ABSENT`
is emitted **only for genuine shear / non-orthogonal** mappings, and
`REFUSED` when the mapping is non-affine or no texture intrinsics exist —
never a guessed/scaled K. (A 0.3.0 build predating the orthogonal-K fix
emitted a conservative `ABSENT (rotation/shear…)` for the fleet's 90°
cases; those sessions remain fully usable through the affine chain.)

Scaling ARCore intrinsics by the dimension ratio is *wrong* whenever the
mapping rotates, translates, or anisotropically scales — and a wrong
viewport/UV mapping is what invalidated the 2026-09-01 washroom video
(see ROADMAP P1.1).

#### The canonical projection chain (read before building rays)

For a 90°/270° mapping the encoded image is **image-axis-rotated**
relative to the ARCore source camera frame. A stored `K_encoded` is a
standard pinhole K in the *encoded camera frame* — not in the ARCore
camera frame. Consumers must use one of exactly these two chains (they
are mathematically identical, proven by the projection round-trip unit
test for all four rotations):

```
Chain A (canonical, always valid — prefer this one):
  encoded pixel (u, v)
  → affine_enc_to_src :  p_src = M·p_enc + t        (source-image pixels)
  → K_src⁻¹           :  ray in the ARCore camera frame
  → ARCore c2w pose   :  world coordinates

Chain B ("K + pose in one frame" form, for pipelines that need it):
  encoded pixel (u, v)
  → K_encoded⁻¹       :  ray in the ENCODED camera frame
  → R(k) axis rotation:  ray in the ARCore camera frame
                          (k = rectilinear_model.rotation; 3×3 R whose
                          2×2 part matches M, depth axis unchanged)
  → (ARCore c2w rotation · R)  :  world coordinates
```

The encoded camera frame shares the ARCore camera's optical center; its
c2w rotation is the ARCore c2w rotation composed with R(k).

**Do not** combine `K_encoded` with the *unchanged* ARCore pose (i.e.
treat the encoded image's axes as if they were the ARCore camera axes):
that is wrong for the fleet devices' 90/270° mapping (it is only valid
when k is 0/180 with a pure per-axis flip). `tools/inspect_capture.py`
recomputes `K_encoded` from the persisted affine + source K, so any
consumer can verify either chain against the session itself.

**Validity of the 2026-09-01 washroom capture
(`capture-20260901T200005-9dc67864`)**: VALID for ARCore trajectory
analysis (poses are independent of the video bug); **INVALID for any
photometric use** — the encoded frames are corrupted (viewport bug).

## Versioning

`"schema": "bildfang-capture/v1"` — future breaking changes bump the
version (`/v2`) and the app records both old and new where possible.
Non-breaking additions (new optional fields) do not bump. Consumers must
ignore unknown fields, not fail on them.
