"""Synthetic tests for the Pixel 9a DNG maths (white XY, ForwardMatrix, LookTable, GainMap)."""
import struct
import unittest

import numpy as np

import r6_diagnose as r
import r9_pixel as px

IDENTITY = [1., 0., 0., 0., 1., 0., 0., 0., 1.]


class WhiteTests(unittest.TestCase):
    def test_white_xy_gives_neutral_for_identity_matrix(self):
        tags = {"white_xy": (.3457, .3585), "cm1": IDENTITY, "cm2": IDENTITY, "illum1": (17,), "illum2": (21,)}
        neutral, _ = px.neutral_from_white_xy(tags)
        expected = r.xy_to_xyz(np.array([.3457, .3585]))
        np.testing.assert_allclose(neutral, expected / expected.max(), atol=1e-9)


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


if __name__ == "__main__":
    unittest.main()
