"""Synthetic tests for the round-7 layers and metering fit; no real RAW or quality claims."""
from pathlib import Path
import tempfile
import unittest

import numpy as np

import r6_diagnose as r
import r7_exposure as e
import r7_look as k
import r7b_skin as sk
import r7c_punch as cp
import r7_final as fin


def hue_of(rgb):
    lab = r.oklab(np.asarray(rgb, dtype=np.float32).reshape(1, 3))[0]
    return float(np.degrees(np.arctan2(lab[2], lab[1])) % 360)


class LayerTests(unittest.TestCase):
    def test_oklab_round_trip(self):
        rng = np.random.default_rng(1)
        x = rng.uniform(0, 1, (50, 3)).astype(np.float32)
        np.testing.assert_allclose(k.oklab_to_linear(r.oklab(x)), x, atol=2e-4)

    def test_profile_layer_zero_is_identity(self):
        x = np.random.default_rng(2).uniform(0, 1, (4, 4, 3)).astype(np.float32)
        self.assertIs(k.profile_layer(x, 0.), x)

    def test_profile_layer_moves_violet_toward_blue_only(self):
        violet = np.array([[[.25, .05, .6]]], dtype=np.float32)
        skin = np.array([[[.6, .4, .3]]], dtype=np.float32)
        self.assertLess(hue_of(k.profile_layer(violet, 20.)), hue_of(violet) - 5)
        self.assertAlmostEqual(hue_of(k.profile_layer(skin, 20.)), hue_of(skin), places=2)

    def test_local_tone_keeps_flat_image(self):
        flat = np.full((40, 60, 3), .2, dtype=np.float32)
        np.testing.assert_allclose(k.local_tone(flat, .5, .5), flat, rtol=1e-4)

    def test_vibrance_leaves_neutral_grey(self):
        grey = np.full((2, 2, 3), .3, dtype=np.float32)
        np.testing.assert_allclose(k.vibrance(grey, 1.), grey, atol=1e-4)

    def test_vibrance_raises_muted_colour_more_than_skin(self):
        muted_blue = np.array([[[.18, .2, .3]]], dtype=np.float32)
        skin = np.array([[[.6, .42, .32]]], dtype=np.float32)

        def chroma(x):
            lab = r.oklab(x.reshape(-1, 3))[0]
            return float(np.hypot(lab[1], lab[2]))

        blue_gain = chroma(k.vibrance(muted_blue, 1.)) / chroma(muted_blue)
        skin_gain = chroma(k.vibrance(skin, 1.)) / chroma(skin)
        self.assertGreater(blue_gain, skin_gain)

    def test_p3_gamut_compress_in_range(self):
        values = np.array([[[1.3, -.1, .4]]], dtype=np.float32)
        out = k.gamut_compress_luma(values @ k.SRGB_TO_P3.T, k.P3_LUMA)
        self.assertTrue(((out >= 0) & (out <= 1)).all())


class SkinLayerTests(unittest.TestCase):
    def lab(self, rgb):
        return r.oklab(np.asarray(rgb, dtype=np.float32).reshape(1, 3))[0]

    def test_zero_strength_is_identity(self):
        x = np.random.default_rng(4).uniform(0, 1, (3, 3, 3)).astype(np.float32)
        self.assertIs(sk.skin_layer(x, 0.), x)

    def test_skin_becomes_lighter_and_less_saturated(self):
        skin = np.array([[[.62, .42, .32]]], dtype=np.float32)
        before, after = self.lab(skin), self.lab(sk.skin_layer(skin, 1.))
        self.assertGreater(after[0], before[0])
        self.assertLess(np.hypot(after[1], after[2]), np.hypot(before[1], before[2]))

    def test_blue_light_is_untouched(self):
        blue = np.array([[[.1, .15, .6]]], dtype=np.float32)
        np.testing.assert_allclose(sk.skin_layer(blue, 1.), blue, atol=1e-4)


