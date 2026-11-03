"""Private, local RAW look comparison. Explicit manual regions; no skin AI."""
from __future__ import annotations

import argparse
import io
import json
import math
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile

import numpy as np
from PIL import Image, ImageCms, ImageOps

import baseline as b

LOOKS = {
    "clear": {"name": "標準クリア", "detail": "明るいニュートラル肌。血色と陰影、背景の黒を残す。",
              "ev": 0.0, "local_ev": .55, "rgb": [1.01, 1., 1.015], "saturation": 1., "skin_saturation": .94, "contrast": 1.},
    "cool": {"name": "透明感クール", "detail": "少し寒色で柔らかく。灰色の唇や青白い衣装に注意。",
             "ev": .10, "local_ev": .65, "rgb": [.94, 1.01, 1.12], "saturation": .88, "skin_saturation": .91, "contrast": .86},
    "warm": {"name": "暖色ナチュラル", "detail": "明るさを保った淡いピーチ肌。暗いオレンジへ戻さない。",
             "ev": .02, "local_ev": .55, "rgb": [1.08, 1.005, .93], "saturation": .97, "skin_saturation": .96, "contrast": .98},
    "stage": {"name": "ステージ鮮明", "detail": "明るい顔と深い背景。衣装と照明色を鮮明に。",
              "ev": -.08, "local_ev": .75, "rgb": [1.015, .99, 1.025], "saturation": 1.20, "skin_saturation": .94, "contrast": 1.18},
}
LUMA = np.array([.2126, .7152, .0722], dtype=np.float32)
WEB = Path(__file__).resolve().parent / "compare_web"


def code_hashes():
    return {"look_compare.py": b.digest(Path(__file__)), "baseline.py": b.digest(Path(b.__file__))}


LOADED_CODE = code_hashes()


def write_json(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def number(value, low, high):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
        raise b.BaselineError("INVALID_RECIPE")
    return float(value)


def validate_recipe(recipe):
    if set(recipe) != {"schema", "scenes"} or recipe["schema"] != 1 or not isinstance(recipe["scenes"], list) or not 1 <= len(recipe["scenes"]) <= 3:
        raise b.BaselineError("INVALID_RECIPE")
    ids = set()
    for scene in recipe["scenes"]:
        if set(scene) != {"id", "source", "edited", "profile", "wb", "base_ev", "skin_ev", "skin", "focus", "note"}:
            raise b.BaselineError("INVALID_RECIPE")
        if not isinstance(scene["id"], str) or not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_-]{0,31}", scene["id"]) or scene["id"].casefold() in ids:
            raise b.BaselineError("INVALID_RECIPE")
        ids.add(scene["id"].casefold())
        # This UI/PoC is explicitly SIGMA fp only; Pixel quality is unverified.
        if scene["profile"] != "sigmafp" or scene["wb"] != "camera":
            raise b.BaselineError("INVALID_RECIPE")
        number(scene["base_ev"], -2, 3)
        number(scene["skin_ev"], 0, 2)
        if not isinstance(scene["note"], str) or len(scene["note"]) > 240:
            raise b.BaselineError("INVALID_RECIPE")
        if not isinstance(scene["skin"], list) or len(scene["skin"]) > 16:
            raise b.BaselineError("INVALID_RECIPE")
        for ellipse in scene["skin"]:
            if not isinstance(ellipse, list) or len(ellipse) != 4:
                raise b.BaselineError("INVALID_RECIPE")
            number(ellipse[0], 0, 1); number(ellipse[1], 0, 1)
            number(ellipse[2], .005, .5); number(ellipse[3], .005, .5)
        if not isinstance(scene["focus"], dict) or set(scene["focus"]) != {"skin", "costume", "light"}:
            raise b.BaselineError("INVALID_RECIPE")
        for box in scene["focus"].values():
            if not isinstance(box, list) or len(box) != 4:
                raise b.BaselineError("INVALID_RECIPE")
            for val in box:
                number(val, 0, 1)
            if box[0] >= box[2] or box[1] >= box[3]:
                raise b.BaselineError("INVALID_RECIPE")
    return recipe


