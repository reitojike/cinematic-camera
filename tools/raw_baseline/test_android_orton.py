"""Migration regression reference, not a permanent visual acceptance threshold."""
import unittest

import export_android_orton as exporter


class AndroidOrtonExportsTest(unittest.TestCase):
    def test_exports_match_pc(self):
        exporter.check_exports()

    def test_regeneration_budget_rejects_output_drift_and_keeps_input_boundary(self):
        exporter.assert_regenerated_pixels([.2 + 1.9e-5], [.2], "SC-010 output")
        for bad in (.2 + 2.1e-5, float("nan"), float("inf")):
            with self.assertRaises(AssertionError):
                exporter.assert_regenerated_pixels([bad], [.2], "output drift/nonfinite")
        with self.assertRaises(AssertionError):
            exporter.assert_regenerated_pixels([.2 + 2.1e-6], [.2], "input drift", atol=2e-6)
        with self.assertRaises(AssertionError):
            exporter.assert_regenerated_pixels([.2, .3], [.2], "shape drift")


if __name__ == "__main__":
    unittest.main()
