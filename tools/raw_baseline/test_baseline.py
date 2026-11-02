"""Synthetic contract tests; no real RAW/camera quality assertions."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image, ImageCms

import baseline as b


def tiff_identity(make="Google", model="Pixel 9a", endian="<"):
    # Metadata-only synthetic TIFF: deliberately not a decodable camera DNG.
    strings = [(271, make.encode("ascii") + b"\0"), (272, model.encode("ascii") + b"\0")]
    start = 8 + 2 + 3 * 12 + 4
    records = []
    payload = b""
    for tag, value in strings:
        location = value.ljust(4, b"\0") if len(value) <= 4 else struct.pack(endian + "I", start + len(payload))
        records.append(struct.pack(endian + "HHI", tag, 2, len(value)) + location)
        if len(value) > 4:
            payload += value
    records.append(struct.pack(endian + "HHI", 50706, 1, 4) + bytes([1, 4, 0, 0]))
    return (b"II" if endian == "<" else b"MM") + struct.pack(endian + "HIH", 42, 8, 3) + b"".join(records) + bytes(4) + payload


class BaselineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name).resolve()
        self.root = self.base / "authorized"
        self.root.mkdir()
        self.private = patch.object(b, "PRIVATE", self.base / ".private")
        self.private.start()

    def tearDown(self):
        self.private.stop()
        self.temp.cleanup()

    def test_inventory_is_bounded_and_does_not_modify_sources(self):
        source = self.root / "sample.DNG"
        source.write_bytes(b"not real")
        (self.root / "notes.txt").write_text("excluded")
        outside = self.base / "outside.dng"
        outside.write_bytes(b"outside")
        before = b.digest(source)
        self.assertEqual(b.inventory(self.root)["inputs"], [{"relative": "sample.DNG", "bytes": 8}])
        self.assertEqual(before, b.digest(source))
        with self.assertRaisesRegex(b.BaselineError, "INPUT_OUTSIDE_ROOT"):
            b.source_path(self.root, "../outside.dng")
        with self.assertRaisesRegex(b.BaselineError, "INPUT_OUTSIDE_ROOT"):
            b.source_path(self.root, str(outside))

    def test_inventory_does_not_traverse_reparse_entries(self):
        blocked = self.root / "private-link"
        blocked.mkdir()
        (blocked / "hidden.dng").write_bytes(b"no follow")
        original = b.linked
        with patch.object(b, "linked", side_effect=lambda p: p == blocked or original(p)):
            result = b.inventory(self.root)
            self.assertEqual(result["dng_count"], 0)
            self.assertEqual(result["skipped_links"], 1)
            with self.assertRaisesRegex(b.BaselineError, "LINK_NOT_ALLOWED"):
                b.source_path(self.root, "private-link/hidden.dng")

    def test_known_camera_identity_both_byte_orders(self):
        for endian in ("<", ">"):
            path = self.root / "metadata.dng"
            path.write_bytes(tiff_identity(endian=endian))
            identity = b.camera_identity(path)
            b.validate_profile(identity, "pixel9a")
            with self.assertRaisesRegex(b.BaselineError, "CAMERA_PROFILE_MISMATCH"):
                b.validate_profile(identity, "sigmafp")
        path.write_bytes(tiff_identity("SIGMA", "fp"))
        b.validate_profile(b.camera_identity(path), "sigmafp")
        path.write_bytes(tiff_identity("SIGMA", "fp L"))
        with self.assertRaises(b.BaselineError):
            b.validate_profile(b.camera_identity(path), "sigmafp")

    def test_sigma_fp_official_model_alias_and_fp_l_rejected(self):
        path = self.root / "metadata.dng"
        for model in ("fp", "SIGMA fp"):
            path.write_bytes(tiff_identity("SIGMA", model))
            b.validate_profile(b.camera_identity(path), "sigmafp")
        for make, model in (("SIGMA", "fp L"), ("SIGMA", "SIGMA fp L"),
                            ("Google", "SIGMA fp"), ("SIGMA", "fp2")):
            path.write_bytes(tiff_identity(make, model))
            with self.assertRaisesRegex(b.BaselineError, "CAMERA_PROFILE_MISMATCH"):
                b.validate_profile(b.camera_identity(path), "sigmafp")

    def test_malformed_header_and_offset_are_rejected(self):
        path = self.root / "broken.dng"
        for value in (b"", b"not a tiff", b"II" + struct.pack("<HI", 42, 0xFFFFFFFE)):
            path.write_bytes(value)
            with self.assertRaises(b.BaselineError):
                b.camera_identity(path)
        data = bytearray(tiff_identity())
        data[18:22] = struct.pack("<I", 0xFFFFFFFE)
        path.write_bytes(data)
        with self.assertRaisesRegex(b.BaselineError, "INVALID_DNG_HEADER"):
            b.camera_identity(path)

    def test_camera_wb_never_silently_falls_back(self):
        for camera in (None, [0, 1, 1, 0], [1, float("nan"), 1, 0]):
            with self.assertRaisesRegex(b.BaselineError, "CAMERA_WB_UNAVAILABLE"):
                b.validate_wb("camera", None, camera)
        b.validate_wb("camera", None, [2, 1, 1.5, 0])
        b.validate_wb("daylight", None)
        for user in (None, [1, 1, 1], [1, 1, float("inf"), 1]):
            with self.assertRaisesRegex(b.BaselineError, "INVALID_WB"):
                b.validate_wb("user", user)

    def test_explicit_libraw_settings_and_source_unchanged(self):
        path = self.root / "metadata-only.dng"
        path.write_bytes(tiff_identity())
        from unittest.mock import MagicMock
        raw = MagicMock()
        raw.sizes.width, raw.sizes.height = 96, 64
        raw.camera_whitebalance = [2, 1, 1.5, 0]
        raw.daylight_whitebalance = [2.1, 1, 1.4, 0]
        raw.black_level_per_channel = [64] * 4
        raw.white_level = 4095
        raw.camera_white_level_per_channel = None
        raw.postprocess.return_value = b.synthetic()
        before = b.digest(path)
        with patch.object(b.rawpy, "imread") as reader:
            reader.return_value.__enter__.return_value = raw
            rgb, observed = b.decode(path, "pixel9a", "camera", None)
        params = raw.postprocess.call_args.kwargs
        self.assertEqual(params["gamma"], (1, 1))
        self.assertTrue(params["no_auto_bright"])
        self.assertFalse(params["no_auto_scale"])
        self.assertEqual(params["adjust_maximum_thr"], 0)
        self.assertEqual(params["bright"], 1)
        self.assertEqual(params["exp_shift"], 1)
        self.assertEqual(params["output_bps"], 16)
        self.assertTrue(params["use_camera_wb"])
        self.assertFalse(params["use_auto_wb"])
        self.assertEqual(observed["white_level"], 4095)
        np.testing.assert_array_equal(rgb, b.synthetic())
        self.assertEqual(before, b.digest(path))

    def test_render_transfer_and_tone_chroma(self):
        values = np.array([[[0., .0031308, 1.]]])
        np.testing.assert_array_equal(b.display_pixels(values), [[[0, 10, 255]]])
        values = np.array([[[.1, .2, .3]]])
        altered = b.tone_only(values, .8)
        self.assertAlmostEqual(altered[0, 0, 0] / altered[0, 0, 1], .5)
        np.testing.assert_allclose(b.tone_only(values, 1), values)
        self.assertTrue(np.isfinite(b.tone_only(np.zeros((1, 1, 3)), .8)).all())
        for strength in (0, float("nan"), float("inf"), 4.1):
            with self.assertRaises(b.BaselineError):
                b.tone_only(values, strength)

    def test_synthetic_replay_artifacts_and_privacy(self):
        first = b.synthetic_run("first")
        second = b.replay(None, "first", "second")
        self.assertEqual(first["artifacts"], second["artifacts"])
        self.assertEqual(first["source"]["kind"], "synthetic")
        self.assertEqual(first["stage_clear"], "not_implemented")
        path = b.PRIVATE / "runs" / "first"
        self.assertNotIn(str(self.base), (path / "manifest.json").read_text())
        decoded = np.load(path / "baseline-linear-srgb16.npy", allow_pickle=False)
        np.testing.assert_array_equal(decoded, b.synthetic())
        with Image.open(path / "baseline.jpg") as image:
            self.assertFalse(image.getexif())
            icc = image.info["icc_profile"]
            self.assertEqual(struct.unpack(">6H", icc[24:36]), (2000, 1, 1, 0, 0, 0))
            self.assertIn("sRGB", ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc))))

    def test_replay_environment_and_hash_mismatch_publish_nothing(self):
        b.synthetic_run("first")
        path = b.PRIVATE / "runs" / "first" / "manifest.json"
        data = json.loads(path.read_text())
        data["environment"]["rawpy"] = "changed"
        b.json_write(path, data)
        with self.assertRaisesRegex(b.BaselineError, "MANIFEST_ENVIRONMENT_MISMATCH"):
            b.replay(None, "first", "no-publish")
        data["environment"] = b.versions()
        data["artifacts"]["baseline.jpg"] = "0" * 64
        b.json_write(path, data)
        with self.assertRaisesRegex(b.BaselineError, "REPLAY_OUTPUT_MISMATCH"):
            b.replay(None, "first", "no-publish")
        self.assertFalse((path.parent.parent / "no-publish").exists())
        self.assertFalse(list(path.parent.parent.glob("partial-*")))

    def test_no_overwrite_and_run_path_escape(self):
        b.synthetic_run("first")
        path = b.PRIVATE / "runs" / "first" / "baseline.jpg"
        before = b.digest(path)
        with self.assertRaisesRegex(b.BaselineError, "OUTPUT_EXISTS"):
            b.synthetic_run("first")
        self.assertEqual(before, b.digest(path))
        for run in ("..", "x/y", "C:\\outside", "", "a" * 65):
            with self.assertRaisesRegex(b.BaselineError, "INVALID_RUN_NAME"):
                b.synthetic_run(run)

    def test_output_failure_does_not_publish_success(self):
        with patch.object(Image.Image, "save", side_effect=OSError("sensitive path")):
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(b.main(["synthetic", "--run", "fail"]), 2)
            self.assertEqual(out.getvalue(), "")
            self.assertEqual(err.getvalue(), "ERROR IO_OR_INVALID_DATA\n")
        self.assertFalse((b.PRIVATE / "runs" / "fail").exists())
        self.assertFalse(list((b.PRIVATE / "runs").glob("partial-*")))

    def test_cli_errors_do_not_echo_private_arguments(self):
        for args in (["develop", "--root", str(self.root)], ["synthetic", "--run", "secret/path"],
                     ["synthetic", "--run", "x", "--tone-strength", "private-name"]):
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(b.main(args), 2)
            self.assertEqual(out.getvalue(), "")
            self.assertNotIn("private-name", err.getvalue())
            self.assertNotIn(str(self.root), err.getvalue())
            self.assertNotIn("secret/path", err.getvalue())


if __name__ == "__main__":
    unittest.main()
