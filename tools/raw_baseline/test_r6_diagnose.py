"""Synthetic tests for the round-6 diagnosis maths; no real RAW, references or quality claims."""
from pathlib import Path
import struct
import tempfile
import unittest

import numpy as np

import r6_diagnose as r


class ColourTests(unittest.TestCase):
    def test_oklab_white_is_achromatic(self):
        lab = r.oklab(np.ones((1, 3), dtype=np.float32))[0]
        self.assertAlmostEqual(float(lab[0]), 1., places=3)
        self.assertLess(float(np.hypot(lab[1], lab[2])), 1e-3)

    def test_p3_white_maps_to_srgb_white(self):
        np.testing.assert_allclose(np.ones(3) @ r.P3_TO_SRGB.T, np.ones(3), atol=1e-5)

    def test_identity_hue_sat_map_keeps_pixels(self):
        rng = np.random.default_rng(0)
        pixels = rng.uniform(.01, 1, (8, 8, 3))
        table = np.tile([0., 1., 1.], 36 * 2)
        np.testing.assert_allclose(r.apply_hue_sat_map(pixels, table, 36, 2), pixels, atol=1e-9)

    def test_hue_shift_rotates_hue(self):
        red = np.array([[[1., .2, .2]]])
        table = np.tile([120., 1., 1.], 36 * 2)
        out = r.apply_hue_sat_map(red, table, 36, 2)[0, 0]
        self.assertEqual(int(np.argmax(out)), 1)

    def test_temp_tint_round_trip(self):
        for temp, tint in ((3200., 0.), (4500., 15.), (6500., -10.)):
            got_temp, got_tint = r.xy_to_temp_tint(r.temp_tint_to_xy(temp, tint))
            self.assertAlmostEqual(got_temp / temp, 1., delta=.01)
            self.assertAlmostEqual(got_tint, tint, delta=1.)

    def test_positive_tint_is_magenta(self):
        neutral, magenta = r.temp_tint_to_xy(5000., 0.), r.temp_tint_to_xy(5000., 30.)
        self.assertLess(magenta[1], neutral[1])


class MeasurementTests(unittest.TestCase):
    def test_hue_delta_wraps(self):
        self.assertAlmostEqual(r.hue_delta(350., 10.), 20.)
        self.assertAlmostEqual(r.hue_delta(10., 350.), -20.)
        self.assertIsNone(r.hue_delta(None, 10.))

    def test_exposure_match_hits_reference_median(self):
        image = np.full((10, 10, 3), .1, dtype=np.float32)
        matched, ev = r.match_exposure(image, image * 4, [0, 0, 1, 1])
        self.assertAlmostEqual(ev, 2., places=5)
        np.testing.assert_allclose(matched, image * 4, rtol=1e-5)

    def test_exact_rule_has_zero_loo_residual(self):
        axis4 = {k: {"c97": c, "skin": c, "manual_ev": 1 - .4 * np.log2(c)} for k, c in zip("abcd", (.05, .1, .2, .4))}
        fit = r.fit_exposure(axis4)
        self.assertTrue(all(abs(v) < 1e-9 for v in fit["c97_-0.40"]["loo_residual"].values()))

    def test_gamut_compress_keeps_in_gamut_and_range(self):
        values = np.array([[[.2, .4, .6]], [[1.4, -.2, .3]]], dtype=np.float32)
        out = r.gamut_compress(values)
        np.testing.assert_allclose(out[0], values[0], atol=1e-6)
        self.assertTrue(((out >= 0) & (out <= 1)).all())


class TagTests(unittest.TestCase):
    def test_reads_srational_and_short_profile_tags(self):
        entries = [(50721, 10, 9, [(i, 1000) for i in range(1, 10)]), (50778, 3, 1, [17])]
        header = b"II" + struct.pack("<HI", 42, 8)
        count = len(entries)
        data_start = 8 + 2 + 12 * count + 4
        records, payload = b"", b""
        for tag, kind, length, values in entries:
            if kind == 10:
                blob = b"".join(struct.pack("<ii", n, d) for n, d in values)
                records += struct.pack("<HHII", tag, kind, length, data_start + len(payload))
                payload += blob
            else:
                records += struct.pack("<HHIH2x", tag, kind, length, values[0])
        blob = header + struct.pack("<H", count) + records + bytes(4) + payload
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "t.dng"
            path.write_bytes(blob)
            tags = r.dng_profile_tags(path)
        self.assertEqual(tags["illum1"], (17,))
        np.testing.assert_allclose(tags["cm1"], [i / 1000 for i in range(1, 10)])


if __name__ == "__main__":
    unittest.main()
