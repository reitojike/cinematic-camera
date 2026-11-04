"""Migration regression reference, not a permanent visual acceptance threshold."""
import unittest
import numpy as np

import export_android_orton as exporter


class AndroidOrtonExportsTest(unittest.TestCase):
    def test_exports_match_pc(self):
        exporter.check_exports()

    def test_regeneration_budget_handles_hdr_rounding_but_rejects_drift(self):
        # CI's observed ~4.64 discrepancy: 7 float32 ULPs, below the 8-ULP budget.
        hdr = np.float32(4.639997482)
        ulp = float(np.spacing(hdr))
        exporter.assert_regenerated_pixels([float(hdr) + 7 * ulp], [hdr], "HDR runner variation")
        with self.assertRaises(AssertionError):
            exporter.assert_regenerated_pixels([float(hdr) + 9 * ulp], [hdr], "HDR drift")
        exporter.assert_regenerated_pixels([.2 + 1.9e-6], [.2], "SDR floor")
        for bad in (.2 + 2.1e-6, float("nan"), float("inf")):
            with self.assertRaises(AssertionError):
                exporter.assert_regenerated_pixels([bad], [.2], "SDR drift/nonfinite")


if __name__ == "__main__":
    unittest.main()
