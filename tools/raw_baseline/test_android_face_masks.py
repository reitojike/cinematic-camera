import unittest

import export_android_face_masks as export


class FaceMaskExportsTest(unittest.TestCase):
    def test_public_synthetic_boxes_match_pc_face_area(self):
        export.check_exports()

    def test_reference_includes_selection_ties_outside_empty_and_bounded_limit(self):
        cases = {name: boxes for name, _, _, boxes in export.scenes()}
        self.assertEqual(cases["empty"], [])
        self.assertEqual(len(cases["stable-ties"]), 8)
        self.assertEqual(len(cases["sixty-four"]), 64)
        self.assertEqual(export.MASK_ATOL, 2e-6)


if __name__ == "__main__":
    unittest.main()
