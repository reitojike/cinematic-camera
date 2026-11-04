"""All-pixel synthetic EV/ROI reference from existing PC functions; no private inputs."""
from __future__ import annotations

import argparse
import gzip
from pathlib import Path

import numpy as np

import preview_inputs as oracle

FIXTURE = Path(__file__).resolve().parents[2] / "app/src/test/resources/r7_preview_inputs_v1.csv.gz"
# Chosen before PC/JVM comparisons: pure scalar EV and float32 mask migration budgets.
EV_ATOL = 2e-6
ROI_ATOL = 2e-6


def scenes():
    rng = np.random.default_rng(113)
    specs = [("odd-interpolation", 7, 5, [[.4, .6, .3, .5]]),
             ("outside-bright", 9, 7, []), ("black", 8, 6, []), ("negative", 8, 6, []),
             ("hdr-clamp", 9, 7, []), ("dark-clamp", 8, 6, []),
             ("percentile-interpolation", 4, 4, [[0., 1., 1., 1.]]),
             ("one-sample", 2, 2, [[.5, .5, .8, .8]]),
             ("soft-overlap", 31, 23, [[.35, .6, .25, .4], [.55, .4, .3, .3]]),
             ("six-ellipses", 37, 29, [[x, y, .24, .3] for y in (.25, .75) for x in (.2, .5, .8)]),
             ("narrow", 2, 17, [[.5, .7, .4, .25]]), ("wide", 19, 2, [[.3, .5, .25, .4]]),
             ("long-edge-limit", 1024, 3, []), ("signed-rgb", 11, 9, [[.5, .5, .4, .4]])]
    for name, w, h, ellipses in specs:
        image = rng.uniform(.001, .8, (h, w, 3)).astype(np.float32)
        if name == "outside-bright":
            image[:] = 100
            image[h // 4:3 * h // 4, w // 4:3 * w // 4] = .2
        elif name in ("black", "negative", "hdr-clamp", "dark-clamp"):
            image[:] = {"black": 0., "negative": -.1, "hdr-clamp": 100., "dark-clamp": 1e-8}[name]
        elif name == "percentile-interpolation":
            image[:] = 4.
            image[1:3, 1:3] = np.array([[.1, .2], [.3, .9]], dtype=np.float32)[..., None]
        elif name == "one-sample":
            image[0, 0] = .18
        elif name == "signed-rgb":
            image[:] = [1.3, -.04, .15]
        # Match the public Kotlin Float ellipse coordinate boundary explicitly.
        ellipses = [[float(np.float32(v)) for v in ellipse] for ellipse in ellipses]
        yield name, image, ellipses


def fixture():
    lines = ["# synthetic plain as-shot inputs before W1; no real image or detected-face data",
             "# CASE,name,width,height,externalEV,p97,count,ellipses; ELLIPSE,cx,cy,rx,ry; PIX,R,G,B,foundationROI"]
    for name, image, ellipses in scenes():
        ev, p97, count, roi = oracle.prepare(image, ellipses)
        h, w = image.shape[:2]
        lines.append(f"CASE,{name},{w},{h},{ev:.17g},{p97:.17g},{count},{len(ellipses)}")
        lines.extend("ELLIPSE," + ",".join(f"{v:.10g}" for v in ellipse) for ellipse in ellipses)
        rows = np.concatenate((image, roi[..., None]), axis=-1).reshape(-1, 4)
        lines.extend("PIX," + ",".join(f"{float(v):.10g}" for v in row) for row in rows)
    return "\n".join(lines) + "\n"


def check_exports():
    actual = gzip.decompress(FIXTURE.read_bytes()).decode().splitlines()
    expected = fixture().splitlines()
    if len(actual) != len(expected):
        raise AssertionError("Preview-input fixture shape changed")
    for line, (got, want) in enumerate(zip(actual, expected), 1):
        a, b = got.split(","), want.split(",")
        if b[0] == "PIX":
            if a[0] != "PIX" or len(a) != 5:
                raise AssertionError(f"Preview-input row layout differs at {line}")
            np.testing.assert_allclose(np.array(a[1:4], dtype=float), np.array(b[1:4], dtype=float),
                                       atol=0, rtol=0, equal_nan=False)
            np.testing.assert_allclose(float(a[4]), float(b[4]), atol=ROI_ATOL, rtol=0, equal_nan=False)
        elif b[0] == "CASE":
            if a[:4] != b[:4] or a[6:] != b[6:]:
                raise AssertionError(f"Preview-input metadata differs at {line}")
            np.testing.assert_allclose(float(a[4]), float(b[4]), atol=EV_ATOL, rtol=0, equal_nan=False)
            np.testing.assert_allclose(float(a[5]), float(b[5]), atol=2e-6, rtol=0, equal_nan=False)
        elif got != want:
            raise AssertionError(f"Preview-input metadata differs at {line}")
    print("Preview-input PC exports match: EV atol=2e-6; ROI atol=2e-6; no photo data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.write:
        FIXTURE.write_bytes(gzip.compress(fixture().encode(), mtime=0))
    else:
        check_exports()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