def reference_path(root: Path, relative: str) -> Path:
    if not isinstance(relative, str):
        raise b.BaselineError("INVALID_RECIPE")
    rel = Path(relative)
    if rel.is_absolute() or rel.drive or not rel.parts or any(p in ("..", ".") for p in rel.parts):
        raise b.BaselineError("INPUT_OUTSIDE_ROOT")
    path = root
    for part in rel.parts:
        path /= part
        if b.linked(path):
            raise b.BaselineError("LINK_NOT_ALLOWED")
    if not path.resolve().is_relative_to(root) or path.suffix.lower() not in (".jpg", ".jpeg") or not 0 < path.stat().st_size <= b.MAX_FILE_BYTES:
        raise b.BaselineError("INVALID_REFERENCE")
    return path


def output_path(root: Path, value: str | Path):
    output = Path(value).absolute()
    parent = b.root_path(output.parent)
    output = parent / output.name
    if output.exists() or output.is_symlink():
        raise b.BaselineError("OUTPUT_EXISTS")
    if output.is_relative_to(root) or root.is_relative_to(output):
        raise b.BaselineError("OUTPUT_OVERLAPS_INPUT")
    return output


def snapshot(path):
    info = path.stat()
    return {"sha256": b.digest(path), "size": info.st_size, "mtime_ns": info.st_mtime_ns}


def skin_mask(width, height, start, stop, ellipses):
    x = (np.arange(width, dtype=np.float32) + .5) / width
    y = (np.arange(start, stop, dtype=np.float32) + .5) / height
    mask = np.zeros((stop - start, width), dtype=np.float32)
    for cx, cy, rx, ry in ellipses:
        radius = np.sqrt(((x[None, :] - cx) / rx) ** 2 + ((y[:, None] - cy) / ry) ** 2)
        # Full center, smooth feather through outer 45%; no hard mask boundary.
        t = np.clip((1 - radius) / .45, 0, 1)
        mask = np.maximum(mask, t * t * (3 - 2 * t))
    return mask


def transform(linear, mask, base_ev, look=None, skin_ev=0):
    """Bounded SDR luma-ratio mapping, followed by hue-preserving gamut compression."""
    if look is None:
        return linear
    luma = linear @ LUMA
    # Avoid lifting black hair/background inside broad manual anatomical regions.
    eligibility = np.clip(luma / .025, 0, 1)
    local = mask * eligibility
    gain = np.exp2(base_ev + look["ev"] + (skin_ev + look["local_ev"]) * local)
    # Bounded monotonic shoulder. EV describes the limiting shadow gain, not
    # a reconstruction of the DNG profile's nonlinear rendering intent.
    target = luma * gain / (1 + (gain - 1) * luma)
    # Odds-power contrast fixes black, .18 and white; unlike a raw power around
    # .18 it cannot brighten neutral highlights past 1 and erase clothing detail.
    power = 1 + (look["contrast"] - 1) * (1 - .75 * local)
    raised = np.power(np.clip(target, 0, 1), power)
    lower = np.power(np.clip(1 - target, 0, 1), power) * (.18 / .82) ** (power - 1)
    target = raised / np.maximum(raised + lower, 1e-8)
    values = linear * (target / np.maximum(luma, 1e-8))[..., None]
    tint = np.array(look["rgb"], dtype=np.float32)
    tinted = values * tint
    # Preserve luminance when warming/cooling, rather than confounding brightness.
    values = tinted * (target / np.maximum(tinted @ LUMA, 1e-8))[..., None]
    saturation = look["saturation"] * (1 - local) + look["skin_saturation"] * local
    values = target[..., None] + (values - target[..., None]) * saturation[..., None]
    return values


