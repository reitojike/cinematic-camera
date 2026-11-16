import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from private_manifest import validate, GEOMETRY


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.identity = "evaluation-0123456789abcdef"
        self.pixels = bytes([1, 2, 3, 255] * 4)
        self.data = {"schema": 1, "geometry": GEOMETRY, "revision": "a" * 64, "samples": [
            {"id": self.identity, "width": 2, "height": 2, "sha256": hashlib.sha256(self.pixels).hexdigest()}]}
        (self.root / (self.identity + ".rgba")).write_bytes(self.pixels)

    def run_bundle(self):
        (self.root / "manifest.json").write_text(json.dumps(self.data))
        # Workspace runner's /tmp is itself a checkout. Test the manifest contract separately;
        # test_evaluate covers the unchanged real outside_git guard.
        with patch("private_manifest.outside_git", side_effect=lambda p: p.resolve()):
            return validate(self.root)

    def test_nonobjects_rejected(self):
        self.data = []
        with self.assertRaises(ValueError): self.run_bundle()
        self.data = {"schema": 1, "geometry": GEOMETRY, "revision": "a" * 64, "samples": [None]}
        with self.assertRaises(ValueError): self.run_bundle()

    def test_nonregular_input_rejected(self):
        image = self.root / (self.identity + ".rgba")
        image.unlink()
        image.mkdir()
        with self.assertRaises(ValueError): self.run_bundle()

    def test_valid(self):
        self.assertEqual(self.run_bundle()["samples"], 1)

    def test_duplicate_and_named_ids_rejected(self):
        self.data["samples"] *= 2
        with self.assertRaises(ValueError): self.run_bundle()
        self.data["samples"] = [{**self.data["samples"][0], "id": "photo-name"}]
        with self.assertRaises(ValueError): self.run_bundle()

    def test_dimensions_and_revision_rejected(self):
        for width in (True, 0, -1, 4194305):
            self.data["samples"][0]["width"] = width
            with self.assertRaises(ValueError): self.run_bundle()
        self.data["samples"][0]["width"] = 2
        self.data["revision"] = "unversioned"
        with self.assertRaises(ValueError): self.run_bundle()

    def test_hash_length_and_alpha_rejected(self):
        for pixels in (self.pixels[:-1], bytes([1, 2, 3, 0] * 4), bytes([5, 2, 3, 255] * 4)):
            (self.root / (self.identity + ".rgba")).write_bytes(pixels)
            with self.assertRaises(ValueError): self.run_bundle()

    def test_symlink_rejected(self):
        image = self.root / (self.identity + ".rgba")
        image.rename(self.root / "other.rgba")
        image.symlink_to(self.root / "other.rgba")
        with self.assertRaises(ValueError): self.run_bundle()

    def test_real_checkout_is_rejected(self):
        (self.root / ".git").write_text("gitdir: external")
        with self.assertRaises(ValueError): validate(self.root)

    def test_same_pixels_under_different_ids_rejected(self):
        second = "evaluation-fedcba9876543210"
        (self.root / (second + ".rgba")).write_bytes(self.pixels)
        self.data["samples"].append({**self.data["samples"][0], "id": second})
        with self.assertRaisesRegex(ValueError, "duplicate pixel content"):
            self.run_bundle()

    def test_distinct_verified_pixels_remain_valid(self):
        second = "evaluation-fedcba9876543210"
        pixels = bytes([4, 5, 6, 255] * 4)
        (self.root / (second + ".rgba")).write_bytes(pixels)
        self.data["samples"].append({**self.data["samples"][0], "id": second,
                                     "sha256": hashlib.sha256(pixels).hexdigest()})
        self.assertEqual(self.run_bundle()["samples"], 2)