class CrispTests(unittest.TestCase):
    def test_none_is_identity(self):
        x = np.random.default_rng(5).uniform(0, 1, (3, 3, 3)).astype(np.float32)
        self.assertIs(cp.crisp(x, None), x)

    def test_deep_shadows_get_darker_and_face_level_does_not(self):
        setting = cp.CRISP_LEVELS["K2"]
        shadow = np.full((1, 1, 3), .01, dtype=np.float32)
        face = np.full((1, 1, 3), .15, dtype=np.float32)
        self.assertLess(float(cp.crisp(shadow, setting)[0, 0, 1]), .01)
        self.assertGreaterEqual(float(cp.crisp(face, setting)[0, 0, 1]), .15 - 1e-4)

    def test_skin_chroma_is_not_boosted(self):
        # Same tone change with and without the chroma boost: skin chroma must barely differ,
        # while a muted blue gains clearly.
        skin = np.array([[[.35, .22, .16]]], dtype=np.float32)
        blue = np.array([[[.10, .12, .25]]], dtype=np.float32)
        boosted, plain = cp.CRISP_LEVELS["K2"], dict(cp.CRISP_LEVELS["K2"], chroma=0.)

        def chroma(x, setting):
            lab = r.oklab(cp.crisp(x, setting).reshape(1, 3))[0]
            return float(np.hypot(lab[1], lab[2]))

        self.assertLess(chroma(skin, boosted) / chroma(skin, plain), 1.05)
        self.assertGreater(chroma(blue, boosted) / chroma(blue, plain), 1.15)


class RecipeTests(unittest.TestCase):
    def test_per_scene_overrides_only_listed_keys(self):
        self.assertEqual(fin.chosen("b")["wb"], "W0")
        self.assertEqual(fin.chosen("b")["immersion"], "I0")
        self.assertEqual(fin.chosen("b")["skin"], "S2")
        self.assertEqual(fin.chosen("g")["wb"], "W1")

    def test_recipe_keys_exist_in_levels(self):
        for sid in ("b", "g"):
            choice = fin.chosen(sid)
            self.assertIn(choice["profile"], k.PROFILE_LEVELS)
            self.assertIn(choice["immersion"], k.IMMERSION_LEVELS)
            self.assertIn(choice["skin"], sk.SKIN_LEVELS)
            self.assertIn(choice["brightness"], sk.BRIGHTNESS_LEVELS)
            self.assertIn(choice["crisp"], cp.CRISP_LEVELS)
            self.assertIn(choice["wb"], cp.WB_LEVELS)


class OutputIsolationTests(unittest.TestCase):
    def test_dotdot_output_into_inputs_is_rejected_before_work(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder).resolve()
            for name in ("inputs", "r5", "outside"):
                (base / name).mkdir()
            alias = str(base / "outside" / ".." / "inputs" / "report")
            with self.assertRaises(SystemExit):
                k.main(["--livepicture", str(base / "inputs"), "--r5", str(base / "r5"), "--output", alias])
            with self.assertRaises(SystemExit):
                e.main(["--adopted", str(base / "inputs"), "--r5", str(base / "r5"), "--output", alias + ".json"])
            self.assertEqual(list((base / "inputs").iterdir()), [])

    def test_exposure_output_inside_r5_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder).resolve()
            for name in ("inputs", "r5"):
                (base / name).mkdir()
            with self.assertRaises(SystemExit):
                e.main(["--adopted", str(base / "inputs"), "--r5", str(base / "r5"), "--output", str(base / "r5" / "rule.json")])
            self.assertEqual(list((base / "r5").iterdir()), [])


class ExposureTests(unittest.TestCase):
    def test_crossval_exact_rule(self):
        rows = [{"inputs": {"c97": c}, "ev": 1.5 - .4 * np.log2(c)} for c in np.linspace(.03, .6, 20)]
        fit = e.crossval(rows, "c97", None)
        self.assertLess(fit["mae"], 1e-6)
        self.assertAlmostEqual(fit["slope"], -.4, places=6)

    def test_solve_ev_is_monotone(self):
        linear = np.random.default_rng(3).uniform(.001, .2, (30, 40, 3)).astype(np.float32)
        look = {"ev": 0., "contrast": 1., "rgb": [1., 1., 1.], "saturation": 1., "skin_saturation": 1.}
        low = e.solve_ev(linear, .3, look)
        high = e.solve_ev(linear, .6, look)
        self.assertLess(low, high)


if __name__ == "__main__":
    unittest.main()
