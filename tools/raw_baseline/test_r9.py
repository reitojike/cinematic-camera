"""Synthetic tests for the Pixel 9a DNG maths (white XY, ForwardMatrix, LookTable, GainMap)."""
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image

import r6_diagnose as r
import r9_app as app
import r9_pixel as px

IDENTITY = [1., 0., 0., 0., 1., 0., 0., 0., 1.]


class WhiteTests(unittest.TestCase):
    def test_white_xy_gives_neutral_for_identity_matrix(self):
        tags = {"white_xy": (.3457, .3585), "cm1": IDENTITY, "cm2": IDENTITY, "illum1": (17,), "illum2": (21,)}
        neutral, _ = px.neutral_from_white_xy(tags)
        expected = r.xy_to_xyz(np.array([.3457, .3585]))
        np.testing.assert_allclose(neutral, expected / expected.max(), atol=1e-9)


class IlluminantOrderTests(unittest.TestCase):
    def test_weight_is_independent_of_illuminant_order(self):
        a_first = {"illum1": (17,), "illum2": (21,)}
        d65_first = {"illum1": (21,), "illum2": (17,)}
        for cct in (2500., 4000., 5000., 7000.):
            self.assertAlmostEqual(r.profile_weight(cct, a_first), 1 - r.profile_weight(cct, d65_first), places=9)
        self.assertAlmostEqual(r.profile_weight(4000., d65_first), .51, places=2)

    def test_weight_is_clamped_outside_the_illuminants(self):
        tags = {"illum1": (21,), "illum2": (17,)}
        self.assertEqual(r.profile_weight(9000., tags), 1.)
        self.assertEqual(r.profile_weight(2000., tags), 0.)


class LookTableTests(unittest.TestCase):
    def test_identity_table_keeps_pixels(self):
        dims = (6, 4, 3)
        table = np.tile([0., 1., 1.], dims[0] * dims[1] * dims[2])
        pixels = np.random.default_rng(1).uniform(.01, .9, (5, 5, 3))
        np.testing.assert_allclose(px.apply_look_table(pixels, table, dims, True), pixels, atol=1e-9)

    def test_value_scale_applies(self):
        dims = (6, 4, 2)
        table = np.tile([0., 1., .5], dims[0] * dims[1] * dims[2])
        pixels = np.full((2, 2, 3), .4)
        np.testing.assert_allclose(px.apply_look_table(pixels, table, dims, False), pixels * .5, atol=1e-9)


    def test_encoded_value_scale_applies_in_srgb_domain(self):
        dims = (6, 4, 2)
        table = np.tile([0., 1., .5], dims[0] * dims[1] * dims[2])
        grey = np.full((1, 1, 3), .4)
        out = px.apply_look_table(grey, table, dims, True)
        # linear 0.4 -> sRGB 0.665 -> x0.5 = 0.333 -> linear ~0.0904 (DNG SDK behaviour)
        np.testing.assert_allclose(out, np.full((1, 1, 3), .0904), atol=5e-4)

    def test_encoded_identity_keeps_pixels(self):
        dims = (6, 4, 2)
        table = np.tile([0., 1., 1.], dims[0] * dims[1] * dims[2])
        pixels = np.random.default_rng(2).uniform(.01, .9, (4, 4, 3))
        np.testing.assert_allclose(px.apply_look_table(pixels, table, dims, True), pixels, atol=1e-6)


