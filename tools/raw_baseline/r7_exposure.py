"""Private round-7 metering study: learn an exposure rule from the user's own edited pairs.

For each authorized RAW/edited pair, find the foundation EV whose rendered centre
top-percentile luminance matches the edit's, then fit EV against input metrics with
cross-validation. Diagnostic only; nothing is adopted as a default.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import sys

import numpy as np
import rawpy

import baseline as b
import r6_diagnose as r

METRICS = ("c97", "c90", "mean", "p50")


def input_metrics(linear: np.ndarray) -> dict[str, float]:
    h, w = linear.shape[:2]
    y = linear @ r.LUMA
    centre = y[h // 4:3 * h // 4, w // 4:3 * w // 4]
    return {"c97": float(np.percentile(centre, 97)), "c90": float(np.percentile(centre, 90)),
            "mean": float(y.mean()), "p50": float(np.median(y))}


def output_c97(linear: np.ndarray) -> float:
    h, w = linear.shape[:2]
    return float(np.percentile(linear[h // 4:3 * h // 4, w // 4:3 * w // 4] @ r.LUMA, 97))


def decode_small(path: Path) -> np.ndarray:
    """Half-size decode with baseline.decode()'s preprocessing, so learned EVs share its exposure scale."""
    with rawpy.imread(str(path)) as raw:
        rgb = raw.postprocess(half_size=True, use_camera_wb=True, use_auto_wb=False, output_color=rawpy.ColorSpace.sRGB,
                              output_bps=16, gamma=(1, 1), no_auto_bright=True, no_auto_scale=False, bright=1.0,
                              exp_shift=1.0, exp_preserve_highlights=0.0, adjust_maximum_thr=0.0,
                              highlight_mode=rawpy.HighlightMode.Clip,
                              fbdd_noise_reduction=rawpy.FBDDNoiseReductionMode.Off, noise_thr=None,
                              median_filter_passes=0)
    linear = r.crop_default(rgb.astype(np.float32) / 65535)
    size = r.fit_size(linear.shape[1], linear.shape[0])
    scale = 600 / max(size)
    return r.resize_linear(linear, (round(size[0] * scale), round(size[1] * scale)))


def solve_ev(linear: np.ndarray, target: float, look: dict) -> float:
    """Bisection on base EV so the foundation render's centre c97 equals the edit's."""
    scene = {"skin": [], "base_ev": 0.}
    low, high = -3., 6.
    for _ in range(22):
        mid = (low + high) / 2
        value = output_c97(r.render_foundation(linear, dict(scene, base_ev=mid), look))
        low, high = (mid, high) if value < target else (low, mid)
    return (low + high) / 2


def crossval(rows: list[dict], metric: str, slope: float | None, folds: int = 5) -> dict:
    x = np.array([math.log2(max(row["inputs"][metric], 1e-6)) for row in rows])
    y = np.array([row["ev"] for row in rows])
    idx = np.arange(len(rows))
    errors = np.empty(len(rows))
    for fold in range(folds):
        test = idx % folds == fold
        if slope is None:
            k, a = np.polyfit(x[~test], y[~test], 1)
        else:
            k, a = slope, float(np.mean(y[~test] - slope * x[~test]))
        errors[test] = y[test] - (a + k * x[test])
    k_all, a_all = (np.polyfit(x, y, 1) if slope is None else (slope, float(np.mean(y - slope * x))))
    return {"metric": metric, "slope": float(k_all), "intercept": float(a_all), "mae": float(np.abs(errors).mean()),
            "p90_abs": float(np.percentile(np.abs(errors), 90)), "within_0.5": float((np.abs(errors) <= .5).mean())}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adopted", type=Path, required=True, help="authorized folder with original/ and edited/")
    parser.add_argument("--r5", type=Path, required=True, help="round-5 folder (reproducer/settings.json)")
    parser.add_argument("--output", type=Path, required=True, help="new JSON path outside inputs")
    args = parser.parse_args(argv)
    adopted = b.root_path(args.adopted)
    output = r.resolve_output(args.output, [adopted])  # resolves '..' and links before the overlap check
    look = dict(json.loads((b.root_path(args.r5) / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    # Pair by file stem after an optional "<number>-" ordering prefix.
    stem = lambda path: re.sub(r"^\d+-", "", path.stem).casefold()
    originals = {stem(p): p for p in (adopted / "original").iterdir() if p.suffix.lower() == ".dng"}
    rows = []
    for edited in sorted((adopted / "edited").glob("*.jpg")):
        if stem(edited) not in originals:
            continue
        linear = decode_small(originals[stem(edited)])
        target = r.load_jpeg(edited)
        target = r.resize_linear(target, (linear.shape[1], linear.shape[0])) if target.shape != linear.shape else target
        rows.append({"id": len(rows), "inputs": input_metrics(linear), "target_c97": output_c97(target),
                     "ev": solve_ev(linear, output_c97(target), look)})
        print(len(rows), end=" ", flush=True)
    fits = [crossval(rows, m, s) for m in METRICS for s in (None, -.4, -1.)]
    output.write_text(json.dumps({"rows": rows, "fits": fits}, indent=1), encoding="utf-8")
    best = min(fits, key=lambda f: f["mae"])
    print(json.dumps({"status": "ok", "pairs": len(rows), "best": best}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
