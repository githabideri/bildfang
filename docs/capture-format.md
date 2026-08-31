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

---

## The clocks (critical)

**Verified on-device (ARCore 1.54, Pixel 7, 2026-09-01):** `Frame.getTimestamp()` is **not** on `SystemClock.elapsedRealtimeNanos()` — it is a large fixed-epoch value (≈1.78×10¹⁸ ns, likely unix nanoseconds) on its own internal clock. bildfang therefore does **not** assume any shared epoch.

All bildfang frame clocks are **session-relative**: `frame.timestamp - anchor`, where the anchor is the timestamp of the first frame delivered to the app. `session.json` stores the anchor triple for absolute reconstruction:

```json
"clock": {
  "frame_timestamp_ns": "ARCore frame clock, epoch unknown (fixed, likely unix ns)",
  "anchor_frame_ts": 1780000000000000000,
  "anchor_unix_ms":  1788212345678,
  "anchor_monotonic_ns": 4321000000000
}
```

- `anchor_unix_ms` / `anchor_monotonic_ns` are read from `System.currentTimeMillis()` / `SystemClock.elapsedRealtimeNanos()` at the instant of the first frame, so any absolute epoch can be derived offline.
- Every pose additionally carries `android_camera_timestamp_ns` (`Frame.getAndroidCameraTimestamp()`) — the **HAL camera clock**, which is the clock the video encoder uses for presentation timestamps. This is the field that aligns poses to video PTS; the ARCore frame timestamp aligns poses to each other.
- Wall-clock time appears in `session.json` (`created_utc`, ISO 8601 UTC) only as a reference.

> The Phase-0 ideal (one shared `elapsedRealtimeNanos` clock across all files, `manifest.json` with sha256 hashes, separate `imu.csv` / `frames.json` / `intrinsics.json`) remains the target for later versions. The current app (v0.2.0) writes `session.json` + `poses/poses.json` + `video.mp4` (ARCore-native MP4, which already embeds the IMU and a custom JSON pose track in the container itself).

## `manifest.json`

Written last, atomically (write to `manifest.json.tmp` then rename). A
session without a complete `manifest.json` is invalid/incomplete.

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

## `camera/intrinsics.json`

```json
{
  "schema": "bildfang-capture/v1-intrinsics",
  "model": "pinhole",
  "arcore_image": {
    "width": 640, "height": 480,
    "fx": 321.4, "fy": 321.7,
    "cx": 320.0, "cy": 240.0
  },
  "distortion": [0.01, -0.02, 0.0001, -0.0001, 0.0],
  "video": {
    "width": 3840, "height": 2160,
    "fx": 1928.4, "fy": 1930.5,
    "cx": 1920.0, "cy": 1080.0,
    "note": "ARCore intrinsics scaled from the ARCore image size to the video resolution; valid while the same physical camera is used"
  },
  "camera_id": "back"
}
```

- ARCore reports intrinsics for **its own image size**, not the video
  encoder's resolution — the `video` block is the scaled version, which is
  what image-based pipelines (COLMAP, VGGT, splatting) need.
- `distortion` uses ARCore's 5-coefficient model (k1, k2, p1, p2, k3).
  If ARCore reports none for a device, the field is `null`.
- If the session used multiple cameras (zoom switch), `camera_id` and the
  intrinsics block repeat per camera; v0.1 captures with a single fixed
  camera per session.

## `imu/imu.csv`

```
timestamp_ns,ax,ay,az,gx,gy,gz
4321001234567,0.02,9.81,-0.11,0.001,0.002,-0.001
```

- Rows come from `SensorManager` (`TYPE_ACCELEROMETER`,
  `TYPE_GYROSCOPE`), sampled at `SENSOR_DELAY_GAME` (≈50 Hz; raised to
  `SENSOR_DELAY_FASTEST` in a later version if the device allows).
- `timestamp_ns` = `SensorEvent.timestamp` — **the same monotonic clock**
  as everything else.
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
- `frames.json` (Phase 2) — maps each encoded video frame to the monotonic
  clock:

  ```json
  {
    "schema": "bildfang-capture/v1-frame-index",
    "container": "mp4",
    "codec": "h264",
    "resolution": [3840, 2160],
    "fps_nominal": 30,
    "frames": [
      { "idx": 0, "presentation_ns": 4321002000000 },
      { "idx": 1, "presentation_ns": 4321033300000 }
    ]
  }
  ```

  Presentation timestamps are written explicitly to the muxer (no
  free-running encoder clock), so video ↔ pose ↔ IMU alignment is exact by
  construction, not estimated.

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

- **Device sleep:** if the device suspends mid-capture, the monotonic clock
  stops. The app marks the gap in `manifest.json` (`interruptions: [{
  "from_ns": …, "to_ns": …, "reason": "sleep" }]`) and continues poses in
  the same segment (no world reset). Consumers must not assume
  `t(n+1) - t(n)` is bounded.
- **Stop** writes files in this order: imu → poses → frames/video-index →
  intrinsics → device → manifest (manifest last = completeness marker).
- **Invariants consumers may rely on:**
  1. all `*_ns` fields share one monotonic clock;
  2. pose timestamps are non-decreasing within a segment;
  3. every ARCore `TRACKING` frame of the session has a pose entry;
  4. the manifest lists every file with size + sha256.

## Versioning

`"schema": "bildfang-capture/v1"` — future breaking changes bump the
version (`/v2`) and the app records both old and new where possible.
Non-breaking additions (new optional fields) do not bump. Consumers must
ignore unknown fields, not fail on them.