class GainMapTests(unittest.TestCase):
    @staticmethod
    def opcode_list(gain: float, plane_rows: int = 2, plane_cols: int = 2) -> bytes:
        body = struct.pack(">10I", 0, 0, 8, 8, 0, 1, plane_rows, plane_cols, 2, 2)
        body += struct.pack(">4d", 1., 1., 0., 0.) + struct.pack(">I", 1) + struct.pack(">4f", *([gain] * 4))
        return struct.pack(">I", 1) + struct.pack(">IIII", 9, 0x01030000, 0, len(body)) + body

    def test_parse_and_unit_gain_is_identity(self):
        maps = px.parse_gain_maps(self.opcode_list(1.))
        image = np.arange(64, dtype=np.uint16).reshape(8, 8) * 100
        before = image.copy()
        px.apply_gain_maps(image, maps, black=0., white=65535.)
        np.testing.assert_array_equal(image, before)

    def test_gain_applies_only_to_its_cfa_sites(self):
        maps = px.parse_gain_maps(self.opcode_list(2.))
        image = np.full((8, 8), 1000, dtype=np.uint16)
        px.apply_gain_maps(image, maps, black=100., white=65535.)
        self.assertEqual(int(image[0, 0]), 100 + 2 * 900)  # pitch 2: (0,0) is corrected
        self.assertEqual(int(image[0, 1]), 1000)           # (0,1) belongs to another plane


class PairingTests(unittest.TestCase):
    """Pairs are matched by capture order, so a missing or extra capture must stop the run."""

    def folders(self, app_names, google_names):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        for folder, names in (("app", app_names), ("google", google_names)):
            (root / folder).mkdir()
            for name in names:
                (root / folder / name).write_bytes(b"")
        self.enterContext(patch.object(app, "capture_time", side_effect=lambda jpeg: jpeg.stem))
        return root / "app", root / "google"

    def test_equal_sets_pair_in_capture_order(self):
        a, g = self.folders(["A2.dng", "A2.jpg", "A1.dng", "A1.jpg"], ["G1.dng", "G1.jpg", "G2.dng", "G2.jpg"])
        app_shots, google_shots = app.paired_shots(a, g)
        self.assertEqual([d.name for d, _ in app_shots], ["A1.dng", "A2.dng"])
        self.assertEqual(len(google_shots), 2)

    def test_extra_capture_is_rejected(self):
        a, g = self.folders(["A1.dng", "A1.jpg", "A2.dng", "A2.jpg"], ["G1.dng", "G1.jpg"])
        with self.assertRaises(SystemExit) as caught:
            app.paired_shots(a, g)
        self.assertIn("capture counts differ", str(caught.exception))

    def test_dng_without_jpeg_is_reported(self):
        a, g = self.folders(["A1.dng", "A1.jpg", "A2.dng"], ["G1.dng", "G1.jpg"])
        with self.assertRaises(SystemExit) as caught:
            app.paired_shots(a, g)
        self.assertIn("unmatched file: A2.dng", str(caught.exception))

    def test_same_capture_time_is_rejected(self):
        a, g = self.folders(["A1.dng", "A1.jpg", "A2.dng", "A2.jpg"], ["G1.dng", "G1.jpg", "G2.dng", "G2.jpg"])
        times = {"A1": "2026:11:03 20:00:00", "A2": "2026:11:03 20:00:00", "G1": "1", "G2": "2"}
        with patch.object(app, "capture_time", side_effect=lambda jpeg: times[jpeg.stem]):
            with self.assertRaises(SystemExit) as caught:
                app.paired_shots(a, g)
        self.assertIn("same capture time 2026:11:03 20:00:00: A1.jpg, A2.jpg", str(caught.exception))

    def test_missing_capture_time_is_rejected(self):
        a, g = self.folders(["A1.dng", "A1.jpg"], ["G1.dng", "G1.jpg"])
        with patch.object(app, "capture_time", side_effect=lambda jpeg: "" if jpeg.stem == "G1" else "1"):
            with self.assertRaises(SystemExit) as caught:
                app.paired_shots(a, g)
        self.assertIn("missing capture time: G1.jpg", str(caught.exception))

    def test_subseconds_break_ties(self):
        root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        exif = Image.Exif()
        exif.get_ifd(0x8769).update({0x9003: "2026:11:03 20:00:00", 0x9291: "042"})
        Image.new("RGB", (4, 4)).save(root / "a.jpg", exif=exif)
        self.assertEqual(app.capture_time(root / "a.jpg"), "2026:11:03 20:00:00.042")

    def test_empty_folder_is_rejected(self):
        a, g = self.folders([], [])
        with self.assertRaises(SystemExit):
            app.paired_shots(a, g)


if __name__ == "__main__":
    unittest.main()
