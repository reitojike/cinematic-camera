"""Synthetic tests for the round-10h fixes; no real photos."""
import unittest

import numpy as np

import r10h_refine as h


def patch_with_hue(hue_deg: float, lightness: float = .7, chroma: float = .08) -> np.ndarray:
    rad = np.radians(hue_deg)
    lab = np.zeros((8, 8, 3), dtype=np.float32)
    lab[..., 0], lab[..., 1], lab[..., 2] = lightness, chroma * np.cos(rad), chroma * np.sin(rad)
    return h.k.oklab_to_linear(lab).astype(np.float32)


def hue_of(values: np.ndarray) -> float:
    lab = h.r.oklab(values)
    return float(np.degrees(np.arctan2(lab[..., 2].mean(), lab[..., 1].mean())) % 360)


class SkinCapTests(unittest.TestCase):
    def test_moderate_skin_is_unchanged(self):
        image = patch_with_hue(25., .7, .06)
        near = np.ones(image.shape[:2], dtype=np.float32)
        np.testing.assert_allclose(h.skin_chroma_cap(image, near), image, atol=1e-5)

    def test_vivid_red_skin_is_softened_without_hue_change(self):
        image = patch_with_hue(10., .65, .14)
        near = np.ones(image.shape[:2], dtype=np.float32)
        out = h.skin_chroma_cap(image, near)
        lab = h.r.oklab(out)
        self.assertLess(float(np.hypot(lab[..., 1], lab[..., 2]).mean()), .12)
        self.assertLess(abs(hue_of(out) - hue_of(image)), .5)

    def test_far_from_skin_is_left_alone(self):
        image = patch_with_hue(10., .65, .14)
        far = np.zeros(image.shape[:2], dtype=np.float32)
        np.testing.assert_allclose(h.skin_chroma_cap(image, far), image, atol=1e-5)


class StrongLevelTests(unittest.TestCase):
    def test_hue_lift_moves_only_pink_red_skin(self):
        near = np.ones((8, 8), dtype=np.float32)
        _, knee, slope, lift, _ = h.LEVELS["X"]
        red = patch_with_hue(5., .65, .12)
        self.assertGreater(hue_of(h.skin_chroma_cap(red, near, knee, slope, lift)), hue_of(red) + 3)
        orange = patch_with_hue(40., .7, .08)
        self.assertLess(abs(hue_of(h.skin_chroma_cap(orange, near, knee, slope, lift)) - 40.), .5)

    def test_stronger_levels_reduce_more(self):
        near = np.ones((8, 8), dtype=np.float32)
        image = patch_with_hue(15., .65, .12)
        chromas = []
        for level in ("M", "S", "X"):
            lab = h.r.oklab(h.skin_chroma_cap(image, near, *h.LEVELS[level][1:4]))
            chromas.append(float(np.hypot(lab[..., 1], lab[..., 2]).mean()))
        self.assertGreater(chromas[0], chromas[1])
        self.assertGreater(chromas[1], chromas[2])


class OrtonTests(unittest.TestCase):
    def test_no_chroma_gain_on_skin(self):
        image = patch_with_hue(25., .6, .09)
        skin = np.ones(image.shape[:2], dtype=np.float32)
        before, after = h.r.oklab(image), h.r.oklab(h.orton(image, skin))
        self.assertLessEqual(float(np.hypot(after[..., 1], after[..., 2]).mean()),
                             float(np.hypot(before[..., 1], before[..., 2]).mean()) + 1e-4)

    def test_near_white_is_not_pushed_up(self):
        white = np.full((40, 40, 3), .9, dtype=np.float32)
        out = h.orton(white, np.zeros((40, 40), dtype=np.float32))
        np.testing.assert_allclose(out, white, atol=2e-3)

    def test_auto_strength_uses_near_white_area(self):
        image = np.full((100, 100, 3), .2, dtype=np.float32)
        self.assertEqual(h.auto_strength(image), 1.)
        image[:4] = .95  # 4% near white, nothing over white
        self.assertAlmostEqual(h.auto_strength(image), .5)


if __name__ == "__main__":
    unittest.main()
