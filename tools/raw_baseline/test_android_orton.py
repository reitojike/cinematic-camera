"""Migration regression reference, not a permanent visual acceptance threshold."""
import unittest

import export_android_orton as exporter


class AndroidOrtonExportsTest(unittest.TestCase):
    def test_exports_match_pc(self):
        exporter.check_exports()


if __name__ == "__main__":
    unittest.main()
