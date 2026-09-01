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
| `android_monotonic` | `SystemClock.elapsedRealtimeNanos()` (also `SensorEvent.timestamp`) | Monotonic since boot, pauses on device sleep. Shared by the IMU samples and by bildfang's own sampling; **not** shared with `arcore_frame`. |
| `wall_clock` | `System.currentTimeMillis()` / ISO 8601 UTC | Human provenance only (session start/end, `created_utc`). |
| `sensor` | `SensorEvent.timestamp` | On Android this *is* `android_monotonic`; listed separately so a future platform change is a schema note, not a silent break. |
| `container_pts` | MP4 presentation timestamps | Whatever clock the muxer was fed (for ARCore-native recordings: derived from the camera image timestamps — to be verified in P1/P2). |

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
- **Measured** (pending, P1/P2): the transformation between
  `android_camera` and `container_pts` for the ARCore-native MP4 —
  expected to be identity or a constant offset; the residual is recorded
  when measured.
- **Unknown/opaque**: the epoch of `arcore_frame`. Never asserted, never
  transformed away — the raw value is always stored alongside the derived
  one.

> The Phase-0 ideal (one shared `elapsedRealtimeNanos` clock across all
> files, `manifest.json` with sha256 hashes, separate `imu.csv` /
> `frames.json` / `intrinsics.json`) remains the target for later versions
> — as a *derived, clearly-labeled* convenience on top of the raw domain
> values, not as a claim about the sources. The current app (v0.2.x)
> writes `session.json` + `poses/poses.json` + `video.mp4` (ARCore-native
> MP4, which embeds the IMU and a custom JSON pose track in the container
> itself).

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

  Presentation timestamps are written explicitly to the muxer (no
  free-running encoder clock), so video ↔ pose ↔ IMU alignment is derived from the measured domain relationships
  above, not estimated per frame.

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

- **Device sleep:** if the device suspends mid-capture, `android_monotonic`
  (and the sensor timestamps on it) stops, while `wall_clock` continues.
  The app marks the gap in `manifest.json` (`interruptions: [{
  "from_ns": …, "to_ns": …, "reason": "sleep" }]`) and continues poses in
  the same segment (no world reset). Consumers must not assume
  `t(n+1) - t(n)` is bounded in any domain.
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

## Versioning

`"schema": "bildfang-capture/v1"` — future breaking changes bump the
version (`/v2`) and the app records both old and new where possible.
Non-breaking additions (new optional fields) do not bump. Consumers must
ignore unknown fields, not fail on them.
