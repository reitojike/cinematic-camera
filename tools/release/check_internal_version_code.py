"""Check published and draft fixed-signer release history before signing.

The historical body and asset are repository-controlled metadata, not signed
statements. Asset digests bind downloaded bytes to the GitHub release snapshot;
the APK digest cross-check binds the asset's record to that same snapshot.
The release workflow independently verifies each newly built APK's signature.
"""
import hashlib
import json
import os
import re
import subprocess
import sys

APK = "cinematic-camera-internal.apk"
ASSET = "RELEASE_METADATA.md"
PREFIX = "<!-- cinematic-internal-release:"
MARKER = re.compile(
    rf"^{re.escape(PREFIX)} versionCode=([1-9][0-9]{{0,9}}) "
    r"certSha256=([0-9a-fA-F]{64}) -->$", re.MULTILINE
)
MAX_CODE = 2_100_000_000


def require(ok, message):
    if not ok:
        raise ValueError(message)


def marker(content, pin, optional=False):
    count = content.count(PREFIX)
    if not count and optional:
        return None
    matches = MARKER.findall(content.replace("\r\n", "\n"))
    require(count == len(matches) == 1, "missing, invalid or duplicate metadata marker")
    code, cert = matches[0]
    require(cert.upper() == pin, "unexpected historical signer")
    require(int(code) <= MAX_CODE, "historical versionCode outside Android range")
    return int(code)


def asset_metadata(release, asset, apk, pin, download):
    require(apk is not None, "metadata asset without fixed-signer APK")
    require(type(asset["id"]) is int and asset["id"] > 0, "invalid metadata asset ID")
    require(asset["state"] == "uploaded", "metadata asset is not uploaded")
    require(type(asset["size"]) is int and 0 < asset["size"] <= 65_536,
            "invalid metadata asset size")
    content = download(asset["id"])
    digest = "sha256:" + hashlib.sha256(content).hexdigest()
    require(len(content) == asset["size"] and digest == asset["digest"],
            "metadata asset bytes differ from GitHub listing")
    content = content.decode("utf-8").replace("\r\n", "\n")
    code = marker(content, pin)
    version = re.findall(r"^- versionName: `([^`]+)`; versionCode: `([0-9]+)`$", content, re.M)
    require(version == [(release["tag_name"], str(code))], "metadata version/tag mismatch")
    hashes = re.findall(r"^([0-9a-f]{64})  cinematic-camera-internal\.apk$", content, re.M)
    require(len(hashes) == 1 and apk["state"] == "uploaded"
            and apk["digest"] == "sha256:" + hashes[0], "metadata/APK digest mismatch")
    return code


def validate_history(pages, candidate, pin, download):
    require(isinstance(pages, list) and bool(pages), "missing release listing")
    require(isinstance(candidate, str) and re.fullmatch(r"[1-9][0-9]{0,9}", candidate)
            and int(candidate) <= MAX_CODE, "invalid candidate versionCode")
    require(re.fullmatch(r"[0-9a-fA-F]{64}", pin), "invalid signer pin")
    pin = pin.upper()
    codes, release_ids = set(), set()
    for page in pages:
        require(isinstance(page, list), "invalid release listing page")
        for release in page:
            release_id = release["id"]
            require(type(release_id) is int and release_id > 0 and release_id not in release_ids,
                    "invalid or duplicate release ID")
            release_ids.add(release_id)
            body = release["body"] if release["body"] is not None else ""
            assets = release["assets"]
            require(isinstance(assets, list), "invalid release asset listing")
            apks = [a for a in assets if a["name"] == APK]
            metadata = [a for a in assets if a["name"] == ASSET]
            if not (apks or metadata or PREFIX in body):
                continue
            try:
                require(len(apks) <= 1 and len(metadata) <= 1, "duplicate release asset")
                body_code = marker(body, pin, optional=bool(metadata))
                asset_code = (asset_metadata(release, metadata[0], apks[0] if apks else None,
                                             pin, download) if metadata else None)
                require(body_code is None or asset_code is None or body_code == asset_code,
                        "body/asset versionCode mismatch")
                code = asset_code if asset_code is not None else body_code
                require(code not in codes, "duplicate historical versionCode")
                codes.add(code)
            except (ValueError, KeyError, TypeError) as exc:
                raise ValueError(f"Release ID {release_id}: {exc}") from exc
    require(int(candidate) > max(codes, default=0),
            "candidate versionCode must exceed all historical releases; start a new run")


def main():
    repo = os.environ["GITHUB_REPOSITORY"]

    def download(asset_id):
        # Construct the authenticated endpoint from local repo + validated numeric ID.
        return subprocess.check_output([
            "gh", "api", "-H", "Accept: application/octet-stream",
            f"repos/{repo}/releases/assets/{asset_id}",
        ])

    try:
        with open(sys.argv[1], encoding="utf-8") as file:
            validate_history(json.load(file), os.environ["RELEASE_VERSION_CODE"],
                             os.environ["EXPECTED_CERT_SHA256"], download)
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as exc:
        print(f"Internal release version check failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
