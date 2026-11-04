"""Exercise the real --write path without overwriting committed regression data."""
from contextlib import ExitStack, redirect_stdout
import gzip
import io
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import export_android_blur
import export_android_glow
import export_android_mist
import export_android_protections


class AndroidFixtureStorageTest(unittest.TestCase):
    def test_regeneration_writes_lossless_gzip_without_filename_or_timestamp(self):
        for module in (export_android_protections, export_android_blur,
                       export_android_glow, export_android_mist):
            with self.subTest(exporter=module.__name__), tempfile.TemporaryDirectory() as directory, ExitStack() as stack:
                fixture = Path(directory) / "synthetic.csv.gz"
                stack.enter_context(patch.object(module, "FIXTURE", fixture))
                if hasattr(module, "CONSTANTS"):
                    stack.enter_context(patch.object(module, "CONSTANTS", Path(directory) / "Constants.kt"))
                stack.enter_context(patch.object(sys, "argv", [module.__file__, "--write"]))
                stack.enter_context(redirect_stdout(io.StringIO()))
                self.assertEqual(0, module.main())
                packed = fixture.read_bytes()
                self.assertEqual(module.fixture().encode("utf-8"), gzip.decompress(packed))
                self.assertEqual(0, packed[3] & 8, "gzip must not embed the local filename")
                self.assertEqual(bytes(4), packed[4:8], "gzip must not embed a generation timestamp")
                module.check_exports()


if __name__ == "__main__":
    unittest.main()
