"""Synthetic tests for the round-10b extreme looks; no real photos."""
import unittest

import numpy as np

import r10b_extreme as x


class ToneTests(unittest.TestCase):
    def test_curves_keep_white_and_order(self):
        grey = np.linspace(0, 1, 101, dtype=np.float32)
        for key in ("toe", "contrast", "shoulder"):
            curve = x.tone(grey, {key: x.LOOKS["X6"].get("toe", .15) if key == "toe" else .3 if key == "contrast" else .9})
            self.assertAlmostEqual(float(curve[-1]), 1., places=5)
            self.assertTrue(np.all(np.diff(curve) >= -1e-6))

    def test_fade_lifts_black(self):
        self.assertAlmostEqual(float(x.tone(np.zeros(1, np.float32), {"fade": .1})[0]), .1, places=6)


class GradeTests(unittest.TestCase):
    @staticmethod
    def scene():
        image = np.full((200, 300, 3), .03, dtype=np.float32)
        image[:, :150] = [.05, .08, .6]   # blue stage light
        image[80:120, 200:240] = [.45, .30, .24]  # skin-like patch
        return image

    def test_dominant_hue_finds_the_light(self):
        lab = x.r.oklab(self.scene())
        skin = x.sk.skin_weight(lab)
        blue = np.degrees(np.arctan2(lab[0, 0, 2], lab[0, 0, 1])) % 360
        self.assertLess(abs((x.dominant_hue(lab, skin) - blue + 180) % 360 - 180), 2.)

    def test_skin_keeps_its_hue_under_strong_colour_moves(self):
        image = self.scene()
        skin = x.sk.skin_weight(x.r.oklab(image))
        look = {k: v for k, v in x.LOOKS["X3"].items() if k != "glow"}
        out = x.grade(image, look, skin)
        hue = lambda v: np.degrees(np.arctan2(*x.r.oklab(v)[100, 220, [2, 1]])) % 360
        self.assertLess(abs((hue(out) - hue(image) + 180) % 360 - 180), 6.)

    def test_every_look_stays_finite(self):
        image = self.scene()
        skin = x.sk.skin_weight(x.r.oklab(image))
        for look in x.LOOKS.values():
            self.assertTrue(np.all(np.isfinite(x.grade(image, look, skin))))


if __name__ == "__main__":
    unittest.main()
