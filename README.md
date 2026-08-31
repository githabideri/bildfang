# Bildfang

**Spatial Capture for Bildwerk.**

Bildfang is a minimal, local-only Android app that captures high-quality
spatial observation data from a phone: video, camera metadata, per-frame
device pose (via ARCore), and IMU traces — written to a self-describing,
versioned session folder. It is a *scientific/spatial camera logger*, not a
3D scanner, viewer, or cloud service.

The first milestone is **successful capture and export of trustworthy data**.
Nothing in this app reconstructs, renders, or uploads anything.

## Relationship to Bildwerk

- Bildfang is a **separate repository** with its own lifecycle and APK
  releases. It has **no dependency on the Bildwerk Python stack** — it is a
  native Kotlin/Android app.
- Its output is designed to be **consumed by Bildwerk pipelines**
  (COLMAP, MASt3R-SLAM, VGGT, Gaussian Splatting, future methods). The
  session layout and `capture-<timestamp>-<id>` naming are aligned with
  Bildwerk's spatial capture format so a session folder can be handed over
  directly (Bildwerk integration itself is a later phase, once the format
  stabilizes).
- ARCore pose is **not absolute ground truth** — it is a high-quality
  trajectory estimate providing initialization, diagnostics, and additional
  constraints for reconstruction. See [docs/coordinate-system.md](docs/coordinate-system.md).

## What this app is NOT

- ❌ a 3D scanner app (no mesh/point-cloud output)
- ❌ a viewer (no AR display, no photo gallery)
- ❌ a cloud service (no account, no upload, no telemetry, no analytics)
- ❌ a social app, an AR demo

## Privacy

Local only. The app requests **no network permission**. Capture data is
written to app storage and stays on the device; the user owns it.

## Output: one session = one folder

```
capture-YYYYMMDDTHHMMSS-<6hex>/
├── video/
│   └── camera.mp4
├── poses/
│   └── poses.json
├── imu/
│   └── imu.csv
├── camera/
│   └── intrinsics.json
├── metadata/
│   └── device.json
└── manifest.json          # "bildfang-capture/v1"
```

See [docs/capture-format.md](docs/capture-format.md) for the full schema and
[docs/coordinate-system.md](docs/coordinate-system.md) for what x/y/z mean
(critical for downstream pipelines).

## Repository layout

```
bildfang/
├── app/          # Kotlin Android app (Phase 1+)
├── docs/
│   ├── capture-format.md
│   ├── coordinate-system.md
│   └── grapheneos.md
├── tools/
│   └── plot_trajectory.py   # first validation: does the path look physically correct?
├── samples/
│   └── example-capture/     # synthetic miniature session for tooling/CI
└── .github/workflows/build-apk.yml
```

## Status

| Phase | Scope | Status |
|-------|-------|--------|
| 0 | Repository, capture format, coordinate system, GrapheneOS requirements | ✅ done |
| 1 | Minimal app: start/stop, ARCore session, poses.json export | 🟡 built + unit-tested, awaiting first on-device capture |
| 2 | Video recording + timestamp sync (poses ↔ video frames) | ⬜ |
| 3 | IMU export, manifest generation, trajectory tooling | ⬜ |
| 4 | Bildwerk integration (capture folder → Bildwerk manifest) | ⬜ |

**v0.1 success criteria:** a capture session folder containing (1) a video,
(2) a pose trajectory, (3) camera metadata, (4) IMU data, (5) a documented
capture format, (6) a trajectory plot — with the recorded path looking
physically correct. Not a 3D model.

## Building

GitHub Actions builds a debug APK on push to `main` and a signed release APK
on tags (`v0.1.0` → `Bildfang-v0.1.0.apk`). See
[.github/workflows/build-apk.yml](.github/workflows/build-apk.yml).
Install manually (`adb install` or copy the APK to the phone). No Play Store
distribution.

**Device notes:** targets Pixel 7 and Pixel 9 Pro, including on GrapheneOS.
See [docs/grapheneos.md](docs/grapheneos.md) for ARCore availability and
setup requirements (ARCore requires "Google Play Services for AR",
installable via a Play Store that is present on the device).

## License

MIT — see [LICENSE](LICENSE).
