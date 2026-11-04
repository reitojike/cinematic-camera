"""Check committed Android conformance exports against the unchanged PC reference."""
import unittest

import export_android_protections as exporter


class AndroidProtectionExportsTest(unittest.TestCase):
    def test_exports_match_pc_authority(self):
        exporter.check_exports()


if __name__ == "__main__":
    unittest.main()
