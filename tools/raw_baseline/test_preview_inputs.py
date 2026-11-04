import itertools
import math
import unittest

import numpy as np

import export_android_preview_inputs as export
import preview_inputs as oracle


class PreviewInputTests(unittest.TestCase):
    def test_raw_p97_budget_covers_rounding_but_rejects_larger_drift(self):
        for sign in (-1., 1.):
            expected = sign * 100.
            ulp = export.float32_ulp(expected)
            self.assertEqual(ulp, 7.62939453125e-6)
            for steps in (1, 2):
                export.assert_p97(expected + sign * steps * ulp, expected)
            with self.assertRaises(AssertionError):
                export.assert_p97(expected + sign * 3 * ulp, expected)
        export.assert_p97(2e-6, 0.)
        with self.assertRaises(AssertionError):
            export.assert_p97(2.1e-6, 0.)
        for value in (float("nan"), float("inf"), -float("inf")):
            with self.assertRaises(AssertionError):
                export.assert_p97(value, 100.)
            with self.assertRaises(AssertionError):
                export.assert_p97(100., value)
        self.assertEqual(export.EV_ATOL, 2e-6)
        self.assertEqual(export.ROI_ATOL, 2e-6)

    def test_float32_ulp_is_finite_for_sign_binade_subnormal_and_max(self):
        for value, expected in ((0., 2. ** -149), (2. ** -149, 2. ** -149),
                                (2. ** -126, 2. ** -149), (1., 2. ** -23),
                                (float(np.nextafter(np.float32(1), np.float32(0))), 2. ** -24),
                                (2., 2. ** -22), (float(np.finfo(np.float32).max), 2. ** 104)):
            for signed in (value, -value):
                self.assertEqual(export.float32_ulp(signed), expected)
                self.assertTrue(math.isfinite(max(export.P97_ABS_ATOL, 2 * export.float32_ulp(signed))))
                export.assert_p97(signed, signed)

    def test_rounding_order_can_reproduce_ci_hdr_stat_without_changing_oracle(self):
        # Float32 multiply-add rounding, not a claim about the runner's actual instruction sequence.
        outcomes = set()
        for order in itertools.permutations(range(3)):
            acc = np.float32(0)
            for channel in order:
                acc = np.float32(float(np.float32(100)) * float(oracle.r.LUMA[channel]) + float(acc))
            outcomes.add(float(acc))
        self.assertIn(100., outcomes)
        self.assertIn(100.00000762939453, outcomes)
        # A different raw statistic must not loosen the EV output budget.
        with self.assertRaises(AssertionError):
            np.testing.assert_allclose(-3.6 + 2.1e-6, -3.6, atol=export.EV_ATOL, rtol=0)
        with self.assertRaises(AssertionError):
            np.testing.assert_allclose(.2 + 2.1e-6, .2, atol=export.ROI_ATOL, rtol=0)

    def test_synthetic_export_matches_existing_pc_metering_and_roi(self):
        export.check_exports()

    def test_central_bounds_clamps_and_explicit_empty_roi(self):
        cases = {name: (image, ellipses) for name, image, ellipses in export.scenes()}
        for name in ("black", "negative", "dark-clamp"):
            ev, _, _, roi = oracle.prepare(*cases[name])
            self.assertAlmostEqual(ev, 5.4, places=12)
            self.assertFalse(roi.any())
        self.assertAlmostEqual(oracle.prepare(*cases["hdr-clamp"])[0], -3.6, places=12)
        self.assertAlmostEqual(oracle.prepare(*cases["outside-bright"])[1], .2, places=6)
        image, ellipses = cases["soft-overlap"]
        before = image.copy()
        oracle.prepare(image, ellipses)
        np.testing.assert_array_equal(before, image)

    def test_invalid_preview_inputs_are_rejected(self):
        for shape in ((1, 1, 3), (2, 1, 3), (1, 2, 3), (2, 1025, 3)):
            with self.assertRaises(ValueError):
                oracle.prepare(np.zeros(shape, dtype=np.float32), [])
        image = np.full((4, 4, 3), .2, dtype=np.float32)
        for ellipses in ([[.5, .5, 0., .2]], [[1.1, .5, .2, .2]], [[.5, .5, .2, float("nan")]],
                         [[.5, .5, .2, .2]] * 7):
            with self.assertRaises(ValueError):
                oracle.prepare(image, ellipses)
        image[0, 0, 0] = float("nan")
        with self.assertRaises(ValueError):
            oracle.prepare(image, [])


if __name__ == "__main__":
    unittest.main()
