"""Local-only, reproducible RAW baseline PoC. No camera or network operations."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import stat
import struct
import sys
import tempfile
from typing import Any

import numpy as np
from PIL import Image, ImageCms, __version__ as pillow_version
import rawpy

PRIVATE = Path(__file__).resolve().parent / ".private"
SCHEMA = 1
MAX_FILE_BYTES = 512 * 1024 * 1024
MAX_PIXELS = 80_000_000
ARTIFACTS = ("baseline-linear-srgb16.npy", "baseline.jpg", "tone-only.jpg")
PROFILES = {"pixel9a": ("google", "pixel 9a"), "sigmafp": ("sigma", "fp")}
SETTINGS = {
    "demosaic": "AHD", "output_color": "linear-sRGB-primaries-D65",
    "output_bps": 16, "gamma": [1.0, 1.0], "no_auto_bright": True,
    "no_auto_scale": False, "bright": 1.0, "exp_shift": 1.0,
    "exp_preserve_highlights": 0.0, "adjust_maximum_thr": 0.0,
    "highlight_mode": "Clip", "black_white": "LibRaw metadata; no user overrides",
    "noise_reduction": "off", "orientation": "RAW metadata via LibRaw",
    "display_transfer": "IEC sRGB piecewise", "jpeg_quality": 95,
    "jpeg_subsampling": 0, "tone": "luminance-rational-ratio-before-clamp",
}


class BaselineError(Exception):
    """Stable error codes deliberately contain no source path or metadata."""


def linked(path: Path) -> bool:
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)


def root_path(value: str | Path) -> Path:
    path = Path(value).absolute()
    for part in (path, *path.parents):
        if linked(part):
            raise BaselineError("LINK_NOT_ALLOWED")
    if not path.is_dir():
        raise BaselineError("ROOT_NOT_DIRECTORY")
    return path.resolve(strict=True)


def source_path(root: Path, relative: str) -> Path:
    rel = Path(relative)
    if rel.is_absolute() or rel.drive or not rel.parts or any(p in ("..", ".") for p in rel.parts):
        raise BaselineError("INPUT_OUTSIDE_ROOT")
    path = root
    for part in rel.parts:
        path = path / part
        if linked(path):
            raise BaselineError("LINK_NOT_ALLOWED")
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root) or not resolved.is_file():
        raise BaselineError("INPUT_OUTSIDE_ROOT")
    if resolved.suffix.lower() != ".dng":
        raise BaselineError("DNG_REQUIRED")
    if not 0 < resolved.stat().st_size <= MAX_FILE_BYTES:
        raise BaselineError("INPUT_SIZE_LIMIT")
    return resolved


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def json_write(path: Path, value: Any) -> None:
    path.write_text(json.dumps(value, sort_keys=True, indent=2, ensure_ascii=True, allow_nan=False) + "\n", encoding="utf-8")


def private_root() -> Path:
    # Never follow a replaced output directory or a junction into another tree.
    parent = root_path(PRIVATE.parent)
    if PRIVATE.exists() or PRIVATE.is_symlink():
        if linked(PRIVATE) or not PRIVATE.is_dir():
            raise BaselineError("UNSAFE_OUTPUT")
    PRIVATE.mkdir(exist_ok=True)
    path = PRIVATE.resolve(strict=True)
    if path.parent != parent:
        raise BaselineError("UNSAFE_OUTPUT")
    return path


def inventory(root: Path) -> dict[str, Any]:
    pending = [root]
    entries = []
    skipped = 0
    while pending:
        directory = pending.pop()
        for path in sorted(directory.iterdir(), key=lambda p: p.name):
            if linked(path):
                skipped += 1
                continue
            if not path.resolve(strict=True).is_relative_to(root):
                raise BaselineError("INPUT_OUTSIDE_ROOT")
            if path.is_dir():
                pending.append(path)
            elif path.is_file() and path.suffix.lower() == ".dng":
                entries.append({"relative": path.relative_to(root).as_posix(), "bytes": path.stat().st_size})
    entries.sort(key=lambda e: e["relative"])
    return {"schema": SCHEMA, "dng_count": len(entries), "skipped_links": skipped, "inputs": entries}


def camera_identity(path: Path) -> tuple[str, str]:
    """Read only TIFF IFD0 Make/Model/DNGVersion; never traverse EXIF/GPS/IDs."""
    size = path.stat().st_size
    with path.open("rb") as handle:
        header = handle.read(8)
        if len(header) != 8 or header[:2] not in (b"II", b"MM"):
            raise BaselineError("INVALID_DNG_HEADER")
        endian = "<" if header[:2] == b"II" else ">"
        magic, offset = struct.unpack(endian + "HI", header[2:])
        if magic != 42 or offset < 8 or offset + 2 > size:
            raise BaselineError("INVALID_DNG_HEADER")
        handle.seek(offset)
        count = struct.unpack(endian + "H", handle.read(2))[0]
        if count > 4096 or offset + 2 + count * 12 + 4 > size:
            raise BaselineError("INVALID_DNG_HEADER")
        records = handle.read(count * 12)
        tags = {}
        for index in range(count):
            record = records[index * 12:(index + 1) * 12]
            tag, kind, length = struct.unpack(endian + "HHI", record[:8])
            if tag == 50706 and kind == 1 and length == 4:
                tags[tag] = record[8:12]
            if tag not in (271, 272) or kind != 2 or not 1 <= length <= 512:
                continue
            if length <= 4:
                data = record[8:8 + length]
            else:
                position = struct.unpack(endian + "I", record[8:])[0]
                if position < 8 or position + length > size:
                    raise BaselineError("INVALID_DNG_HEADER")
                handle.seek(position)
                data = handle.read(length)
            try:
                tags[tag] = " ".join(data.rstrip(b"\0").decode("ascii", errors="strict").lower().split())
            except UnicodeError as exc:
                raise BaselineError("UNKNOWN_CAMERA") from exc
    if 50706 not in tags:
        raise BaselineError("DNG_VERSION_REQUIRED")
    if 271 not in tags or 272 not in tags:
        raise BaselineError("UNKNOWN_CAMERA")
    return tags[271], tags[272]


def validate_profile(identity: tuple[str, str], profile: str) -> None:
    if profile not in PROFILES or identity != PROFILES[profile]:
        raise BaselineError("CAMERA_PROFILE_MISMATCH")


def validate_wb(mode: str, multipliers: list[float] | None, camera: list[float] | None = None) -> None:
    if mode not in ("camera", "daylight", "user"):
        raise BaselineError("INVALID_WB")
    if mode == "user":
        if multipliers is None or len(multipliers) != 4 or any(not math.isfinite(v) or v <= 0 for v in multipliers):
            raise BaselineError("INVALID_WB")
    elif multipliers is not None:
        raise BaselineError("INVALID_WB")
    if mode == "camera" and (camera is None or len(camera) != 4 or
                             any(not math.isfinite(v) or v <= 0 for v in camera[:3]) or
                             not math.isfinite(camera[3]) or camera[3] < 0):
        raise BaselineError("CAMERA_WB_UNAVAILABLE")


def decode(path: Path, profile: str, wb: str, multipliers: list[float] | None) -> tuple[np.ndarray, dict[str, Any]]:
    validate_profile(camera_identity(path), profile)
    try:
        with rawpy.imread(str(path)) as raw:
            if raw.sizes.width * raw.sizes.height > MAX_PIXELS:
                raise BaselineError("PIXEL_LIMIT")
            camera_wb = [float(v) for v in raw.camera_whitebalance]
            daylight_wb = [float(v) for v in raw.daylight_whitebalance]
            if wb == "daylight":
                try:
                    validate_wb("camera", None, daylight_wb)
                except BaselineError as exc:
                    raise BaselineError("DAYLIGHT_WB_UNAVAILABLE") from exc
            validate_wb(wb, multipliers, camera_wb)
            observed = {
                "black_level_per_channel": [int(v) for v in raw.black_level_per_channel],
                "white_level": int(raw.white_level),
                "camera_white_level_per_channel": raw.camera_white_level_per_channel,
                "camera_wb": camera_wb, "daylight_wb": daylight_wb,
                "effective_wb": multipliers if wb == "user" else camera_wb if wb == "camera" else daylight_wb,
            }
            rgb = raw.postprocess(
                demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD,
                use_camera_wb=wb == "camera", use_auto_wb=False,
                user_wb=multipliers if wb == "user" else None,
                output_color=rawpy.ColorSpace.sRGB, output_bps=16, gamma=(1, 1),
                no_auto_bright=True, no_auto_scale=False, bright=1.0,
                exp_shift=1.0, exp_preserve_highlights=0.0, adjust_maximum_thr=0.0,
                highlight_mode=rawpy.HighlightMode.Clip,
                fbdd_noise_reduction=rawpy.FBDDNoiseReductionMode.Off,
                noise_thr=None, median_filter_passes=0,
            )
    except rawpy.LibRawError as exc:
        raise BaselineError("RAW_DECODE_FAILED") from exc
    return validate_rgb(rgb), observed


def validate_rgb(rgb: np.ndarray) -> np.ndarray:
    if rgb.dtype != np.uint16 or rgb.ndim != 3 or rgb.shape[2] != 3 or rgb.shape[0] * rgb.shape[1] > MAX_PIXELS or min(rgb.shape) < 1:
        raise BaselineError("INVALID_RGB")
    return rgb


def synthetic() -> np.ndarray:
    # Exact integer gradient, neutral wedge, and saturated patches. Not a RAW decode.
    x = np.linspace(0, 65535, 96, dtype=np.uint16)
    image = np.tile(x[None, :, None], (64, 1, 3))
    image[:16, :24] = [65535, 0, 0]
    image[:16, 24:48] = [0, 65535, 0]
    image[:16, 48:72] = [0, 0, 65535]
    image[:16, 72:] = [65535, 65535, 65535]
    return image


def tone_only(linear: np.ndarray, strength: float) -> np.ndarray:
    if not math.isfinite(strength) or not .25 <= strength <= 4:
        raise BaselineError("INVALID_TONE")
    luma = linear @ np.array([.2126, .7152, .0722])
    denominator = strength + (1 - strength) * luma
    return linear / denominator[..., None]


def display_pixels(linear: np.ndarray) -> np.ndarray:
    clipped = np.clip(linear, 0, 1)
    encoded = np.where(clipped <= .0031308, 12.92 * clipped, 1.055 * clipped ** (1 / 2.4) - .055)
    return np.rint(encoded * 255).clip(0, 255).astype(np.uint8)


def versions() -> dict[str, Any]:
    return {"python": sys.version.split()[0], "numpy": np.__version__, "Pillow": pillow_version,
            "rawpy": rawpy.__version__, "LibRaw": list(rawpy.libraw_version),
            "tool_sha256": digest(Path(__file__)),
            "rawpy_flags": dict(rawpy.flags), "platform": sys.platform}


def run_output(run: str) -> Path:
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", run):
        raise BaselineError("INVALID_RUN_NAME")
    private = private_root()
    runs = private / "runs"
    if runs.exists() or runs.is_symlink():
        if linked(runs) or not runs.is_dir():
            raise BaselineError("UNSAFE_OUTPUT")
    runs.mkdir(exist_ok=True)
    output = runs / run
    if output.exists() or output.is_symlink():
        raise BaselineError("OUTPUT_EXISTS")
    return output


def generate(rgb: np.ndarray, source: dict[str, Any], settings: dict[str, Any], observed: dict[str, Any], run: str,
             expected: dict[str, str] | None = None) -> dict[str, Any]:
    validate_rgb(rgb)
    output = run_output(run)
    staging = Path(tempfile.mkdtemp(prefix="partial-", dir=output.parent))
    try:
        np.save(staging / ARTIFACTS[0], rgb, allow_pickle=False)
        linear = rgb.astype(np.float64) / 65535
        tone = tone_only(linear, settings["tone_strength"])
        icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
        # LCMS generates an ICC creation timestamp. Normalize its valid date and
        # clear optional profile ID; retain all sRGB transforms unchanged.
        icc = icc[:24] + struct.pack(">6H", 2000, 1, 1, 0, 0, 0) + icc[36:84] + bytes(16) + icc[100:]
        for name, values in (("baseline.jpg", linear), ("tone-only.jpg", tone)):
            image = Image.fromarray(display_pixels(values))
            image.save(staging / name, "JPEG", quality=95, subsampling=0, optimize=False,
                       progressive=False, icc_profile=icc, exif=b"")
        hashes = {name: digest(staging / name) for name in ARTIFACTS}
        if expected is not None and hashes != expected:
            raise BaselineError("REPLAY_OUTPUT_MISMATCH")
        manifest = {
            "schema": SCHEMA, "source": source, "settings": settings, "observed": observed,
            "environment": versions(), "artifacts": hashes,
            "metrics": {"decoded_zero_fraction": float(np.mean(rgb == 0)),
                        "decoded_ceiling_fraction": float(np.mean(rgb == 65535)),
                        "tone_preclamp_above_one_fraction": float(np.mean(tone > 1)),
                        "tone_preclamp_below_zero_fraction": float(np.mean(tone < 0))},
            "stage_clear": "not_implemented", "quality": "unverified",
        }
        json_write(staging / "manifest.json", manifest)
        # Directory rename publishes a complete run; never overwrite an earlier run.
        staging.rename(output)
        return manifest
    finally:
        if staging.exists() and staging.resolve().parent == output.parent.resolve() and staging.name.startswith("partial-"):
            # staging is a fresh, validated private child created by this function.
            shutil.rmtree(staging)


def develop(root: Path, relative: str, profile: str, wb: str, multipliers: list[float] | None, tone: float, run: str,
            expected_source: str | None = None, expected_outputs: dict[str, str] | None = None) -> dict[str, Any]:
    path = source_path(root, relative)
    before = digest(path)
    if expected_source is not None and before != expected_source:
        raise BaselineError("SOURCE_HASH_MISMATCH")
    rgb, observed = decode(path, profile, wb, multipliers)
    if digest(path) != before:
        raise BaselineError("SOURCE_CHANGED")
    settings = dict(SETTINGS, profile=profile, wb=wb, user_wb=multipliers, tone_strength=tone)
    return generate(rgb, {"kind": "dng", "relative": path.relative_to(root).as_posix(), "sha256": before}, settings, observed, run, expected_outputs)


def synthetic_run(run: str, tone: float = .8, expected: dict[str, str] | None = None) -> dict[str, Any]:
    rgb = synthetic()
    settings = dict(SETTINGS, profile="synthetic", wb="synthetic", user_wb=None, tone_strength=tone)
    return generate(rgb, {"kind": "synthetic", "sha256": hashlib.sha256(rgb.tobytes()).hexdigest()}, settings, {}, run, expected)


def replay(root: Path | None, manifest_run: str, run: str) -> dict[str, Any]:
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}", manifest_run):
        raise BaselineError("INVALID_RUN_NAME")
    path = private_root() / "runs" / manifest_run / "manifest.json"
    for part in (path, path.parent, path.parent.parent):
        if linked(part):
            raise BaselineError("UNSAFE_OUTPUT")
    if path.stat().st_size > 65536:
        raise BaselineError("INVALID_MANIFEST")
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schema") != SCHEMA or data.get("environment") != versions():
        raise BaselineError("MANIFEST_ENVIRONMENT_MISMATCH")
    settings = data["settings"]
    if {key: settings.get(key) for key in SETTINGS} != SETTINGS:
        raise BaselineError("MANIFEST_SETTINGS_MISMATCH")
    hashes = data["artifacts"]
    if set(hashes) != set(ARTIFACTS) or any(not re.fullmatch(r"[0-9a-f]{64}", v) for v in hashes.values()):
        raise BaselineError("INVALID_MANIFEST")
    source = data["source"]
    if source["kind"] == "synthetic" and settings["profile"] == "synthetic":
        if source["sha256"] != hashlib.sha256(synthetic().tobytes()).hexdigest():
            raise BaselineError("SOURCE_HASH_MISMATCH")
        return synthetic_run(run, settings["tone_strength"], hashes)
    if source["kind"] != "dng" or root is None:
        raise BaselineError("REPLAY_ROOT_REQUIRED")
    return develop(root, source["relative"], settings["profile"], settings["wb"], settings["user_wb"], settings["tone_strength"], run,
                   source["sha256"], hashes)


class Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise BaselineError("CLI_ARGUMENTS")


def main(argv: list[str] | None = None) -> int:
    try:
        parser = Parser(description=__doc__)
        commands = parser.add_subparsers(dest="command", required=True, parser_class=Parser)
        scan = commands.add_parser("inventory", help="Count DNGs; private relative inventory only")
        scan.add_argument("--root", required=True)
        scan.add_argument("--save", action="store_true")
        synth = commands.add_parser("synthetic", help="Synthetic pipeline test, not RAW decode")
        synth.add_argument("--run", required=True)
        synth.add_argument("--tone-strength", type=float, default=.8)
        dev = commands.add_parser("develop", help="Develop one explicitly selected, authorized DNG")
        dev.add_argument("--root", required=True)
        dev.add_argument("--input", required=True, help="DNG path relative to authorized root")
        dev.add_argument("--profile", choices=tuple(PROFILES), required=True)
        dev.add_argument("--wb", choices=("camera", "daylight", "user"), default="camera")
        dev.add_argument("--user-wb", type=float, nargs=4)
        dev.add_argument("--tone-strength", type=float, default=.8)
        dev.add_argument("--run", required=True)
        repeat = commands.add_parser("replay", help="Replay an existing private run; refuse environment changes")
        repeat.add_argument("--root")
        repeat.add_argument("--manifest-run", required=True)
        repeat.add_argument("--run", required=True)
        args = parser.parse_args(argv)
        if args.command == "inventory":
            result = inventory(root_path(args.root))
            if args.save:
                target = private_root() / "inventory.json"
                with target.open("x", encoding="utf-8") as handle:
                    json.dump(result, handle, sort_keys=True, indent=2)
            print(json.dumps({"status": "ok", "dng_count": result["dng_count"], "skipped_links": result["skipped_links"]}))
            return 0
        if args.command == "synthetic":
            result = synthetic_run(args.run, args.tone_strength)
        elif args.command == "develop":
            result = develop(root_path(args.root), args.input, args.profile, args.wb, args.user_wb, args.tone_strength, args.run)
        else:
            result = replay(root_path(args.root) if args.root else None, args.manifest_run, args.run)
        print(json.dumps({"status": "ok", "kind": result["source"]["kind"], "run": args.run, "quality": "unverified"}))
        return 0
    except BaselineError as exc:
        print("ERROR " + str(exc), file=sys.stderr)
        return 2
    except (OSError, ValueError, KeyError, TypeError):
        print("ERROR IO_OR_INVALID_DATA", file=sys.stderr)
        return 2
    except Exception:
        print("ERROR INTERNAL_FAILURE", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
