"""Independent WhiteXY coefficient fixture and raw-tag contract checks."""
import hashlib
import unittest

import numpy as np
import export_android_white_xy as export


class AndroidWhiteXyFixtureTest(unittest.TestCase):
    def test_committed_fixture_matches_independent_reference(self):
        self.assertEqual(export.OUTPUT.read_text(encoding="utf-8"), export.contents())
        self.assertEqual((export.WB_ATOL, export.WB_RTOL), (2e-5, 2e-6))
        self.assertEqual(len(export.cases()), 11)

    def test_all_31_rows_match_fixed_colour_source(self):
        # Fixed upstream source SHA02594463...299a50; data-only AST extraction,
        # independently checked before fixture generation. No native table input.
        self.assertEqual(hashlib.sha256(export.TABLE.astype("<f8").tobytes()).hexdigest(),
                         "1d83baab036a5068a38e80d56b8b078ebc5404f164e302ab7f801fc066a983c1")
        self.assertEqual(export.TABLE[19, 1], .24792)

    def test_chromaticity_reference_values_and_corrected_325_region(self):
        expected = [((.3457, .3585), 5000.706605070579),
                    ((.4, .4), 3690.2471527268026),
                    ((.3127, .3290), 6503.70718479529),
                    ((.44757, .40745), 2855.7553370077117),
                    ((.42, .4), 3272.81101263)]
        for xy, temperature in expected:
            self.assertAlmostEqual(export.correlated_temperature(xy), temperature, delta=1e-8)

    def test_reversing_calibration_endpoints_preserves_neutral_and_wb(self):
        cases = {case["id"]: case for case in export.cases()}
        for name in ("interior", "d65"):
            ordinary, reverse = cases[name], cases[f"reversed-{name}"]
            np.testing.assert_allclose(ordinary["neutral"], reverse["neutral"], rtol=0, atol=1e-11)
            self.assertEqual(ordinary["requested_wb"], reverse["requested_wb"])
            self.assertAlmostEqual(ordinary["first_weight"] + reverse["first_weight"], 1, delta=1e-11)
        self.assertEqual(cases["low-end-clamp"]["first_weight"], 1)
        self.assertEqual(cases["d65"]["first_weight"], 0)

    def test_raw_scaling_contract_is_not_sdk_matrix_normalization(self):
        cases = {case["id"]: case for case in export.cases()}
        base = cases["interior"]
        for name in ("raw-cm1-scale-0.5", "raw-cm1-scale-1.5"):
            self.assertGreater(np.max(np.abs(np.array(cases[name]["requested_wb"]) - base["requested_wb"])), .01)
        doubled = cases["common-cm-scale-2"]
        self.assertEqual(base["requested_wb"], doubled["requested_wb"])
        np.testing.assert_allclose(np.array(base["neutral"])*2, doubled["neutral"], rtol=0, atol=1e-11)

    def test_endpoint_composites_differ_from_multiplying_interpolated_matrices(self):
        case = next(c for c in export.cases() if c["id"] == "interior")
        m = case["metadata"]
        g = case["first_weight"]
        matrix = lambda key: np.asarray(m[key]).reshape(3, 3)
        wrong = np.diag(m["ab"]) @ (g*matrix("cc1")+(1-g)*matrix("cc2")) @ (g*matrix("cm1")+(1-g)*matrix("cm2"))
        x, y = m["white_xy"]
        wrong_neutral = wrong @ np.array([x/y, 1, (1-x-y)/y])
        self.assertGreater(np.max(np.abs(wrong_neutral-case["neutral"])), 1e-4)
        # Every expected multiplier independently whitens its raw neutral.
        for item in export.cases():
            balanced = np.asarray(item["neutral"])*item["requested_wb"]
            np.testing.assert_allclose(balanced, np.repeat(balanced[1], 3),
                                       atol=export.WB_ATOL, rtol=export.WB_RTOL)

    def test_invalid_and_out_of_domain_chromaticities_reject(self):
        for xy in ((float("nan"), .3), (.8, .4), (0, .3), (.25, .25), (.56, .4)):
            with self.subTest(xy=xy), self.assertRaises(ValueError):
                export.correlated_temperature(xy)


if __name__ == "__main__":
    unittest.main()
