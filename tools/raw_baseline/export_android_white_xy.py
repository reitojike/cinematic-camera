"""Independent synthetic raw-tag WhiteXY WB oracle; no native output or photos.

Only the numerical Robertson table is transcribed from Colour v0.4.6,
commit 0c6f868c4b5c5ae861f868ba8015be3abf0f92f0 (Copyright 2013 Colour
Developers, BSD-3-Clause). See third_party/dng_temperature/README.md and the
complete license in app/src/main/assets/third_party/dng_temperature/LICENSE.
The NumPy distance/projection calculation below is this project's reference,
not a copy of Adobe SDK or Colour conversion functions. No SDK CM normalization.
"""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

import numpy as np

OUTPUT = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/as_shot_white_xy_v1.json"
# Declared before any device comparison; Float32 requested-WB coefficients only.
WB_ATOL, WB_RTOL = 2e-5, 2e-6
TABLE = np.array([
    [0, .18006, .26352, -.24341], [10, .18066, .26589, -.25479],
    [20, .18133, .26846, -.26876], [30, .18208, .27119, -.28539],
    [40, .18293, .27407, -.30470], [50, .18388, .27709, -.32675],
    [60, .18494, .28021, -.35156], [70, .18611, .28342, -.37915],
    [80, .18740, .28668, -.40955], [90, .18880, .28997, -.44278],
    [100, .19032, .29326, -.47888], [125, .19462, .30141, -.58204],
    [150, .19962, .30921, -.70471], [175, .20525, .31647, -.84901],
    [200, .21142, .32312, -1.0182], [225, .21807, .32909, -1.2168],
    [250, .22511, .33439, -1.4512], [275, .23247, .33904, -1.7298],
    [300, .24010, .34308, -2.0637], [325, .24792, .34655, -2.4681],
    [350, .25591, .34951, -2.9641], [375, .26400, .35200, -3.5814],
    [400, .27218, .35407, -4.3633], [425, .28039, .35577, -5.3762],
    [450, .28863, .35714, -6.7262], [475, .29685, .35823, -8.5955],
    [500, .30505, .35907, -11.324], [525, .31320, .35968, -15.628],
    [550, .32129, .36011, -23.325], [575, .32931, .36038, -40.770],
    [600, .33724, .36051, -116.45],
], dtype=np.float64)
TEMPERATURES = {17: 2850., 20: 5500., 21: 6500., 22: 7500., 23: 5000.}
BASE = {
    "cm1": [.78, -.12, -.06, -.2, 1.1, .08, .01, -.05, .82],
    "cm2": [.9, -.18, -.08, -.15, 1.05, .05, .02, -.08, .95],
    "cc1": [1.02, .01, 0., -.01, .99, .02, 0., .01, 1.03],
    "cc2": [.98, .02, .01, .01, 1.01, -.01, .02, 0., 1.04],
    "ab": [1.1, .95, 1.05], "illum1": [17], "illum2": [21],
}


def correlated_temperature(xy):
    """Bracket xy by signed normal distances to tabulated CIE-1960 isotherms."""
    x, y = np.asarray(xy, dtype=np.float64)
    if not np.isfinite([x, y]).all() or x <= 0 or y <= 0 or x + y >= 1:
        raise ValueError("invalid chromaticity")
    uv = np.array([4*x, 6*y]) / (-2*x + 12*y + 3)
    normals = np.column_stack((-TABLE[:, 3], np.ones(len(TABLE))))
    normals /= np.linalg.norm(normals, axis=1)[:, None]
    distances = np.einsum("ij,ij->i", uv - TABLE[:, 1:3], normals)
    crossings = np.flatnonzero(distances[1:] <= 0)
    if not crossings.size:
        raise ValueError("outside isotherm table")
    upper = int(crossings[0]) + 1
    lower = upper - 1
    if lower == 0 or distances[lower] <= 0:
        raise ValueError("unbracketed white")
    fraction = distances[lower] / (distances[lower] - distances[upper])
    mired = TABLE[lower, 0] + fraction * (TABLE[upper, 0] - TABLE[lower, 0])
    result = float(1e6 / mired)
    if not 1700 <= result <= 15000:
        raise ValueError("outside supported temperature domain")
    return result


