"""Synthetic tests for the round-10 glow and grain layers; no real photos."""
import unittest

import numpy as np

import r10_iconic as ic


class GlowTests(unittest.TestCase):
    def test_none_is_identity(self):
        x = np.random.default_rng(1).uniform(0, 1, (20, 30, 3)).astype(np.float32)
        self.assertIs(ic.glow(x, None), x)

    def test_dark_flat_image_gains_no_light(self):
        dark = np.full((40, 60, 3), .2, dtype=np.float32)
        np.testing.assert_allclose(ic.glow(dark, ic.GLOW_LEVELS["G1"]), dark, atol=1e-6)

    @staticmethod
    def light_scene():
        # Radii scale with the long side, so use a photo-like size with one small bright light.
        image = np.full((400, 600, 3), .05, dtype=np.float32)
        image[196:204, 296:304] = 3.
        return image

    def test_bright_source_spreads_without_dark_halo(self):
        out = ic.glow(self.light_scene(), ic.GLOW_LEVELS["G1"])
        self.assertGreater(float(out[200, 315].mean()), .05 + 1e-3)
        self.assertGreaterEqual(float(out.min()), .05 - 1e-6)  # no ringing below the background

    def test_halation_is_warm(self):
        out = ic.glow(self.light_scene(), ic.GLOW_LEVELS["G1"])[200, 306] - .05  # halation hugs the edge
        self.assertGreater(out[0], out[2])

    def test_diffusion_never_goes_negative_on_saturated_pixels(self):
        image = np.full((40, 60, 3), .05, dtype=np.float32)
        image[10:30, 20:40] = [4., .2, 4.]  # far above 1
        out = ic.glow(image, ic.GLOW_LEVELS["G2"])
        self.assertGreaterEqual(float(out.min()), 0.)


class GrainTests(unittest.TestCase):
    def test_none_is_identity(self):
        encoded = np.full((10, 10, 3), 128, dtype=np.uint8)
        self.assertIs(ic.grain(encoded, None), encoded)

    def test_mean_is_preserved_and_seed_reproducible(self):
        encoded = np.full((200, 300, 3), 128, dtype=np.uint8)
        a = ic.grain(encoded, ic.GRAIN_LEVELS["N2"])
        b = ic.grain(encoded, ic.GRAIN_LEVELS["N2"])
        np.testing.assert_array_equal(a, b)
        self.assertLess(abs(float(a.mean()) - 128), 1.)
        self.assertGreater(float(a.std()), 2.)

    def test_black_and_white_stay_clean(self):
        for level in (0, 255):
            encoded = np.full((50, 50, 3), level, dtype=np.uint8)
            np.testing.assert_array_equal(ic.grain(encoded, ic.GRAIN_LEVELS["N2"]), encoded)


if __name__ == "__main__":
    unittest.main()