def gamut_compress(values):
    """Compress chroma toward clipped luminance; do not clip separate RGB channels."""
    center = np.clip(values @ LUMA, 0, 1)
    delta = values - center[..., None]
    limit = np.ones_like(center)
    for c in range(3):
        d = delta[..., c]
        positive = np.maximum(d, 1e-8)
        negative = np.minimum(d, -1e-8)
        bound = np.where(d > 0, (1 - center) / positive, np.where(d < 0, -center / negative, 1))
        limit = np.minimum(limit, bound)
    return np.clip(center[..., None] + delta * np.clip(limit, 0, 1)[..., None], 0, 1)


def icc_bytes():
    icc = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    return icc[:24] + struct.pack(">6H", 2000, 1, 1, 0, 0, 0) + icc[36:84] + bytes(16) + icc[100:]


def render(rgb, scene, look, foundation=False):
    h, w, _ = rgb.shape
    result = np.empty(rgb.shape, dtype=np.uint8)
    above = below = 0
    for start in range(0, h, 128):
        stop = min(start + 128, h)
        # Preserve the old baseline's float64 transfer/rounding byte for byte.
        linear = rgb[start:stop].astype(np.float64 if look is None else np.float32) / 65535
        mask = skin_mask(w, h, start, stop, scene["skin"])
        values = transform(linear, mask, scene["base_ev"], look, 0 if foundation else scene["skin_ev"])
        above += int(np.count_nonzero(values > 1))
        below += int(np.count_nonzero(values < 0))
        result[start:stop] = b.display_pixels(gamut_compress(values) if look else values)
    return result, {"pre_gamut_above_one_fraction": above / rgb.size,
                    "pre_gamut_below_zero_fraction": below / rgb.size}


def save_image(pixels, path):
    image = Image.fromarray(pixels)
    image.save(path, "JPEG", quality=95, subsampling=0, optimize=False, progressive=False, icc_profile=icc_bytes(), exif=b"")
    thumb = image.copy()
    thumb.thumbnail((720, 720), Image.Resampling.LANCZOS)
    thumb.save(path.with_name(path.stem + "-thumb.jpg"), "JPEG", quality=90, subsampling=0, icc_profile=icc_bytes(), exif=b"")


