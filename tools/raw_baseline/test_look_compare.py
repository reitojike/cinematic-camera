import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageCms

import baseline as b
import look_compare as c


def recipe():
    return {"schema": 1, "scenes": [{"id": "sample", "source": "sample.dng", "edited": None,
            "profile": "sigmafp", "wb": "camera", "base_ev": 1., "skin_ev": .2, "note": "Synthetic only", "skin": [[.5, .5, .3, .4]],
            "focus": {key: [.2, .2, .8, .8] for key in ("skin", "costume", "light")}}]}


class LookTests(unittest.TestCase):
    def test_fixed_baseline_matches_existing_float64_transfer(self):
        rng = np.random.default_rng(31)
        rgb = rng.integers(0, 65536, (259, 101, 3), dtype=np.uint16)
        actual, _ = c.render(rgb, recipe()["scenes"][0], None)
        np.testing.assert_array_equal(actual, b.display_pixels(rgb.astype(np.float64) / 65535))

    def test_shadow_gain_monotonic_curve_and_neutral_endpoints(self):
        linear = np.broadcast_to(np.linspace(0, 1, 1001)[:, None], (1001, 3)).copy()
        look = dict(c.LOOKS["clear"], local_ev=0, rgb=[1, 1, 1], saturation=1, skin_saturation=1, contrast=1)
        output = c.transform(linear, np.zeros(1001), 1, look)
        self.assertTrue(np.all(np.diff(output[:, 0]) > 0))
        np.testing.assert_allclose(output[[0, -1]], [[0, 0, 0], [1, 1, 1]], atol=1e-6)
        self.assertAlmostEqual(output[1, 0] / .001, 2, delta=.003)
        np.testing.assert_allclose(c.transform(linear, np.zeros(1001), 0, look), linear)

    def test_skin_feather_is_continuous_and_chunk_invariant(self):
        whole = c.skin_mask(101, 103, 0, 103, [[.5, .5, .3, .4]])
        parts = np.concatenate([c.skin_mask(101, 103, 0, 37, [[.5, .5, .3, .4]]),
                                c.skin_mask(101, 103, 37, 103, [[.5, .5, .3, .4]])])
        np.testing.assert_array_equal(whole, parts)
        self.assertEqual(whole[51, 50], 1)
        self.assertEqual(whole[0, 0], 0)
        self.assertLess(np.max(np.abs(np.diff(whole, axis=1))), .12)

    def test_contrast_preserves_neutral_highlights_and_anchor(self):
        linear = np.broadcast_to(np.linspace(0, 1, 1001)[:, None], (1001, 3)).copy()
        for settings in c.LOOKS.values():
            look = dict(settings, ev=0, local_ev=0, rgb=[1, 1, 1], saturation=1, skin_saturation=1)
            output = c.transform(linear, np.zeros(1001), 0, look)
            self.assertTrue(np.all(np.diff(output[:, 0]) > 0))
            self.assertTrue(((output >= 0) & (output <= 1)).all())
            np.testing.assert_allclose(output[[0, 180, -1]], linear[[0, 180, -1]], atol=1e-6)

    def test_four_distinct_looks_and_more_visible_than_old_tone(self):
        rgb = b.synthetic()
        scene = recipe()["scenes"][0]
        outputs = [c.render(rgb, scene, look)[0] for look in c.LOOKS.values()]
        self.assertEqual(len({im.tobytes() for im in outputs}), 4)
        neutral = np.full((1, 1, 3), .12, dtype=np.float32)
        old = b.display_pixels(b.tone_only(neutral, .8))
        new = b.display_pixels(c.transform(neutral, np.ones((1, 1)), 1, c.LOOKS["clear"]))
        self.assertGreater(float(new.mean()), float(old.mean()) + 20)

    def test_gamut_compression_finite_bounded_and_keeps_in_gamut(self):
        values = np.array([[2, .2, .1], [-.2, .4, 1.2], [0, 0, 0], [1, 1, 1], [.2, .4, .6]], dtype=np.float32)
        output = c.gamut_compress(values)
        self.assertTrue(np.isfinite(output).all())
        self.assertTrue(((output >= 0) & (output <= 1)).all())
        np.testing.assert_allclose(output[-3:], values[-3:], atol=1e-6)

    def test_recipe_rejects_nan_bad_regions_duplicates_and_unknown_fields(self):
        edits = [lambda r: r.update(schema=2), lambda r: r["scenes"][0].update(base_ev=float("nan")),
                 lambda r: r["scenes"][0].update(skin=[[.5, .5, 0, .1]]),
                 lambda r: r["scenes"].append(copy.deepcopy(r["scenes"][0])),
                 lambda r: r["scenes"][0].update(extra=True), lambda r: r["scenes"][0].update(profile="pixel9a"),
                 lambda r: r["scenes"][0]["focus"].update(skin=[.8, .2, .2, .8])]
        for edit in edits:
            data = recipe(); edit(data)
            with self.assertRaises(b.BaselineError):
                c.validate_recipe(data)

    def test_case_only_scene_id_duplicate_is_rejected_before_output(self):
        data = recipe()
        duplicate = copy.deepcopy(data["scenes"][0])
        duplicate["id"] = duplicate["id"].upper()
        data["scenes"].append(duplicate)
        with self.assertRaisesRegex(b.BaselineError, "INVALID_RECIPE"):
            c.validate_recipe(data)

    def test_prepare_replay_originals_metadata_and_atomic_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            root = temp / "inputs"; root.mkdir()
            source = root / "sample.dng"; source.write_bytes(b"synthetic test source")
            first, second = temp / "first", temp / "second"
            before = c.snapshot(source)
            with patch.object(b, "decode", return_value=(b.synthetic(), {})):
                a = c.prepare(root, recipe(), first)
                saved_recipe = json.loads((first / "recipe.json").read_text(encoding="utf-8"))
                z = c.prepare(root, saved_recipe, second)
            self.assertEqual(a["artifacts"], z["artifacts"])
            self.assertEqual(before, c.snapshot(source))
            self.assertTrue(a["sources_unchanged"])
            self.assertFalse(a["automatic_skin_detection"])
            for path in (first / "assets").glob("*.jpg"):
                with Image.open(path) as im:
                    im.load()
                    self.assertFalse(im.getexif())
                    self.assertIn("sRGB", ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(im.info["icc_profile"]))))
            with self.assertRaisesRegex(b.BaselineError, "OUTPUT_EXISTS"):
                c.prepare(root, recipe(), first)
            with patch.object(b, "decode", side_effect=b.BaselineError("RAW_DECODE_FAILED")):
                with self.assertRaises(b.BaselineError):
                    c.prepare(root, recipe(), temp / "failed")
            self.assertFalse((temp / "failed").exists())
            self.assertFalse(list(temp.glob("look-partial-*")))
            with patch.object(c, "code_hashes", side_effect=[c.LOADED_CODE, {}]), patch.object(b, "decode", return_value=(b.synthetic(), {})):
                with self.assertRaisesRegex(b.BaselineError, "CODE_CHANGED"):
                    c.prepare(root, recipe(), temp / "changed-code")
            self.assertFalse((temp / "changed-code").exists())
            self.assertFalse(list(temp.glob("look-partial-*")))

    def test_output_input_overlap_and_parent_symlink_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory); root = temp / "input"; root.mkdir()
            with self.assertRaisesRegex(b.BaselineError, "OUTPUT_OVERLAPS_INPUT"):
                c.output_path(root, root / "output")
            with self.assertRaises(b.BaselineError):
                c.reference_path(root, "../private.jpg")
            with patch.object(b, "linked", return_value=True):
                with self.assertRaisesRegex(b.BaselineError, "LINK_NOT_ALLOWED"):
                    c.output_path(root, temp / "output")


if __name__ == "__main__":
    unittest.main()
