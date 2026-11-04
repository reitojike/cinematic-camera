"""Synthetic tests for the round-10i highlight handling; no real photos."""
import unittest

import numpy as np

import r10i_highlights as hi


class ShoulderTests(unittest.TestCase):
    def test_midtones_unchanged(self):
        image = np.full((4, 4, 3), .2, dtype=np.float32)
        np.testing.assert_allclose(hi.shoulder(image), image, atol=1e-6)

    def test_over_white_keeps_gradation_below_white(self):
        levels = np.array([1., 1.4, 2.], dtype=np.float32)
        image = np.repeat(levels[:, None, None], 3, axis=2).reshape(3, 1, 3)
        display = hi.encode(hi.shoulder(image) @ hi.r.LUMA).ravel()
        self.assertTrue(np.all(np.diff(display) > 0))
        self.assertTrue(np.all(display < 1))

    def test_colour_ratio_kept(self):
        image = np.array([[[1.2, .6, .3]]], dtype=np.float32)
        out = hi.shoulder(image)
        np.testing.assert_allclose(out / out[..., :1], image / image[..., :1], rtol=1e-5)


class FaceCutTests(unittest.TestCase):
    def test_no_cut_without_faces_or_when_dim(self):
        image = np.full((20, 20, 3), 1.5, dtype=np.float32)
        self.assertEqual(hi.face_cut(image, np.zeros((20, 20), dtype=np.float32)), 1.)
        dim = np.full((20, 20, 3), .3, dtype=np.float32)
        self.assertEqual(hi.face_cut(dim, np.ones((20, 20), dtype=np.float32)), 1.)

    def test_cut_is_limited(self):
        image = np.full((20, 20, 3), 50., dtype=np.float32)
        self.assertAlmostEqual(hi.face_cut(image, np.ones((20, 20), dtype=np.float32)), 2 ** -hi.MAX_CUT)


if __name__ == "__main__":
    unittest.main()
