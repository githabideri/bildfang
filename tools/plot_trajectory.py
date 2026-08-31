#!/usr/bin/env python3
"""
plot_trajectory.py — first validation tool for Bildfang captures.

Input:  poses/poses.json  (bildfang-capture/v1-poses)
Output: top-down trajectory plot (PNG) + console diagnostics

The success criterion it answers is deliberately simple:

    "Does the phone itself believe it walked a correct path?"

    - top-down path should match what you remember walking
    - forward arrows should point where you were looking
    - if you returned to your start position, loop-closure distance
      should be small (this is ARCore's drift metric, not a
      reconstruction metric)

If this plot looks physically wrong, the capture is insufficient —
do not feed it to reconstruction.

Usage:
    python3 plot_trajectory.py poses.json --out trajectory.png
    python3 plot_trajectory.py poses.json --segment 0 --out seg0.png

Dependencies: numpy, matplotlib (that's it).
"""

import argparse
import json
import math
import sys

import numpy as np


def load_poses(path):
    with open(path) as f:
        data = json.load(f)
    poses = data["poses"] if isinstance(data, dict) else data
    if not poses:
        sys.exit("no poses found")
    return poses


def quat_to_matrix(q):
    x, y, z, w = q
    n = math.sqrt(x * x + y * y + z * z + w * w)
    if n == 0:
        return np.eye(3)
    x, y, z, w = x / n, y / n, z / n, w / n
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - w * z),     2 * (x * z + w * y)],
        [2 * (x * y + w * z),     1 - 2 * (x * x + z * z), 2 * (y * z - w * x)],
        [2 * (x * z - w * y),     2 * (y * z + w * x),     1 - 2 * (x * x + y * y)],
    ])


def forward_vec(q):
    """Camera forward in world frame = R · (0,0,-1)  (ARCore: camera looks -Z)."""
    r = quat_to_matrix(q) @ np.array([0.0, 0.0, -1.0])
    return r / (np.linalg.norm(r) or 1.0)


def summarize(poses, segid):
    seg = [p for p in poses if p.get("segment", 0) == segid]
    ts = [p["timestamp_ns"] for p in seg]
    dur_s = (ts[-1] - ts[0]) / 1e9
    n_track = sum(1 for p in seg if p["tracking_state"] == "TRACKING")

    path = 0.0
    for a, b in zip(seg[:-1], seg[1:]):
        if b.get("segment", 0) != segid:
            break
        dx = b["translation"]["x"] - a["translation"]["x"]
        dy = b["translation"]["y"] - a["translation"]["y"]
        dz = b["translation"]["z"] - a["translation"]["z"]
        path += math.sqrt(dx * dx + dy * dy + dz * dz)

    first, last = seg[0], seg[-1]
    closure = math.dist(
        (first["translation"]["x"], first["translation"]["y"], first["translation"]["z"]),
        (last["translation"]["x"],  last["translation"]["y"],  last["translation"]["z"]),
    )

    # drift estimate: best closure of the final 10 s against the first 10 s
    head = [p for p in seg if p["timestamp_ns"] <= ts[0] + 10e9]
    tail = [p for p in seg if p["timestamp_ns"] >= ts[-1] - 10e9]
    best = min(
        (math.dist(
            (h["translation"]["x"], h["translation"]["y"], h["translation"]["z"]),
            (t["translation"]["x"], t["translation"]["y"], t["translation"]["z"]),
        ) for h in head for t in tail),
        default=closure,
    )

    print(f"segment {segid}: {len(seg)} frames  ({n_track} TRACKING)")
    print(f"  duration        : {dur_s:7.1f} s")
    print(f"  path length     : {path:7.2f} m")
    print(f"  start→end dist  : {closure:7.3f} m")
    print(f"  loop closure*   : {best:7.3f} m   (*best match, first 10 s vs last 10 s)")
    print("  * if you walked a closed loop, loop closure ≈ ARCore drift")
    print("    if you did NOT, ignore it — it's just start/end separation")


def plot(poses, segid, out):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    seg = [p for p in poses if p.get("segment", 0) == segid]
    xs = [p["translation"]["x"] for p in seg]
    zs = [p["translation"]["z"] for p in seg]
    base = plt.rcParams["axes.prop_cycle"].by_key()["color"][0]
    colors = {"TRACKING": base, "PAUSED": "#f0a0a0", "STOPPED": "#888888"}

    fig, ax = plt.subplots(figsize=(8, 8))
    ax.scatter(
        xs, [-z for z in zs],
        c=[colors.get(p["tracking_state"], colors["STOPPED"]) for p in seg],
        s=5, alpha=0.5,
    )
    # forward-direction arrows every Nth pose
    n = max(1, len(seg) // 24)
    for i in range(0, len(seg), n):
        p = seg[i]
        fx, fy, fz = forward_vec([
            p["rotation_quaternion"][k] for k in ("x", "y", "z", "w")
        ])
        ax.quiver(
            p["translation"]["x"], -p["translation"]["z"], fx, -fz,
            angles="xy", scale=1.0 / max(0.02, abs(fx) + abs(fz)),
            scale_units="xy", color="#3060c0", alpha=0.6, width=0.004,
        )

    if xs and zs:
        ax.plot([xs[0]], [-zs[0]], marker="s", color="#3060c0",
                label="start", zorder=5, markersize=8)
        ax.plot([xs[-1]], [-zs[-1]], marker="*", color="#c03030",
                label="end", zorder=5, markersize=12)

    ax.set_xlabel("X (m) — right of session start")
    ax.set_ylabel("-Z (m) — toward the scene at session start")
    ax.set_title(f"Bildfang trajectory — segment {segid} (top-down)")
    ax.set_aspect("equal")
    ax.grid(True, alpha=0.3)
    ax.legend(loc="upper right")
    fig.tight_layout()
    fig.savefig(out, dpi=150)
    print(f"plot written: {out}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    ap.add_argument("poses", help="path to poses.json")
    ap.add_argument("--segment", type=int, default=0)
    ap.add_argument("--out", default="trajectory.png")
    ap.add_argument("--no-plot", action="store_true")
    args = ap.parse_args()

    poses = load_poses(args.poses)
    summarize(poses, args.segment)
    if not args.no_plot:
        plot(poses, args.segment, args.out)


if __name__ == "__main__":
    main()
