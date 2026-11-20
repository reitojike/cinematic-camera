import unittest

from check_internal_version_code import marker
from compose_stable_release_notes import LIMIT, SOURCE, VERIFICATION, approved_prose, compose

TAG = "v0.1.1"
SOURCE_COMMIT = "a" * 40
CERT = "BC" * 32
PROSE = "# Cinematic Camera v0.1.1\n\n日本語と `$HOME`、\"引用\"、$(something)\n\n- 2行目\n".encode()
BINDING = SOURCE + SOURCE_COMMIT.encode() + b" -->\n"
NOTES = BINDING + PROSE
METADATA = (
    f"<!-- cinematic-internal-release: versionCode=42 certSha256={CERT} -->\n\n"
    f"- source main: `{SOURCE_COMMIT}`\n"
    f"- versionName: `{TAG}`; versionCode: `42`\n"
    + "c" * 64 + "  cinematic-camera-internal.apk\n"
).encode()


class StableReleaseNotesTest(unittest.TestCase):
    def test_exact_multiline_prose_precedes_original_verification(self):
        combined = compose(NOTES, TAG, SOURCE_COMMIT, METADATA)
        self.assertEqual(combined, PROSE + VERIFICATION + METADATA)
        self.assertNotIn(SOURCE, combined)
        self.assertEqual(marker(combined.decode(), CERT), 42)

    def test_missing_or_wrong_app_source_cannot_publish(self):
        for raw in (b"", PROSE, BINDING, NOTES[:LIMIT] + b"x" * LIMIT):
            with self.subTest(raw_bytes=len(raw)), self.assertRaises(ValueError):
                approved_prose(raw, TAG, SOURCE_COMMIT)
        with self.assertRaises(ValueError):
            approved_prose(NOTES, TAG, "b" * 40)
        with self.assertRaises(ValueError):
            approved_prose(NOTES, "v0.1.2", SOURCE_COMMIT)

    def test_reserved_marker_or_invalid_encoding_cannot_publish(self):
        for tail in (METADATA, b"CINEMATIC-INTERNAL-RELEASE\n", BINDING, b"\xff\n", b"x\r\n", b"\x00\n"):
            with self.subTest(tail=tail), self.assertRaises(ValueError):
                compose(NOTES + tail, TAG, SOURCE_COMMIT, METADATA)
        for bad in (b"", METADATA + METADATA):
            with self.assertRaises(ValueError):
                compose(NOTES, TAG, SOURCE_COMMIT, bad)


if __name__ == "__main__":
    unittest.main()