def raw_tag_result(metadata):
    """Float64 NumPy composites over exact millionth-denominator raw tag values."""
    xy = np.asarray(metadata["white_xy"], dtype=np.float64)
    temperature = correlated_temperature(xy)
    endpoints = np.array([TEMPERATURES[metadata[k][0]] for k in ("illum1", "illum2")])
    first_weight = float(np.clip((1/temperature - 1/endpoints[1]) /
                                 (1/endpoints[0] - 1/endpoints[1]), 0, 1))
    analog = np.diag(np.asarray(metadata["ab"], dtype=np.float64))
    composites = [analog @ np.array(metadata[f"cc{i}"]).reshape(3, 3) @
                  np.array(metadata[f"cm{i}"]).reshape(3, 3) for i in (1, 2)]
    matrix = first_weight * composites[0] + (1-first_weight) * composites[1]
    xyz = np.array([xy[0], xy[1], 1-xy.sum()]) / xy[1]
    neutral = matrix @ xyz
    if not np.isfinite(neutral).all() or np.any(neutral <= 0):
        raise ValueError("nonpositive camera neutral")
    return temperature, first_weight, neutral, (neutral[1]/neutral).astype(np.float32)


def make_case(name, xy, changes=None):
    metadata = copy.deepcopy(BASE)
    metadata["white_xy"] = list(xy)
    if changes:
        metadata.update(changes)
    # Match the Android fixture writer, including the metadata encoding boundary.
    metadata = {key: [round(float(v)*1_000_000)/1_000_000 for v in values]
                for key, values in metadata.items()}
    for key in ("illum1", "illum2"):
        metadata[key] = [int(metadata[key][0])]
    temperature, weight, neutral, wb = raw_tag_result(metadata)
    number = lambda v: float(f"{float(v):.12g}")
    return {"id": name, "metadata": metadata, "signature": "oracle-calibration",
            "temperature": number(temperature), "first_weight": number(weight),
            "neutral": [number(v) for v in neutral],
            "requested_wb": [float(v) for v in wb]}


def cases():
    result = [make_case(name, xy) for name, xy in (
        ("d50", (.3457, .3585)), ("interior", (.4, .4)),
        ("d65", (.3127, .3290)), ("standard-a", (.44757, .40745)),
        ("near-325-mired", (.42, .4)),
        ("low-end-clamp", (.48, .4)),
    )]
    result.append(make_case("reversed-interior", (.4, .4), {
        "cm1": BASE["cm2"], "cm2": BASE["cm1"],
        "cc1": BASE["cc2"], "cc2": BASE["cc1"], "illum1": [21], "illum2": [17]}))
    result.append(make_case("reversed-d65", (.3127, .3290), {
        "cm1": BASE["cm2"], "cm2": BASE["cm1"],
        "cc1": BASE["cc2"], "cc2": BASE["cc1"], "illum1": [21], "illum2": [17]}))
    for scale in (.5, 1.5):
        result.append(make_case(f"raw-cm1-scale-{scale}", (.4, .4), {
            "cm1": [v*scale for v in BASE["cm1"]]}))
    result.append(make_case("common-cm-scale-2", (.4, .4), {
        key: [2*v for v in BASE[key]] for key in ("cm1", "cm2")}))
    return result


def contents():
    return json.dumps({"version": 1,
        "oracle": "independent NumPy raw-tag composites + Colour corrected Robertson numerical table",
        "wb_atol": WB_ATOL, "wb_rtol": WB_RTOL, "rational_denominator": 1000000,
        "contract": "No W1 shift, SDK CM normalization/rounding, native outputs, or photographs",
        "cases": cases()}, ensure_ascii=False, indent=1, allow_nan=False) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    expected = contents()
    if args.write:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(expected, encoding="utf-8", newline="\n")
    elif OUTPUT.read_text(encoding="utf-8") != expected:
        raise SystemExit("WhiteXY fixture differs from independent PC oracle")


if __name__ == "__main__":
    main()
