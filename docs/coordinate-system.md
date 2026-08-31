# Coordinate System — `arcore-world-v1`

**This document is load-bearing.** Any pipeline consuming `poses.json` must
know exactly what x/y/z and the quaternion mean. ARCore pose is *not*
absolute ground truth; it is a high-quality trajectory estimate, used for
initialization, diagnostics, and extra constraints — never as a final
result.

Sources: ARCore documentation —
[Coordinate systems & transforms](https://developers.google.com/ar/develop/java/transforms),
[ARCore supported devices](https://developers.google.com/ar/devices).

## 1. World frame (ARCore)

| Property | Value |
|---|---|
| Handedness | **right-handed** (x × y = z) |
| **X** | to the **right** of the device at session start |
| **Y** | **up** (aligned with gravity, via IMU fusion) |
| **Z** | **back** — pointing *away* from the scene, i.e. out of the screen |
| Camera forward | **−Z** of the camera frame |
| Origin | **arbitrary** — set by ARCore when tracking initializes (typically near the start pose). Not Earth-fixed, not GPS-aligned |
| Yaw | **arbitrary** — the initial device heading is yaw 0. Only the Y-up alignment is physical |
| Units | meters |
| Mutability | static within a segment; a tracking reset starts a new segment (see capture-format.md) |

Consequences:

- You **cannot** compare two sessions by absolute position or absolute yaw.
- You **can** compare *shapes* (translation relative to the start point,
  rotations) — which is all that trajectory validation needs.
- The trajectory is anchored by convention: **pose[0] of segment 0 is the
  origin** in all exported data (ARCore reports the initial pose ≈ (0,0,0),
  but the app normalizes the first segment to exactly (0,0,0)).

## 2. Camera (local) frame

The ARCore camera frame uses the **OpenGL convention**:

- X to the right of the image,
- Y up in the image,
- Z **back** through the camera (out of the lens) — the camera looks along
  **−Z**.

The origin of this frame is the **camera optical center** (principal point
in camera space; lens-center approximation — see caveats below).

## 3. The pose

For each frame, `Pose p` is a rigid transform **from camera local frame to
world frame**:

```
world_point = p.transform(local_point)
```

- `translation` = the camera optical center in world coordinates
  (`p.getTranslation()`), meters.
- `rotation_quaternion` {x, y, z, w} = the rotation part of `p`, i.e. it
  rotates a **local-frame vector into the world frame** (Hamilton
  quaternions, `q = [x, y, z, w]`).

Quaternion → 3×3 matrix `R` (applied as `v_world = R · v_local`):

```
R =
[ 1-2(y²+z²)   2(xy-wz)     2(xz+wy)  ]
[ 2(xy+wz)     1-2(x²+z²)   2(yz-wx)  ]
[ 2(xz-wy)     2(yz+wx)     1-2(x²+y²) ]
```

Useful directions in the **world** frame (all = R · local vector):

```
forward  = R · (0, 0, -1)     # where the camera looks
up       = R · (0, 1, 0)
right    = R · (1, 0, 0)
```

`tools/plot_trajectory.py` uses exactly this to draw viewing-direction
arrows.

## 4. Conversion to pipeline conventions

| Pipeline convention | Frame | Conversion from ARCore |
|---|---|---|
| **OpenGL** (Nerfstudio/Splatfacto-style splatting, many renderers) | x right, y up, **z back** | ARCore local frame **as-is**; world frame as-is. No conversion of pose. |
| **OpenCV** (COLMAP image/camera convention) | x right, **y down**, **z forward** | `R_opencv→world = R_arcore → world · M`, where `M = [[0,-1,0],[1,0,0],[0,0,-1]]`. Translation unchanged. |
| MASt3R-SLAM / VGGT | varies per backend | pin down in Phase 4, per backend, and record the exact mapping in the Bildwerk pipeline definition. |

Rule of thumb: **ARCore world + camera frames = OpenGL convention.**
Anything that expects "camera looks along +Z" (OpenCV) needs the `M`
flip on the *camera* frame only; the world frame (Y up) is unaffected.

## 5. Caveats & honesty requirements

- **Intrinsic–extrinsic mismatch:** the pose refers to the *optical
  center* of a virtual ARCore camera model, while video frames come from
  the physical camera pipeline. For the ARCore-supported devices here the
  discrepancy is small (mm-scale) and sub-degree, but it is **not zero**.
  A pipeline that re-estimates poses from images (COLMAP) may see a small
  systematic offset vs. ARCore — expected, and one reason ARCore pose is a
  *constraint*, not a measurement.
- **Drift:** ARCore VIO drifts over minutes; it is calibrated against the
  environment but has no loop closure in v0.1. A walk-loop test (walk
  around, return to start) measures this drift directly — the loop-closure
  distance printed by the plot tool *is* the drift metric.
- **World reset** after long `PAUSED` tracking → new segment (documented in
  capture-format.md). Consumers must never interpolate across segments.
- **Not ENU:** the world frame is Y-up but **not** Earth-oriented; do not
  use it as a map frame.

## 6. Validation recipe (do this for every new capture)

1. Run `tools/plot_trajectory.py poses/poses.json --out trajectory.png`.
2. Check: (a) the top-down path matches what you remember walking;
   (b) forward arrows point where you were looking; (c) loop-closure
   distance is small if you returned to start; (d) no long `PAUSED` gaps.
3. If the path looks *physically* wrong → the capture is insufficient; do
   not feed it to reconstruction.
