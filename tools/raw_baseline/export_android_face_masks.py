"""Synthetic integer boxes through the existing PC face_area; no detector or photo I/O."""
import argparse
import gzip
from pathlib import Path
from unittest.mock import patch

import numpy as np

import r10h_refine as pc

FIXTURE = Path(__file__).resolve().parents[2] / "app/src/test/resources/detected_face_masks_v1.csv.gz"
MASK_ATOL = 2e-6  # Chosen before comparison, same float32-mask boundary as explicit foundation ROI.


def scenes():
    return [
        ("empty", 48, 32, []), ("single", 16, 12, [(4, 1, 6, 6)]),
        ("odd-edge", 31, 23, [(24, 16, 8, 10)]), ("one-pixel", 1, 1, [(0, 0, 1, 1)]),
        ("outside", 32, 21, [(-4, -3, 12, 16)]),
        ("overlap", 37, 29, [(2, 4, 12, 14), (7, 8, 15, 14)]),
        ("wide", 41, 7, [(8, -2, 18, 10)]),
        ("top-six", 31, 29, [(i * 3 - 2, i * 2, i + 2, i + 3) for i in range(8)]),
        ("stable-ties", 29, 27, [(i * 3, i * 2, 5, 5) for i in range(8)]),
        ("six", 32, 32, [(x, y, 6, 8) for y in (2, 18) for x in (1, 12, 24)]),
        ("huge-box", 128, 96, [(-2048, -2048, 4096, 4096)]),
        ("far-outside", 32, 32, [(4096, 4096, 1, 1)]),
        ("sixty-four", 31, 31, [(i % 8 * 4, i // 8 * 4, 1, 1) for i in range(64)]),
    ]


def fixture():
    lines = ["# synthetic bbox-to-mask only; no inference or photo data; PC top-six and soft edge",
             "# CASE,name,width,height,boxes; BOX,x,y,width,height; PIX,mask"]
    for name, w, h, boxes in scenes():
        with patch.object(pc, "detect_faces", return_value=boxes):
            area, _ = pc.face_area(np.zeros((h, w, 3), dtype=np.float32))
        lines.append(f"CASE,{name},{w},{h},{len(boxes)}")
        lines.extend("BOX," + ",".join(str(v) for v in box) for box in boxes)
        lines.extend(f"PIX,{float(value):.10g}" for value in area.ravel())
    return "\n".join(lines) + "\n"


def check_exports():
    actual = gzip.decompress(FIXTURE.read_bytes()).decode().splitlines()
    expected = fixture().splitlines()
    if len(actual) != len(expected):
        raise AssertionError("Face-mask fixture layout changed")
    maximum = 0.
    for line, (got, want) in enumerate(zip(actual, expected), 1):
        if want.startswith("PIX,"):
            a, b = float(got.split(",")[1]), float(want.split(",")[1])
            np.testing.assert_allclose(a, b, atol=MASK_ATOL, rtol=0, equal_nan=False, err_msg=f"mask line {line}")
            maximum = max(maximum, abs(a - b))
        elif got != want:
            raise AssertionError(f"Face-mask metadata differs at {line}")
    print(f"Synthetic face-mask regeneration max_abs={maximum}; atol={MASK_ATOL}; no detector run")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--write", action="store_true")
    group.add_argument("--check", action="store_true")
    if parser.parse_args().write:
        FIXTURE.write_bytes(gzip.compress(fixture().encode(), mtime=0))
    else:
        check_exports()
