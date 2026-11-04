"""No private inputs: public common settings, synthetic ROI, fixed exported pixels."""
import unittest

import export_android_r7 as export


class R7BaseTests(unittest.TestCase):
    def test_public_recipe_and_all_pixels_match_frozen_export(self):
        export.check_exports()

    def test_recipe_has_no_scene_or_source_metadata(self):
        self.assertEqual(set(export.base.FOUNDATION),
                         {"ev", "contrast", "rgb", "saturation", "skin_saturation", "yellow_rgb"})
        self.assertEqual(export.base.FOUNDATION["rgb"], [1., 1., 1.])


if __name__ == "__main__":
    unittest.main()
