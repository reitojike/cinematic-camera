"""Private round-8 metering study: does a skin-colour face proxy predict the user's face exposure?

For each authorized pair whose edit keeps the full frame, find the base EV at which the
round-7 look renders the face proxy (skin-like hues, centre-weighted) as bright as the
user's edit does. Then compare how well input metrics predict that EV (cross-validated):
the centre top-3% luminance used so far versus the skin-proxy luminance itself.
No new dependency and no face detector; diagnostic only, nothing becomes a default.
"""
from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path
import sys

import numpy as np

import baseline as b
import r6_diagnose as r
import r7_exposure as e
import r7_final as fin
import r7_look as k
import r7b_skin as sk
import r7c_punch as cp
import r8_generalize as g

r.WORK_LONG = 600  # imported r7 modules raise it; metering only needs a small image
SIZE = 480
MIN_MASS = .004  # minimum weighted skin fraction for a usable proxy


def skin_proxy_weight(linear: np.ndarray) -> np.ndarray:
    """Skin-like hue, moderate chroma relative to lightness, centre/upper-weighted; scale-invariant."""
    lab = r.oklab(np.maximum(linear, 0))
    lightness = np.maximum(lab[..., 0], 1e-4)
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    ratio = chroma / lightness
    band = np.clip(1 - np.abs((hue - 50 + 180) % 360 - 180) / 40, 0, 1)
    gate = np.clip((ratio - .04) / .04, 0, 1) * np.clip(1 - (ratio - .35) / .1, 0, 1)
    h, w = lightness.shape
    y = (np.arange(h) + .5)[:, None] / h
    x = (np.arange(w) + .5)[None, :] / w
    prior = np.exp(-(((x - .5) / .3) ** 2 + ((y - .4) / .3) ** 2) / 2)
    return band * gate * prior * (lightness > .05)


def proxy_log_y(linear: np.ndarray, weight: np.ndarray) -> float:
    y = np.maximum(linear @ r.LUMA, 1e-6)
    return float((np.log2(y) * weight).sum() / weight.sum())


def edit_keeps_full_frame(path: Path) -> bool:
    """True only when the edit's Lightroom XMP records no crop (equal aspect ratios are not enough)."""
    data = path.read_bytes()
    xmp = data[data.find(b"<x:xmpmeta"):data.find(b"</x:xmpmeta>")]
    if not xmp:
        return False
    has_crop = re.search(rb'crs:HasCrop="(True|False)"', xmp)
    return bool(has_crop) and has_crop.group(1) == b"False"


def solve_face_ev(decoded: np.ndarray, weight: np.ndarray, target: float, look: dict) -> float:
    choice = fin.chosen("_default")
    low, high = -4., 7.
    for _ in range(20):
        mid = (low + high) / 2
        values = fin.render(decoded, {"skin": [], "base_ev": mid}, look, choice)
        # Measure the image the look actually saves (luminance-preserving gamut mapping).
        value = proxy_log_y(k.gamut_compress_luma(values, r.LUMA), weight)
        low, high = (mid, high) if value < target else (low, mid)
    return (low + high) / 2


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adopted", type=Path, required=True)
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="new JSON path outside inputs")
    args = parser.parse_args(argv)
    adopted, r5 = b.root_path(args.adopted), b.root_path(args.r5)
    output = r.resolve_output(args.output, [adopted, r5])
    look = dict(json.loads((r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    originals = {g.stem(p): p for p in (adopted / "original").iterdir() if p.suffix.lower() == ".dng"}
    rows = []
    for edited in sorted((adopted / "edited").glob("*.jpg")):
        if g.stem(edited) not in originals:
            continue
        target_image = r.load_jpeg(edited)
        reference = e.decode_small(originals[g.stem(edited)])
        if not edit_keeps_full_frame(edited) or                 abs(target_image.shape[1] / target_image.shape[0] - reference.shape[1] / reference.shape[0]) > .01:
            continue  # cropped (or unverifiable) edit: pixels do not align
        size = (round(reference.shape[1] * SIZE / max(reference.shape[:2])), round(reference.shape[0] * SIZE / max(reference.shape[:2])))
        reference = r.resize_linear(reference, size)
        target_image = r.resize_linear(target_image, size)
        weight = skin_proxy_weight(reference)
        mass = float(weight.mean())
        dng = originals[g.stem(edited)]
        mult = r.shifted_multipliers(dng, cp.WB_LEVELS[fin.chosen("_default")["wb"]], 0.)[0]
        decoded, _ = r.decode_dng_profile(dng, hue_sat=True, multipliers=mult, half_size=True)
        decoded = r.resize_linear(decoded.astype(np.float32), size)
        decoded *= float(np.median(reference @ r.LUMA) / max(np.median(decoded @ r.LUMA), 1e-6))
        row = {"id": len(rows), "date": g.capture_date(edited), "mass": mass,
               "inputs": dict(e.input_metrics(reference), skin=2 ** proxy_log_y(reference, weight) if mass >= MIN_MASS else None)}
        if mass >= MIN_MASS:
            row["ev"] = solve_face_ev(decoded, weight, proxy_log_y(target_image, weight), look)
        rows.append(row)
        print(len(rows), end=" ", flush=True)
    usable = [row for row in rows if "ev" in row]
    fits = [e.crossval(usable, metric, slope) for metric in ("c97", "skin") for slope in (None, -.4, -1.)]
    output.write_text(json.dumps({"rows": rows, "fits": fits, "usable": len(usable), "min_mass": MIN_MASS}, indent=1),
                      encoding="utf-8")
    print(json.dumps({"status": "ok", "pairs": len(rows), "usable": len(usable),
                      "best": min(fits, key=lambda f: f["mae"])}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
