#!/usr/bin/env python3
"""
compare_trajectories.py — ARCore (bildfang) vs COLMAP trajectory comparison.

The scientific question: does the SfM-reconstructed camera path agree with
the VIO (ARCore) trajectory of the same walk?

Inputs
  --colmap-model  COLMAP sparse model dir (images.txt / cameras.txt)
  --frames-json   bildfang session video/frames.json
  --poses-json    bildfang session poses/poses.json
  --fps           extraction rate used for the frames (default 10)
  --out           output PNG (trajectory overlay)

Correspondence (time-based join, no ARCore information enters COLMAP):
  ffmpeg fps=N output frame k ~ stream time k/N s
    -> nearest encoded frame in frames.json by pts_ns
    -> its pose_index -> ARCore pose in poses.json

Alignment: Umeyama similarity (scale + rotation + translation),
ARCore (metric) -> COLMAP (arbitrary scale). The fitted scale factor is
itself a result: s ~= 1 means ARCore's metres are true metres.

Outputs (stdout):
  - joined count + median join error
  - scale / rotation residual / RMS (m, ARCore metric)
  - per-time-binned RMS (drift curve)
  - loop-closure distance of both trajectories
  - PNG: top-down (x, -z) + side (x, y), both trajectories, time-coloured

No dependencies beyond numpy + matplotlib.
"""

import argparse
import json
import math
import os

import numpy as np

try:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
except ImportError:
    plt = None


def load_poses(path):
    with open(path) as f:
        d = json.load(f)
    return d["poses"] if isinstance(d, dict) else d


def load_frames(path):
    with open(path) as f:
        d = json.load(f)
    return d["frames"]


def frame_sort_key(name):
    # f00001.jpg -> 1 ; non-matching names sort last
    stem = os.path.splitext(os.path.basename(name))[0]
    if stem[:1].isdigit():
        return int(stem)
    return 10**12


def load_colmap_images(model_dir):
    """name -> camera center (3,) from images.txt (tx ty tz is the world center)."""
    out = {}
    with open(os.path.join(model_dir, "images.txt")) as f:
        lines = [ln.rstrip("\n") for ln in f]
    i = 0
    while i < len(lines):
        ln = lines[i]
        if not ln or ln.startswith("#"):
            i += 1
            continue
        parts = ln.split()
        if len(parts) >= 10:
            # image_id qw qx qy qz camera_id name x y z
            name = parts[6]
            tx, ty, tz = map(float, parts[7:10])
            out[name] = np.array([tx, ty, tz])
        i += 2  # skip the 2D-observation line
    return out


