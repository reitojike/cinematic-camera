"""Validate an already-authorized, local evaluation bundle. No network or device IO."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from evaluate import outside_git

GEOMETRY = "upright-srgb-argb8888-v1"


def validate(root):
    root = outside_git(Path(root))
    manifest = root / "manifest.json"
    if manifest.is_symlink() or not manifest.is_file():
        raise ValueError("manifest must be a direct regular file")
    with manifest.open("rb") as stream:
        raw = stream.read(65537)
    if len(raw) > 65536:
        raise ValueError("manifest too large")
    data = json.loads(raw)
    if not isinstance(data, dict) or type(data.get("schema")) is not int or data.get("schema") != 1 or data.get("geometry") != GEOMETRY:
        raise ValueError("unsupported manifest")
    revision = data.get("revision", "")
    if not re.fullmatch(r"[0-9a-f]{64}", revision):
        raise ValueError("preprocessing revision hash required")
    samples = data.get("samples", [])
    if not isinstance(samples, list) or not 1 <= len(samples) <= 64:
        raise ValueError("sample count outside 1..64")
    seen = set()
    seen_pixels = set()
    for sample in samples:
        if not isinstance(sample, dict):
            raise ValueError("sample must be an object")
        identity = sample.get("id", "")
        if not re.fullmatch(r"evaluation-[0-9a-f]{16}", identity) or identity in seen:
            raise ValueError("invalid or duplicate opaque sample ID")
        seen.add(identity)
        width, height = sample.get("width"), sample.get("height")
        if (type(width) is not int or type(height) is not int or width <= 0 or height <= 0
                or width * height > 4194304):
            raise ValueError("invalid dimensions")
        path = root / (identity + ".rgba")
        if path.is_symlink() or not path.is_file() or path.resolve().parent != root:
            raise ValueError("input must be a direct regular file")
        with path.open("rb") as stream:
            pixels = stream.read(width * height * 4 + 1)
        if len(pixels) != width * height * 4 or any(a != 255 for a in pixels[3::4]):
            raise ValueError("opaque RGBA8888 byte count required")
        pixel_hash = hashlib.sha256(pixels).hexdigest()
        if pixel_hash != sample.get("sha256"):
            raise ValueError("input hash mismatch")
        if pixel_hash in seen_pixels:
            raise ValueError("duplicate pixel content")
        seen_pixels.add(pixel_hash)
    return {"schema": 1, "manifest_sha256": hashlib.sha256(raw).hexdigest(), "samples": len(samples)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.bundle)))
    except (ValueError, OSError, KeyError, TypeError):
        raise SystemExit("Invalid evaluation bundle; inspect locally (paths omitted)")