def prepare(root: Path, recipe, output: Path):
    root = b.root_path(root)
    validate_recipe(recipe)
    output = output_path(root, output)
    environment = b.versions()
    if code_hashes() != LOADED_CODE:
        raise b.BaselineError("CODE_CHANGED")
    template_hashes = {name: b.digest(WEB / name) for name in ("index.html", "app.js", "style.css")}
    sources = {}
    for scene in recipe["scenes"]:
        sources[scene["source"]] = b.source_path(root, scene["source"])
        if scene["edited"] is not None:
            sources[scene["edited"]] = reference_path(root, scene["edited"])
    before = {k: snapshot(p) for k, p in sources.items()}
    staging = Path(tempfile.mkdtemp(prefix="look-partial-", dir=output.parent))
    try:
        assets = staging / "assets"
        assets.mkdir()
        page = {"looks": LOOKS, "scenes": []}
        records = []
        for scene in recipe["scenes"]:
            rgb, observed = b.decode(sources[scene["source"]], scene["profile"], scene["wb"], None)
            entry = {"id": scene["id"], "focus": scene["focus"], "base_ev": scene["base_ev"], "skin_ev": scene["skin_ev"], "note": scene["note"], "images": {}}
            metrics = {}
            # Include an explicit common exposure-only foundation, before local/color looks.
            foundation = dict(LOOKS["clear"], ev=0, local_ev=0, rgb=[1., 1., 1.], saturation=1., skin_saturation=1., contrast=1.)
            for key, look in [("baseline", None), ("foundation", foundation), *LOOKS.items()]:
                pixels, metrics[key] = render(rgb, scene, look, foundation=key == "foundation")
                filename = scene["id"] + "-" + key + ".jpg"
                save_image(pixels, assets / filename)
                entry["images"][key] = {"path": "assets/" + filename, "width": rgb.shape[1], "height": rgb.shape[0]}
            if scene["edited"] is not None:
                with Image.open(sources[scene["edited"]]) as im:
                    if im.width * im.height > b.MAX_PIXELS or "icc_profile" not in im.info:
                        raise b.BaselineError("INVALID_REFERENCE")
                    profile = ImageCms.ImageCmsProfile(io.BytesIO(im.info["icc_profile"]))
                    oriented = ImageOps.exif_transpose(im)
                    converted = ImageCms.profileToProfile(oriented, profile, ImageCms.createProfile("sRGB"), outputMode="RGB", renderingIntent=1)
                    filename = scene["id"] + "-edited.jpg"
                    save_image(np.asarray(converted), assets / filename)
                    entry["images"]["edited"] = {"path": "assets/" + filename, "width": converted.width, "height": converted.height}
            page["scenes"].append(entry)
            records.append({"id": scene["id"], "baseline_settings": dict(b.SETTINGS, profile=scene["profile"], wb=scene["wb"]),
                            "observed": observed, "metrics": metrics, "baseline_rgb_sha256": __import__("hashlib").sha256(rgb.tobytes()).hexdigest()})
        after = {k: snapshot(p) for k, p in sources.items()}
        if before != after:
            raise b.BaselineError("SOURCE_CHANGED")
        for name in ("index.html", "app.js", "style.css"):
            shutil.copyfile(WEB / name, staging / name)
        if (code_hashes() != LOADED_CODE or b.versions() != environment
                or any(b.digest(staging / name) != value or b.digest(WEB / name) != value for name, value in template_hashes.items())):
            raise b.BaselineError("CODE_CHANGED")
        data = json.dumps(page, ensure_ascii=False, sort_keys=True).replace("<", "\\u003c").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        (staging / "data.js").write_text("window.COMPARISON = " + data + ";\n", encoding="utf-8")
        write_json(staging / "recipe.json", recipe)
        manifest = {"schema": 1, "recipe": recipe, "looks": LOOKS, "environment": environment, "code_hashes": LOADED_CODE,
                    "template_hashes": template_hashes,
                    "sources_before": before, "sources_after": after, "sources_unchanged": True, "scenes": records,
                    "artifacts": {p.relative_to(staging).as_posix(): b.digest(p) for p in sorted(staging.rglob("*")) if p.is_file()},
                    "stage_clear": "not_implemented", "automatic_skin_detection": False, "browser_rendering": "not_performed"}
        write_json(staging / "manifest.json", manifest)
        staging.rename(output)
        return manifest
    finally:
        if staging.exists() and staging.parent.resolve() == output.parent.resolve() and staging.name.startswith("look-partial-"):
            shutil.rmtree(staging)


def main(argv=None):
    try:
        parser = b.Parser(description=__doc__)
        parser.add_argument("--root", required=True)
        parser.add_argument("--recipe", required=True)
        parser.add_argument("--output", required=True)
        args = parser.parse_args(argv)
        path = Path(args.recipe)
        if path.stat().st_size > 65536:
            raise b.BaselineError("INVALID_RECIPE")
        recipe = json.loads(path.read_text(encoding="utf-8-sig"))
        prepare(Path(args.root), recipe, Path(args.output))
        print('{"status":"ok","stage_clear":"not_implemented","browser_rendering":"not_performed"}')
        return 0
    except b.BaselineError as exc:
        print("ERROR " + str(exc), file=sys.stderr)
        return 2
    except (OSError, ValueError, TypeError, KeyError):
        print("ERROR IO_OR_INVALID_DATA", file=sys.stderr)
        return 2
    except Exception:
        print("ERROR INTERNAL_FAILURE", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
