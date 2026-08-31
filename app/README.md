# app/ — Bildfang Android app (Phase 1+)

The Gradle project lives here (this directory is a placeholder until Phase 1
lands). Planned shape when it does:

```
app/
├── settings.gradle.kts
├── build.gradle.kts          # root + :app (single module)
├── gradle/wrapper/…          # committed
└── src/main/
    ├── AndroidManifest.xml   # CAMERA + no INTERNET
    └── kotlin/app/bildfang/
        ├── CaptureActivity.kt   # the whole UI: start/stop/status
        ├── ArCoreCapture.kt     # Session, per-frame pose + IMU capture
        ├── ImuRecorder.kt       # SensorManager → imu.csv rows
        ├── VideoRecorder.kt     # MediaCodec+MediaMuxer, explicit pts (Phase 2)
        └── SessionExporter.kt   # session folder + manifest (Phase 3)
```

Constraints:

- Kotlin, AGP 8, `minSdk 24` (AR-Required), `targetSdk` current.
- ARCore: `com.google.ar:arcore` (client only — the service APK is
  installed on the device, see [../docs/grapheneos.md](../docs/grapheneos.md)).
- **No `INTERNET` permission.** No third-party network libraries, ever.
- One activity, no view model ceremony — it is a logger, not an app.
- Format spec: [../docs/capture-format.md](../docs/capture-format.md).
  The exporter is the *only* writer of session files; it implements the
  write order and manifest-last invariant.
