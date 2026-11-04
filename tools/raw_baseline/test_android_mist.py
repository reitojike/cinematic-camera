"""Stage-2 exports must match the unchanged PC reference."""
import unittest

import export_android_mist as exporter


class AndroidMistExportsTest(unittest.TestCase):
    def test_exports_match_pc(self):
        exporter.check_exports()


if __name__ == "__main__":
    unittest.main()
