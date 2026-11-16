import csv
import json
import tempfile
import unittest
from pathlib import Path

from report import report


class LargeReportTest(unittest.TestCase):
    def fixture(self, root):
        source = root / "source"
        source.mkdir()
        cases = []
        for w, h in ((4000, 3000), (6000, 4000)):
            for kind in ("TONES", "COLOR_PATCHES", "MICRO_DETAIL", "LIGHTS", "NOISE", "MIXED"):
                for strength, warmth, ev in ((0, 0, 0), (.5, 0, 0), (1, 100, 2)):
                    key = f"l{len(cases) + 1:03d}"
                    row = dict(id=key, kind=kind, rawWidth=w, rawHeight=h,
                               comparisonWidth=1024, comparisonHeight=768 if w == 4000 else 682,
                               strength=strength, warmth=warmth, ev=ev,
                               full=f"{key}-full.png", half=f"{key}-half.png",
                               metrics=dict(maeRgb8=float(len(cases)), maxRgb8=32, p99Rgb8=4,
                                            rmseRgb8=2, psnrDb=30, changedPixelFraction=.5))
                    for mode in ("full", "half"):
                        (source / row[mode]).write_bytes(b"synthetic-test-placeholder")
                    cases.append(row)
        data = dict(schema=1, matrixVersion="large-raw-36-v1", status="complete", cases=cases)
        return source, data

    def write(self, source, data):
        (source / "comparison.json").write_text(json.dumps(data), encoding="utf-8")

    def test_complete_matrix_and_non_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, data = self.fixture(root)
            self.write(source, data)
            output = root / "review"
            report(source, output, "1234abcd")
            with (output / "metrics.csv").open(encoding="utf-8", newline="") as f:
                self.assertEqual(36, len(list(csv.DictReader(f))))
            text = (output / "README.md").read_text(encoding="utf-8")
            self.assertIn("36 条件完走", text)
            self.assertIn("4000×3000 / 6000×4000", text)
            self.assertNotIn("3.15MP", text)
            with self.assertRaises(FileExistsError):
                report(source, output, "1234abcd")

    def test_invalid_captures_rejected_before_output(self):
        mutations = [lambda d: d.update(status="incomplete"),
                     lambda d: d["cases"].pop(),
                     lambda d: d["cases"][0].update(id="l002"),
                     lambda d: d["cases"][0].update(rawWidth=4002),
                     lambda d: d["cases"][0].update(full="../private.png"),
                     lambda d: d.update(matrixVersion="unknown")]
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                source, data = self.fixture(root)
                mutation(data)
                self.write(source, data)
                with self.assertRaises(ValueError):
                    report(source, root / "review", "1234abcd")
                self.assertFalse((root / "review").exists())


if __name__ == "__main__":
    unittest.main()