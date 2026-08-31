# GrapheneOS & ARCore — Device Setup Requirements

Target devices: **Pixel 7** and **Pixel 9 Pro**, both running **GrapheneOS**
with a **sandboxed Google Play Store + sandboxed Play services installed**
(standard setup on this fleet). No assumption of a full GMS install.

## ARCore: what it actually is

ARCore is **not** part of the OS and **not** an SDK-only dependency. It is a
separate service app — **"Google Play Services for AR"**
(`com.google.ar.core`) — that apps bind to. The ARCore Android SDK
(`com.google.ar:arcore`) is a thin client: an "AR Required" app must call
`ArCoreApk.checkSupport(context)` at startup and, if the service is
missing/disabled, point the user at installing it.

Consequences for Bildfang:

- `minSdkVersion` ≥ 24 (AR-Required requirement).
- The app **must handle the three states**: supported / install needed /
  enabled but version too old — with a clear on-screen message and an
  action to install/enable, not a crash.
- The APK declares the ARCore dependency; it does **not** bundle ARCore.

## Status per device

| Device | GrapheneOS | ARCore-certified | Status / evidence |
|---|---|---|---|
| Pixel 7 (Tensor G2) | official build, supported | **Yes** — listed on [ARCore supported devices](https://developers.google.com/ar/devices) (snapshot 2026-08-25): OpenGL ES 3.2, GPU texture up to 1080p, no Depth API | ARCore can be installed via the sandboxed Play Store (see below). **Verify on-device** with `checkSupport()` in the Phase-1 smoke test |
| Pixel 9 Pro (Tensor G4) | official build, supported (GrapheneOS [releases](https://grapheneos.org/releases) list the Pixel 9 Pro as a supported device) | **Unverified** — the public lists I checked do not show the Pixel 9 family (a [Play-Console-list mirror](https://github.com/rolandsmeenk/ARCore-devices) last updated 2024-02 stops at Pixel 8; a 2026-08-25 snapshot of Google's device page shows no Pixel 9 rows) | **Open question — decide on-device.** If `checkSupport()` fails, options: wait for a Play-services-for-AR update that certifies it, or accept Pixel 7 as the primary capture device |

The app must treat both identically at runtime (support check, never a
compile-time assumption).

## Installing ARCore on GrapheneOS

GrapheneOS ships **without** Google apps, but this fleet's devices run the
official **sandboxed** Play Store + Play services. What is known:

1. **Play Store path (primary):** with the sandboxed Play Store present,
   "Google Play Services for AR" is installable — the same route users on
   GrapheneOS use successfully (e.g. GrapheneOS forum threads
   [d/8671](https://discuss.grapheneos.org/d/8671-sandboxed-play-services-for-ar),
   Nov 2023, and [d/20063](https://discuss.grapheneos.org/d/20063-cannot-install-google-play-services-for-ar),
   Feb 2025, Pixel 9 Pro Fold — the latter needed the normal Play-Store
   search + a cache clear, not the in-app "enable" deep link).
   - The in-app ARCore "enable/install" deep link (standard Google Play
   flow) has been reported as a **dead end on GrapheneOS** — the prompt
   shows but nothing installs ([GrapheneOS apps repo issue #130,
   open](https://github.com/GrapheneOS/apps.grapheneos.org/issues/130),
   Jan 2025). **Bildfang's install prompt therefore links to a manual
   search** ("open Play Store → search 'ARCore' / 'Play Services for AR'")
   rather than relying only on the deep link.
2. **Sideload path (fallback):** the ARCore APK is available on
   [APKMirror](https://www.apkmirror.com/apk/google-inc/arcore/).
   Sideload on GrapheneOS is unverified; keep as fallback only.
3. **Not available** from the GrapheneOS app store (it mirrors Play
   services but not the AR services; see issue #130).
4. **No emulator path:** GrapheneOS provides no Play-signed emulator image,
   and ARCore needs a real, certified device. Development loop = build on
   the dev box → `adb install` (USB or Wi-Fi) → test on the physical
   Pixel. There is no headless simulation of ARCore tracking.

## Privacy & permissions

| App | Network permission | Notes |
|---|---|---|
| **Bildfang** | **none** — the app declares no `INTERNET` permission at all | local-only by construction; nothing to revoke |
| Play Services for AR | yes (may phone home) | on GrapheneOS, **restrict/revoke ARCore's network access** in the per-app settings; tracking itself is on-device. Re-check after every ARCore update (a new version may request different permissions — GrapheneOS shows this in the permission overlay) |

Captured data never leaves the device from Bildfang's side; any later
transfer to a Bildwerk host is a conscious user action (adb pull / cable /
Nextcloud), never app-initiated.

## First-run checklist (both devices, before first capture)

- [ ] GrapheneOS up to date; Play Store (sandboxed) signed in
- [ ] "Google Play Services for AR" installed & enabled (Play Store search)
- [ ] ARCore network permission: **restricted/revoked**
- [ ] Bildfang permissions granted: camera, sensors (IMU), storage (session
      directory)
- [ ] In-app `checkSupport()` → "supported" (Phase 1 smoke test; on the
      Pixel 9 Pro this is the decision gate)
- [ ] Battery: disable battery optimization for Bildfang during captures

## Open questions (resolve in Phase 1)

1. **Pixel 9 Pro ARCore certification** — the decisive unknown. Decision
   tree: certified → dual-device capture; not certified → Pixel 7 is the
   v0.1 device, Pixel 9 Pro becomes "follow-up".
2. Does ARCore inside the *sandboxed* Play profile expose camera/sensor
   data to an app outside that profile without issue? (ARCore is a system
   service app; the app binds to it across profiles — expected to work,
   verify empirically; if it doesn't, the fallback is a non-sandboxed GMS
   install, which changes the privacy posture.)
3. Sustained 30 fps 4K encoding + 30 Hz ARCore + 50 Hz IMU without thermal
   throttling on each device (determines v0.1 video resolution target).
