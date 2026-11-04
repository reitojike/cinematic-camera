"""Synthetic DNG WB metadata only: PC round-9 W1 coefficients, never photo input."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import r6_diagnose as r
import r9_pixel as px

OUTPUT = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/w1_trial_v1.json"
CM1 = [.72, .13, .04, .11, .78, .08, .02, .16, .81]
CM2 = [.65, .17, .06, .13, .74, .11, .03, .13, .79]


def rational_values(values):
    # The fixture writer encodes these exact millionth-denominator rationals.
    return [round(float(v) * 1_000_000) / 1_000_000 for v in values]


def cases():
    result = []
    for index, (temp, tint) in enumerate((
        (2400., -15.), (2800., 0.), (3500., 15.), (4500., -25.),
        (5000., 0.), (6500., 25.), (9000., -15.), (12000., 0.),
    )):
        for source in ("xy", "neutral"):
            tags = {"cm1": CM1, "cm2": CM2, "illum1": [17], "illum2": [21]}
            xy = rational_values(r.temp_tint_to_xy(temp, tint))
            if source == "xy":
                tags["white_xy"] = xy
            else:
                weight = r.profile_weight(r.xy_to_cct(np.array(xy)), tags)
                cm = weight * np.array(CM1).reshape(3, 3) + (1 - weight) * np.array(CM2).reshape(3, 3)
                neutral = cm @ r.xy_to_xyz(np.array(xy))
                tags["neutral"] = rational_values(neutral / neutral.max())
            result.append(make_case(f"{source}-{index}", tags))
    tags = {"cm1": CM2, "cm2": CM1, "illum1": [21], "illum2": [17], "white_xy": [.3127, .3290]}
    result.append(make_case("reversed", tags))
    for illuminant in (17, 20, 21, 22, 23):
        # A single matrix is equivalent to both calibration matrices being the same.
        tags = {"cm1": CM1, "cm2": CM1, "illum1": [illuminant], "illum2": [illuminant], "white_xy": [.3127, .3290]}
        result.append(make_case(f"single-{illuminant}", tags, single=True))
    return result


def make_case(name, tags, single=False):
    white = px.as_shot_white(tags)
    temp, tint = r.xy_to_temp_tint(white)
    shifted_temp = 1e6 / (1e6 / temp + 22.)
    shifted = r.temp_tint_to_xy(shifted_temp, tint)
    weight = r.profile_weight(r.xy_to_cct(shifted), tags)
    cm = weight * np.array(tags["cm1"]).reshape(3, 3) + (1 - weight) * np.array(tags["cm2"]).reshape(3, 3)
    neutral = cm @ r.xy_to_xyz(shifted)
    neutral /= neutral.max()
    multipliers = (1 / neutral) / (1 / neutral)[1]
    metadata = dict(tags)
    if single:
        metadata.pop("cm2")
        metadata.pop("illum2")
    # Stable text across NumPy backends; rounding is far below the Float32 regression boundary.
    number = lambda value: float(f"{float(value):.12g}")
    return {"id": name, "metadata": metadata, "requested_wb": [number(v) for v in multipliers],
            "shifted_xy": [number(v) for v in shifted], "source_temperature": number(temp), "source_tint": number(tint),
            "target_temperature": number(shifted_temp)}


def contents():
    return json.dumps({"version": 1, "oracle": "r9_pixel W1 / r6 Planck search; synthetic metadata only",
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
        raise SystemExit("W1 synthetic fixture differs from PC oracle")


if __name__ == "__main__":
    main()
