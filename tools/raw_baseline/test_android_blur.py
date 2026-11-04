"""Check the blur exports against the unchanged PC/Pillow path."""
import unittest

import export_android_blur as exporter


class AndroidBlurExportsTest(unittest.TestCase):
    def test_exports_match_pc(self):
        exporter.check_exports()


if __name__ == "__main__":
    unittest.main()
