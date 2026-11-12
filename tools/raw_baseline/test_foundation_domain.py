"""Synthetic #355 domain regression; no baseline regeneration or private inputs."""
import unittest
import numpy as np
import r6_diagnose as r


class FoundationDomainTests(unittest.TestCase):
    def render(self, y, ev, luma_gain=None):
        rgb = np.repeat(np.asarray(y, dtype=np.float32)[None, :, None], 3, axis=2)
        look = dict(ev=0., contrast=1., rgb=[1., 1., 1.], saturation=1., skin_saturation=1.)
        with np.errstate(divide="raise", invalid="raise", over="raise"):
            return r.foundation(rgb, np.zeros(rgb.shape[:2], dtype=np.float32), ev, look,
                                luma_gain=luma_gain)

    def test_endpoints_hdr_pole_and_monotonicity(self):
        for ev in (-2., 0., 2.):
            gain = 2. ** ev
            values = [-1., 0., .001, .25, .5, .999, 1., 1.25, 2., 4.]
            if gain < 1:
                pole = np.float32(1 / (1 - gain))
                values += [np.nextafter(pole, np.float32(0)), pole,
                           np.nextafter(pole, np.float32(np.inf))]
            values = sorted(values)
            out = self.render(values, ev)[0, :, 0]
            self.assertTrue(np.isfinite(out).all())
            self.assertTrue((np.diff(out) >= -1e-7).all())
            np.testing.assert_allclose(out[:2], 0, atol=0)
            np.testing.assert_allclose(out[np.asarray(values) >= 1], 1, atol=1e-6)

    def test_interior_retains_original_expression(self):
        values = np.linspace(.001, .999, 101, dtype=np.float32)
        for ev in (-2., 0., 2.):
            rgb = np.repeat(values[None, :, None], 3, axis=2)
            y = rgb @ r.LUMA
            gain = np.exp2(ev + np.zeros_like(y))
            expected = y * gain / (1 + (gain - 1) * y)
            np.testing.assert_allclose(self.render(values, ev)[..., 0], expected, rtol=2e-7, atol=1e-7)

    def test_historical_luma_gain_is_after_bounded_curve(self):
        # Historical diagnostic is intentionally affected for HDR even before its old pole.
        out = self.render([.5, 1.25, 2.], -2., np.full((1, 3), .5, dtype=np.float32))
        np.testing.assert_allclose(out[0, :, 0], [.1, .5, .5], atol=1e-6)


if __name__ == "__main__":
    unittest.main()
