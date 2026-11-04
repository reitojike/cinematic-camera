"""Synthetic tests for the round-10e portrait effects; no real photos."""
import unittest

import numpy as np

import r10e_portrait as p


def scene():
    image = np.full((200, 300, 3), .02, dtype=np.float32)
    image[60:140, 120:180] = [.45, .30, .24]  # skin-like face
    image[20:40, 20:60] = 2.                   # bright light
    return image


class EffectTests(unittest.TestCase):
    def setUp(self):
        self.image = scene()
        self.skin = p.sk.skin_weight(p.r.oklab(self.image))
        self.face = p.face_mask(self.image.shape[:2], {"skin": [[.5, .5, .1, .2]]})

    def test_every_effect_stays_finite_and_non_negative(self):
        for key in p.EFFECTS:
            out = p.effect(self.image, key, self.skin, self.face)
            self.assertTrue(np.all(np.isfinite(out)), key)
            self.assertGreaterEqual(float(out.min()), -1e-4, key)

    def test_black_mist_keeps_far_shadows_dark(self):
        out = p.black_mist(self.image, self.skin)
        self.assertLess(abs(float(out[190, 290].mean()) - .02), 1e-3)
        self.assertGreater(float(out[45, 40].mean()), .02 + 1e-3)  # glow next to the light

    def test_vignette_darkens_corners_only(self):
        flat = np.full((100, 150, 3), .5, dtype=np.float32)
        out = p.vignette(flat)
        self.assertAlmostEqual(float(out[50, 75, 0]), .5, places=5)
        self.assertLess(float(out[0, 0, 0]), .4)

    def test_face_focus_keeps_face_detail_and_softens_elsewhere(self):
        stripes = np.zeros((200, 300, 3), dtype=np.float32)
        stripes[:, ::2] = .6
        out = p.face_focus(stripes, self.face)
        self.assertGreater(float(out[100, 140:160].std()), float(stripes[100, 140:160].std()) * .99)
        self.assertLess(float(out[100, 10:30].std()), float(stripes[100, 10:30].std()) * .7)

    def test_blush_adds_red_on_skin_only(self):
        out = p.blush(self.image, self.skin)
        delta = out - self.image
        self.assertGreater(float(delta[100, 150, 0] - delta[100, 150, 2]), 0)
        np.testing.assert_allclose(out[190, 290], self.image[190, 290], atol=1e-5)


class ComboTests(unittest.TestCase):
    def test_combos_stay_finite_and_weak_sits_between(self):
        import r10f_combo as cb
        image = scene()
        skin = p.sk.skin_weight(p.r.oklab(image))
        face = p.face_mask(image.shape[:2], {"skin": [[.5, .5, .1, .2]]})
        outs = {key: cb.combo(image, key, skin, face) for key in cb.COMBOS}
        for key, out in outs.items():
            self.assertTrue(np.all(np.isfinite(out)), key)
        np.testing.assert_array_equal(outs["std"], image)
        corner = lambda v: float(v[195, 5].mean())  # vignette darkens the corner; weak darkens it less
        self.assertLess(corner(outs["ALL"]), corner(outs["ALLw"]))
        self.assertLess(corner(outs["ALLw"]), corner(image))


    def test_auto_strength_backs_off_on_clipped_photos(self):
        import r10f_combo as cb
        dark = np.full((100, 100, 3), .2, dtype=np.float32)
        self.assertEqual(cb.auto_strength(dark), 1.)
        clipped = dark.copy()
        clipped[:5] = 2.  # 5% over white
        self.assertAlmostEqual(cb.auto_strength(clipped), .5)


if __name__ == "__main__":
    unittest.main()
