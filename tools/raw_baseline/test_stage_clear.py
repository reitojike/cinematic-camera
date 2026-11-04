"""Stage Clear v1 tests on synthetic scenes (no photos, no OpenCV).

The golden file holds region means of a fixed synthetic stage (coloured lights, a skin face with
its mask, a near-white spot) at strength 0 / 0.5 / 1. A change to any step of Stage Clear v1 shows
up here; refresh it only on purpose, with `python test_stage_clear.py --write-golden`, and record
the new version.
"""
import json
from pathlib import Path
import sys
import unittest

import numpy as np

import r10d_character as cd
import r10_iconic as ic
import r10h_refine as h
import r10i_highlights as hi
import stage_clear as sc

GOLDEN = Path(__file__).with_name("golden") / "stage_clear_v1.json"
H, W = 90, 120


def ellipse(cx: float, cy: float, rx: float, ry: float) -> np.ndarray:
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32) + .5
    return np.clip((1.25 - np.hypot((xx - cx) / rx, (yy - cy) / ry)) / .35, 0, 1)


def scene() -> tuple[np.ndarray, np.ndarray, dict]:
    """Linear sRGB stage: dark background, red/blue/purple lights, a lit face, a white spot."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    image = np.zeros((H, W, 3), dtype=np.float32) + (.01 + .02 * yy / H)[..., None]
    lights = {"red": ((18, 18), (1.6, .12, .05)), "blue": ((60, 14), (.05, .15, 1.8)),
              "purple": ((102, 18), (.9, .1, 1.4))}
    regions = {}
    for name, ((cx, cy), colour) in lights.items():
        spot = np.exp(-((xx - cx) ** 2 + (yy - cy) ** 2) / (2 * 6. ** 2))
        image += spot[..., None] * np.array(colour, dtype=np.float32)
        regions[name] = (slice(cy - 3, cy + 4), slice(cx - 3, cx + 4))
        regions[name + "-halo"] = (slice(cy + 9, cy + 13), slice(cx - 2, cx + 3))
    face = ellipse(60, 58, 13, 16)
    image = image * (1 - face[..., None]) + face[..., None] * np.array([.62, .30, .22], dtype=np.float32)
    regions["face"] = (slice(52, 64), slice(54, 66))
    white = ellipse(105, 75, 5, 5)
    image = image * (1 - white[..., None]) + white[..., None] * 1.3
    regions["white"] = (slice(73, 78), slice(103, 108))
    regions["shadow"] = (slice(80, 88), slice(4, 20))
    return image, (face > .5).astype(np.float32), regions


def means(image: np.ndarray, regions: dict) -> dict:
    return {name: [round(float(v), 6) for v in image[rows, cols].reshape(-1, 3).mean(axis=0)] for name, (rows, cols) in regions.items()}


def golden_values() -> dict:
    image, faces, regions = scene()
    result = {}
    for strength in sc.STRENGTHS:
        out, info = sc.render(image, faces, strength)
        result[f"{strength:g}"] = {"regions": means(out, regions), "face_cut_ev": round(info["face_cut_ev"], 6)}
    return {"version": sc.VERSION, "strengths": result}


class StageClearTests(unittest.TestCase):
    def test_full_strength_is_the_round_10_look(self):
        image, faces, _ = scene()
        skin = sc.skin_weight(image)
        standard = ic.glow(image, cd.STANDARD)
        first = h.chosen_layer(standard, skin, h.CHOSEN_LEVEL, faces)
        factor = hi.face_cut(first, faces)
        layered = h.chosen_layer(standard * factor, skin, h.CHOSEN_LEVEL, faces) if factor < 1 else first
        out, _ = sc.render(image, faces, 1.)
        np.testing.assert_allclose(out, hi.shoulder(layered), atol=1e-6)

    def test_light_spread_grows_with_strength(self):
        image, faces, regions = scene()
        halo = [sc.render(image, faces, s)[0][regions["blue-halo"]].mean() for s in sc.STRENGTHS]
        self.assertTrue(halo[0] < halo[1] < halo[2])

    def test_zero_adds_no_light(self):
        image, faces, regions = scene()
        out, info = sc.render(image, faces, 0.)
        self.assertEqual(info["glow_strength"], 0.)
        self.assertLessEqual(out[regions["blue-halo"]].mean(), image[regions["blue-halo"]].mean() * 1.05)

    def test_protections_stay_on_at_zero(self):
        image, faces, regions = scene()
        bright = image * np.where(faces[..., None] > 0, 2.5, 1.).astype(np.float32)
        _, info = sc.render(bright, faces, 0.)
        self.assertLess(info["face_cut_ev"], 0)
        display = hi.encode(sc.render(image, faces, 0.)[0] @ hi.r.LUMA)
        self.assertLess(float(display[regions["white"]].max()), 1.)

    def test_default_strength_is_half(self):
        image, faces, _ = scene()
        self.assertEqual(sc.DEFAULT_STRENGTH, .5)
        out, info = sc.render(image, faces)
        self.assertEqual(info["strength"], .5)
        np.testing.assert_array_equal(out, sc.render(image, faces, .5)[0])

    def test_strength_range(self):
        image, faces, _ = scene()
        for bad in (-.1, 1.1):
            with self.assertRaises(ValueError):
                sc.render(image, faces, bad)

    def test_golden(self):
        expected = json.loads(GOLDEN.read_text(encoding="utf-8"))
        actual = golden_values()
        self.assertEqual(actual["version"], expected["version"])
        for strength, want in expected["strengths"].items():
            got = actual["strengths"][strength]
            self.assertAlmostEqual(got["face_cut_ev"], want["face_cut_ev"], places=4)
            for name, rgb in want["regions"].items():
                np.testing.assert_allclose(got["regions"][name], rgb, atol=2e-4, err_msg=f"{strength} {name}")


if __name__ == "__main__":
    if "--write-golden" in sys.argv:
        GOLDEN.parent.mkdir(exist_ok=True)
        GOLDEN.write_text(json.dumps(golden_values(), indent=1) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {GOLDEN}")
    else:
        unittest.main()
