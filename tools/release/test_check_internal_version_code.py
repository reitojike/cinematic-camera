import copy
import hashlib
import unittest

from check_internal_version_code import APK, ASSET, validate_history

PIN = "A" * 64
APK_HASH = "b" * 64


def marker(code, pin=PIN):
    return f"<!-- cinematic-internal-release: versionCode={code} certSha256={pin} -->"


def metadata(code=25, tag="v0.1.0"):
    return (f"# Generated notes\n\n{marker(code)}\n"
            f"- versionName: `{tag}`; versionCode: `{code}`\n"
            f"{APK_HASH}  {APK}\n").encode()


def asset(contents):
    return {"id": 42, "name": ASSET, "state": "uploaded", "size": len(contents),
            "digest": "sha256:" + hashlib.sha256(contents).hexdigest()}


def release(release_id, code, contents=None, body=None, draft=False):
    assets = [{"name": APK, "state": "uploaded", "digest": "sha256:" + APK_HASH}]
    if contents is not None:
        assets.append(asset(contents))
    return {"id": release_id, "tag_name": "v0.1.0", "assets": assets, "draft": draft,
            "body": marker(code) if body is None else body}


class GuardTest(unittest.TestCase):
    def test_mixed_pages_and_drafts(self):
        data = metadata()
        old = release(1, 24)
        current = release(2, 25, data, body="# Approved notes", draft=True)
        unrelated = {"id": 3, "body": None, "assets": []}
        validate_history([[old, unrelated], [current]], "26", PIN.lower(), lambda _: data)
        for code in ("24", "25"):
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "exceed"):
                validate_history([[old], [current]], code, PIN, lambda _: data)

    def test_body_and_asset_must_agree(self):
        data = metadata()
        validate_history([[release(1, 25, data)]], "26", PIN, lambda _: data)
        for body in (marker(24), marker(25, "B" * 64), marker(25) + "\n" + marker(25),
                     "<!-- cinematic-internal-release: invalid -->"):
            with self.subTest(body=body), self.assertRaises(ValueError):
                validate_history([[release(1, 25, data, body)]], "26", PIN, lambda _: data)

    def test_missing_invalid_and_duplicate_history(self):
        for body in ("", marker("01"), marker(2_100_000_001),
                     f"not a full line {marker(25)}", marker(25, "B" * 64)):
            with self.subTest(body=body), self.assertRaises(ValueError):
                validate_history([[release(1, 25, body=body)]], "26", PIN, lambda _: b"")
        for history in ([[release(1, 25)], [release(2, 25)]],
                        [[release(1, 24)], [release(1, 25)]],
                        [{"invalid": "pagination shape"}], []):
            with self.subTest(history=history), self.assertRaises(ValueError):
                validate_history(history, "26", PIN, lambda _: b"")

    def test_bad_asset_fails_even_with_valid_body(self):
        data = metadata()
        good = release(1, 25, data)
        for index, key, value in (
            (1, "digest", "sha256:" + "c" * 64), (1, "size", 0), (1, "size", 65_537),
            (1, "id", "../elsewhere"), (1, "state", "starter"),
            (0, "digest", "sha256:" + "a" * 64), (0, "state", "starter"),
        ):
            bad = copy.deepcopy(good)
            bad["assets"][index][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate_history([[bad]], "26", PIN, lambda _: data)
        for bad_assets in ([good["assets"][1]], good["assets"] * 2):
            bad = dict(good, assets=bad_assets)
            with self.assertRaises(ValueError):
                validate_history([[bad]], "26", PIN, lambda _: data)
        for bad_text in (b"", b"\xff", metadata(tag="v0.1.1"), data + marker(25).encode()):
            with self.subTest(data=bad_text), self.assertRaises(ValueError):
                validate_history([[release(1, 25, bad_text)]], "26", PIN, lambda _: bad_text)

    def test_bad_candidate(self):
        for value in ("0", "01", "-1", "2.0", "2100000001", "9" * 5000):
            with self.subTest(value=value[:12]), self.assertRaises(ValueError):
                validate_history([[]], value, PIN, lambda _: b"")


if __name__ == "__main__":
    unittest.main()
