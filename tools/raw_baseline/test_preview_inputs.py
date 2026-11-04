import unittest

import numpy as np

import export_android_preview_inputs as export
import preview_inputs as oracle


class PreviewInputTests(unittest.TestCase):
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