def umeyama(src, dst):
    """Similarity (s, R, t) mapping src -> dst.  src/dst: (N,3)."""
    n = src.shape[0]
    mu_s = src.mean(0)
    mu_d = dst.mean(0)
    sc = src - mu_s
    dc = dst - mu_d
    var_s = (sc ** 2).sum() / n
    H = (dc.T @ sc) / n
    U, S, Vt = np.linalg.svd(H)
    d = np.sign(np.linalg.det(Vt.T @ U.T))
    D = np.eye(3)
    D[2, 2] = d
    R = Vt.T @ D @ U.T
    s = (1.0 / var_s) * (S - S.sum() * d / 3)
    t = mu_d - s * (R @ mu_s)
    return s, R, t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--colmap-model", required=True)
    ap.add_argument("--frames-json", required=True)
    ap.add_argument("--poses-json", required=True)
    ap.add_argument("--fps", type=float, default=10.0)
    ap.add_argument("--out", default="trajectory_comparison.png")
    args = ap.parse_args()

    frames = load_frames(args.frames_json)
    poses = load_poses(args.poses_json)
    colmap = load_colmap_images(args.colmap_model)

    pts = np.array([f["pts_ns"] for f in frames], dtype=np.float64) / 1e9  # s

    # join: output frame k (time k/fps) -> nearest encoded frame -> ARCore pose
    ar_pts, cm_pts, times = [], [], []
    join_err = []
    for k, name in enumerate(sorted(colmap, key=frame_sort_key)):
        t_img = k / args.fps
        j = int(np.argmin(np.abs(pts - t_img)))
        join_err.append(abs(pts[j] - t_img))
        p = poses[frames[j]["pose_index"]]
        ar_pts.append(p["translation"])
        cm_pts.append(colmap[name])
        times.append(pts[j])

    ar_pts = np.array(ar_pts)
    cm_pts = np.array(cm_pts)
    je = np.array(join_err)

    print(f"joined: {len(ar_pts)} camera poses (COLMAP recovered {len(colmap)} images)")
    print(f"time-join: median {np.median(je) * 1e3:.1f} ms, max {je.max() * 1e3:.1f} ms")

    # Umeyama: ARCore (metric, src) -> COLMAP (dst)
    s, R, t = umeyama(ar_pts, cm_pts)
    fitted = s * (ar_pts @ R.T) + t
    resid = np.linalg.norm(fitted - cm_pts, axis=1)
    rot_deg = math.degrees(math.acos(min(1.0, (np.trace(R) - 1) / 2)))
    rms = float(np.sqrt((resid ** 2).mean()))

    ar_extent = ar_pts.max(0) - ar_pts.min(0)
    cm_extent = cm_pts.max(0) - cm_pts.min(0)

    print(f"\nUmeyama similarity fit (ARCore -> COLMAP):")
    print(f"  scale s            : {s:.4f}  (COLMAP units per ARCore metre)")
    print(f"  rotation residual  : {rot_deg:.2f} deg")
    print(f"  RMS deviation      : {rms:.3f} COLMAP units = {rms / s:.3f} m (ARCore metric)")
    print(f"  median deviation   : {float(np.median(resid)) / s:.3f} m")
    print(f"  max deviation      : {resid.max() / s:.3f} m")
    print(f"\nextent: ARCore {np.round(ar_extent, 2)} m | COLMAP {np.round(cm_extent, 2)} units "
          f"(/s = {np.round(cm_extent / s, 2)} m)")

    lc_ar = float(np.linalg.norm(ar_pts[0] - ar_pts[-1]))
    lc_cm = float(np.linalg.norm(cm_pts[0] - cm_pts[-1]) / s)
    print(f"loop closure (first->last joined pose): ARCore {lc_ar:.3f} m | COLMAP {lc_cm:.3f} m")

    # drift over time: binned RMS of the deviation
    print(f"\ndrift (per-time-binned RMS, ARCore metric metres):")
    nb = 10
    bins = np.linspace(0, max(times) + 1e-6, nb + 1)
    for b in range(nb):
        m = (times >= bins[b]) & (times < bins[b + 1])
        if m.sum() > 1:
            r = resid[m] / s
            print(f"  t={bins[b]:6.1f}-{bins[b + 1]:6.1f}s  n={int(m.sum()):4d}  "
                  f"rms={float(np.sqrt((r ** 2).mean())):6.3f}  max={r.max():6.3f}")

    # plot: top-down (x, -z) + side (x, y), both trajectories, time-coloured
    if plt is not None:
        fig, axes = plt.subplots(1, 2, figsize=(13, 6))
        cm_fit = fitted
        for ax, (a, b, title) in zip(axes, [
            (0, 2, "top-down  (x, -z)"),
            (0, 1, "side      (x, y)"),
        ]):
            sc1 = ax.scatter(ar_pts[:, a], ar_pts[:, b], c=times, cmap="viridis",
                             s=4, alpha=0.6, label="ARCore (VIO)")
            ax.plot(cm_pts[:, a], cm_pts[:, b], "-", lw=0.6, color="crimson",
                    alpha=0.5, label="COLMAP (SfM, raw)")
            ax.plot(cm_fit[:, a], cm_fit[:, b], "o", ms=3, color="black",
                    alpha=0.7, label="COLMAP aligned (Umeyama)")
            ax.set_xlabel(f"x [{title.split('(')[1].rstrip(')')}-axis]")
            ax.set_title(f"{title}\ns={s:.3f}, RMS={rms / s:.3f} m")
            ax.set_aspect("equal", adjustable="datalim")
            ax.legend(loc="best", fontsize=8)
        fig.colorbar(sc1, ax=axes, label="time [s]")
        fig.suptitle("bildfang washroom 2026-09-01: ARCore vs COLMAP camera trajectory")
        fig.tight_layout()
        fig.savefig(args.out, dpi=120)
        print(f"\nplot: {args.out}")
    else:
        print("\nplot skipped (matplotlib not available)")


if __name__ == "__main__":
    main()
