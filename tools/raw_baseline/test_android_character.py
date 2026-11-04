"""All look stages from an already developed base, separate from DNG/decode/R7."""
import unittest
import export_android_character as exporter


class AndroidCharacterExportsTest(unittest.TestCase):
    def test_exports_match_pc(self):
        exporter.check_exports()


if __name__ == "__main__":
    unittest.main()
