# bildfang — Android app

Minimal ARCore pose-logger. Start → record camera pose per frame → Stop →
exports `poses.json` (`bildfang-capture/v1-poses`) to
`/sdcard/Android/data/app.bildfang/files/sessions/capture-<UTC>-<6hex>/poses/`.

Phase 1: pose capture only. **No video, no IMU file, no preview** (black
screen + status readout). Video/imu/frame-extraction land in Phase 2.

## Layout

```
app/
├── build.gradle.kts        # AGP 8.6.0, Kotlin 1.9.24, arcore 1.54.0
├── settings.gradle.kts
├── gradle/                 # wrapper (8.10.2)
└── src/
    ├── main/
    │   ├── AndroidManifest.xml   # CAMERA only — no INTERNET, by design
    │   ├── kotlin/app/bildfang/
    │   │   ├── MainActivity.kt   # UI + ARCore session + capture loop
    │   │   └── PoseData.kt       # PoseRecord + PoseJson (pure Kotlin)
    │   └── res/                  # activity_main.xml, strings.xml
    └── test/
        └── java/app/bildfang/PoseJsonTest.kt   # 4 tests
```

## Build (any Linux box with JDK 17 + Android SDK)

```sh
cd app
echo "sdk.dir=/path/to/sdk" > local.properties   # gitignored
./gradlew assembleDebug
# → build/outputs/apk/debug/bildfang-debug.apk
./gradlew testDebugUnitTest
```

SDK bootstrap on a bare host (headless, no Android Studio):

```sh
# commandlinetools URL uses the _latest.zip suffix (verified 2026-08-31,
# build 13114758 — the older -linux.zip pattern is dead):
curl -O https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -d sdk/cmdline-tools cmdtools.zip && mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
yes | sdk/cmdline-tools/latest/bin/sdkmanager --licenses \
  "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

`sdkmanager --licenses` writes the canonical `licenses/` hashes — do that
instead of hand-writing them (the hashes change when Google rewrites the
license text; hand-copied ones from older docs no longer match).

## ARCore API gotchas (verified against 1.54.0, Apr 2026)

- **Maven coordinate is `com.google.ar:core`** (not the historical
  `com.google.ar:arcore`, which 404s on Google Maven). Java package is
  still `com.google.ar.core.*`.
- **`setFrameListener` is gone** — the API is pull-style: call
  `session.update()` yourself and it returns the current `Frame`. This
  app drives it at display refresh via `Choreographer.postFrameCallback`
  (main thread, no GL surface needed for pose-only capture).
- **`Session.Builder` is gone** — use `Session(context)`; it throws
  checked `Unavailable*Exception`s.
- **`ArCoreApk.checkSupport` is gone** — use
  `ArCoreApk.getInstance().checkAvailability(context)` →
  `Availability` enum (`SUPPORTED_INSTALLED`, `SUPPORTED_NOT_INSTALLED`,
  `SUPPORTED_APK_TOO_OLD`, `UNSUPPORTED_DEVICE_NOT_CAPABLE`, `UNKNOWN_*`).
- GrapheneOS note: `checkAvailability` only reflects a *Play-installed*
  ARCore service; the manually installed "Google Play Services for AR"
  may report differently — the first on-device test will tell us.

## Install & test (GrapheneOS)

```sh
adb install build/outputs/apk/debug/bildfang-debug.apk   # or transfer + "Install"
```

1. App starts → status line must say **"ARCore ready"** (else follow the
   install instructions printed in the app).
2. **Start Capture** → allow camera permission → screen shows duration /
   frame count / tracking state. Walk around a room for ~2 min, include a
   slow loop back to the start.
3. **Stop Capture** → status shows the exported file path.
4. Pull and verify:
   ```sh
   adb pull /sdcard/Android/data/app.bildfang/files/sessions/
   python3 ../tools/plot_trajectory.py <session>/poses/poses.json
   ```
   Expect: sane trajectory, ~30 fps, first pose at (0,0,0).

**Phase-1 acceptance:** one session folder with valid `poses.json` on
both Pixel 7 and Pixel 9 Pro (the 9 Pro run doubles as the ARCore
certification check — see `docs/grapheneos.md`).
