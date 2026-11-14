"""V1 generation routing/authority checks, separate from synthetic pixel conformance."""
from contextlib import redirect_stdout
import importlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


EXPORTS = {
    "protections": "StageClearV1",
    "glow": "StageClearGlowV1",
    "mist": "StageClearMistV1",
    "orton": "StageClearOrtonV1",
    "character": "StageClearCharacterV1",
    "r7": "R7BaseV1",
}
ROOT = Path(__file__).resolve().parents[2]
PACKAGE = Path("app/src/main/java/dev/cinematic/p0/gpu/constants")


class V1ConstantExportsTest(unittest.TestCase):
    def test_all_six_generators_target_only_the_gpu_definition(self):
        self.assertEqual(6, len(EXPORTS))
        for kind, name in EXPORTS.items():
            with self.subTest(generator=kind):
                module = importlib.import_module(f"export_android_{kind}")
                self.assertEqual(ROOT / PACKAGE / f"{name}.kt", module.CONSTANTS)
                self.assertFalse((ROOT / f"app/src/main/java/dev/cinematic/p0/raw/{name}.kt").exists())

    def test_generated_declarations_match_committed_values_types_and_order(self):
        for kind, name in EXPORTS.items():
            with self.subTest(generator=kind):
                module = importlib.import_module(f"export_android_{kind}")
                text = (ROOT / PACKAGE / f"{name}.kt").read_text(encoding="utf-8")
                self.assertEqual(text, module.constants())
                self.assertIn("package dev.cinematic.p0.gpu.constants\n", text)

    def test_write_and_check_use_the_relocated_definition_and_reject_drift(self):
        for kind, name in EXPORTS.items():
            with self.subTest(generator=kind), tempfile.TemporaryDirectory() as directory:
                module = importlib.import_module(f"export_android_{kind}")
                root = Path(directory)
                target = root / module.CONSTANTS.relative_to(ROOT)
                fixture = root / module.FIXTURE.relative_to(ROOT)
                target.parent.mkdir(parents=True)
                fixture.parent.mkdir(parents=True)
                # Exercise CLI routing only. Existing pixel goldens are never rewritten by this test.
                with patch.object(module, "CONSTANTS", target), patch.object(module, "FIXTURE", fixture), \
                        patch.object(module, "fixture", return_value="# routing fixture\nPIX," + ",".join(["0"] * 16) + "\n"), \
                        redirect_stdout(io.StringIO()):
                    with patch("sys.argv", [module.__name__, "--write"]):
                        self.assertEqual(0, module.main())
                    self.assertEqual(module.constants(), target.read_text(encoding="utf-8"))
                    self.assertFalse((root / f"app/src/main/java/dev/cinematic/p0/raw/{name}.kt").exists())
                    with patch("sys.argv", [module.__name__, "--check"]):
                        self.assertEqual(0, module.main())
                        target.write_text(target.read_text(encoding="utf-8") + "// drift\n", encoding="utf-8")
                        with self.assertRaises(AssertionError):
                            module.main()


if __name__ == "__main__":
    unittest.main()
