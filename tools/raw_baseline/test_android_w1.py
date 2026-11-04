"""Regenerate the public synthetic WB fixture from the PC W1 implementation."""
import unittest
import export_android_w1 as export


class AndroidW1FixtureTest(unittest.TestCase):
    def test_pc_w1_fixture_is_current(self):
        self.assertEqual(export.OUTPUT.read_text(encoding="utf-8"), export.contents())


if __name__ == "__main__":
    unittest.main()
