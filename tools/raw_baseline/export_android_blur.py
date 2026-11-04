"""Synthetic PC blur conformance only; never opens photographs or per-photo settings.

Exports the intermediate BOX image as well as r10_iconic.blur's restored plane.
Run --write deliberately, or --check (default) to verify committed numerical evidence.
"""
from __future__ import annotations

import argparse
import gzip
from pathlib import Path

import numpy as np
from PIL import Image

import r10_iconic as ic

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "app/src/test/resources/stage_clear_blur_v1.csv.gz"


def plane(width: int, height: int) -> np.ndarray:
    yy, xx = np.mgrid[:height, :width].astype(np.float32)
    # A deterministic, non-separable surface with negatives, >1, and high-frequency edges.
    return (xx * .035 - yy * .025 + ((xx * 3 + yy * 7) % 11) * .13 - .4).astype(np.float32)


def cases():
    # Every radius used by the current glow, mist, Orton and skin-weight stages.
    for index, radius in enumerate((.006, .01, .012, .02, .025, .03, .06, .07)):
        # longSide > 1/.006: even the smallest current radius actually resamples.
        width, height = (191, 7) if index % 2 == 0 else (7, 191)
        yield f"look-radius-{radius:g}", plane(width, height), radius
    # Both half-even directions for the short side: 2.5->2 and 3.5->4.
    yield "round-down-even", plane(10, 5), .2
    yield "round-up-even", plane(10, 7), .2
    # Select radius so cell=2 exactly, making the half-way dimensions explicit.
    yield "half-even-2", plane(5, 4), .4
    yield "half-even-4", plane(7, 4), 2 / 7
    for width, height in ((1, 1), (1, 17), (17, 1), (3, 31), (31, 3), (7, 19), (19, 7)):
        yield f"shape-{width}x{height}", plane(width, height), .23
    yield "zero-radius", plane(7, 5), 0.
    yield "minimum-2", plane(9, 13), 4.
    yield "constant", np.full((11, 13), -.3, dtype=np.float32), .3
    for name, (x, y) in {"corner": (0, 0), "edge": (0, 8), "centre": (10, 8)}.items():
        impulse = np.zeros((17, 21), dtype=np.float32)
        impulse[y, x] = 4
        yield f"impulse-{name}", impulse, .23


def fixture() -> str:
    lines = ["# synthetic r10_iconic.blur only; linear float32 plane; no photo data",
             "# CASE,name,width,height,radius,smallWidth,smallHeight; PIX,input,restored; LOW,box"]
    for name, values, radius in cases():
        height, width = values.shape
        cell = max(radius * max(width, height), 1.)
        small = (max(2, round(width / cell)), max(2, round(height / cell)))
        reduced = np.asarray(Image.fromarray(values, "F").resize(small, Image.Resampling.BOX))
        restored = ic.blur(values, radius)
        # This assertion ties the staged fixture to the unmodified PC function.
        np.testing.assert_array_equal(restored, np.asarray(
            Image.fromarray(reduced, "F").resize((width, height), Image.Resampling.BILINEAR)))
        lines.append(f"CASE,{name},{width},{height},{radius:.17g},{small[0]},{small[1]}")
        lines += [f"PIX,{float(a):.10g},{float(b):.10g}"
                  for a, b in zip(values.flat, restored.flat)]
        lines += [f"LOW,{float(v):.10g}" for v in reduced.flat]
    return "\n".join(lines) + "\n"


def check_exports() -> None:
    expected, actual = fixture().splitlines(), gzip.decompress(FIXTURE.read_bytes()).decode("utf-8").splitlines()
    if len(expected) != len(actual):
        raise AssertionError("Blur fixture shape changed")
    for line, (want, got) in enumerate(zip(expected, actual), 1):
        a, b = want.split(","), got.split(",")
        if a[0] in ("PIX", "LOW"):
            if a[0] != b[0] or len(a) != len(b):
                raise AssertionError(f"Blur fixture layout differs at line {line}")
            np.testing.assert_allclose(np.array(b[1:], dtype=float), np.array(a[1:], dtype=float),
                                       atol=2e-7, rtol=0, err_msg=f"blur fixture line {line}")
        elif want != got:
            raise AssertionError(f"Blur fixture metadata differs at line {line}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.write:
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        FIXTURE.write_bytes(gzip.compress(fixture().encode("utf-8"), mtime=0))
    else:
        check_exports()
    print("Wrote synthetic blur fixture" if args.write else "Blur fixture matches PC")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
