"""Synthetic tests for the generalisation-check photo selection; no real photos."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

import r8_exposure as x
import r8_generalize as g


class PickTests(unittest.TestCase):
    def test_spreads_over_dates_and_skips_tuned(self):
        # 20 photos on a busy date (one excluded as tuned) and 5 on a quiet date.
        paths = [Path(f"{i}-IMG{i:04d}.jpg") for i in range(1, 26)]
        dates = {p: ("2026:01:01" if i < 20 else "2026:02:02") for i, p in enumerate(paths)}
        with patch.object(g, "capture_date", side_effect=lambda p: dates[p]):
            picked = g.pick(paths, exclude={"img0001"})
        self.assertEqual(len(picked), 3)  # busy date -> 2, quiet date -> 1
        self.assertEqual(sum(dates[p] == "2026:02:02" for p in picked), 1)
        self.assertNotIn(Path("1-IMG0001.jpg"), picked)

    def test_stem_drops_order_prefix(self):
        self.assertEqual(g.stem(Path("38-ABCD5356.DNG")), "abcd5356")


class SkinProxyTests(unittest.TestCase):
    def scene(self, gain=1.):
        image = np.full((60, 80, 3), .02, dtype=np.float32)
        image[15:35, 30:50] = [.30, .20, .15]  # skin-like patch near the upper centre
        image[40:55, 5:20] = [.05, .08, .40]   # saturated blue light
        return image * gain

    def test_weight_sits_on_skin_not_on_blue_light(self):
        weight = x.skin_proxy_weight(self.scene())
        self.assertGreater(weight[15:35, 30:50].sum() / weight.sum(), .95)
        self.assertLess(weight[40:55, 5:20].max(), 1e-3)

    def test_weight_is_exposure_invariant(self):
        np.testing.assert_allclose(x.skin_proxy_weight(self.scene()), x.skin_proxy_weight(self.scene(4.)), atol=1e-3)

    def test_proxy_luminance_tracks_exposure(self):
        weight = x.skin_proxy_weight(self.scene())
        self.assertAlmostEqual(x.proxy_log_y(self.scene(4.), weight) - x.proxy_log_y(self.scene(), weight), 2., places=4)


class FramingTests(unittest.TestCase):
    def check(self, xmp: bytes) -> bool:
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "edit.jpg"
            path.write_bytes(b"\xff\xd8 fake jpeg " + xmp + b" \xff\xd9")
            return x.edit_keeps_full_frame(path)

    def test_uncropped_edit_is_accepted(self):
        self.assertTrue(self.check(b'<x:xmpmeta crs:HasCrop="False"></x:xmpmeta>'))

    def test_cropped_edit_is_rejected_even_with_same_aspect(self):
        self.assertFalse(self.check(b'<x:xmpmeta crs:HasCrop="True" crs:CropTop="0.1"></x:xmpmeta>'))

    def test_edit_without_crop_record_is_rejected(self):
        self.assertFalse(self.check(b"<x:xmpmeta></x:xmpmeta>"))
        self.assertFalse(self.check(b""))


if __name__ == "__main__":
    unittest.main()
