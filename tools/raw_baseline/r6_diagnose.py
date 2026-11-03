"""Private round-6 colour-stage diagnosis (#63). Local only; never writes to inputs.

Axis 1 compares where colour differences arise (our decode, profile, WB, sliders),
using user-exported Lightroom references only as diagnostic images. Axis 2-4
compare single changes on top of the round-5 foundation. Nothing here is fitted
to the references or adopted as a default look.
"""
from __future__ import annotations

import argparse
import html
import io
import json
import math
from pathlib import Path
import struct
import sys

import numpy as np
from PIL import Image, ImageCms

import baseline as b

LUMA = np.array([.2126, .7152, .0722], dtype=np.float32)
WORK_LONG = 1200
# Lightroom's default crop for these DNGs (long, short) margins in sensor pixels.
CROP_MARGIN = (32, 21)
LR_VARIANTS = ("b-standard-zero", "a-portrait-zero", "c-portrait-editwb")
STAGES = ("ours", "dngmatrix", "dngprofile", "r5", "b-standard-zero", "a-portrait-zero", "c-portrait-editwb", "edited")
STAGE_NAMES = {"ours": "当方の基準（露出合わせ）", "dngmatrix": "DNG行列補間（露出合わせ）",
               "dngprofile": "DNG行列＋HueSatMap（露出合わせ）", "r5": "第5回①", "b-standard-zero": "LR Adobe標準",
               "a-portrait-zero": "LR Camera Portrait", "c-portrait-editwb": "LR Portrait＋編集WB",
               "edited": "本人の最終編集", "b2-neutral-zero": "LR Adobeニュートラル"}
REGIONS = ("skin", "costume", "light", "dark")


# ---------- colour ----------

def srgb_to_linear(encoded: np.ndarray) -> np.ndarray:
    return np.where(encoded <= .04045, encoded / 12.92, ((encoded + .055) / 1.055) ** 2.4).astype(np.float32)


def linear_to_srgb8(linear: np.ndarray) -> np.ndarray:
    return b.display_pixels(np.clip(linear, 0, 1))


def oklab(linear: np.ndarray) -> np.ndarray:
    m1 = np.array([[.4122214708, .5363325363, .0514459929], [.2119034982, .6806995451, .1073969566],
                   [.0883024619, .2817188376, .6299787005]], dtype=np.float32)
    m2 = np.array([[.2104542553, .7936177850, -.0040720468], [1.9779984951, -2.4285922050, .4505937099],
                   [.0259040371, .7827717662, -.8086757660]], dtype=np.float32)
    # Signed cube root on unclipped values: wide-gamut (e.g. Display P3) colours keep their
    # measured chroma; clipping belongs to preview generation only.
    lms = np.cbrt(linear @ m1.T)
    return lms @ m2.T


# ---------- loading ----------

def fit_size(width: int, height: int) -> tuple[int, int]:
    scale = WORK_LONG / max(width, height)
    return max(1, round(width * scale)), max(1, round(height * scale))


def resize_linear(linear: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    channels = [np.asarray(Image.fromarray(np.ascontiguousarray(linear[..., c]), "F").resize(size, Image.Resampling.BOX))
                for c in range(3)]
    return np.stack(channels, -1).astype(np.float32)


# Display P3 (D65, sRGB transfer) to linear sRGB; out-of-gamut values stay negative/above 1
# so that measurement keeps the edit's full chroma.
P3_TO_SRGB = np.array([[1.2249401, -.2249404, 0.], [-.0420569, 1.0420571, 0.], [-.0196376, -.0786361, 1.0982735]],
                      dtype=np.float32)


def load_jpeg(path: Path, crop: bool = False) -> np.ndarray:
    with Image.open(path) as image:
        icc = image.info.get("icc_profile")
        name = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc))) if icc else "sRGB"
        rgb = np.asarray(image.convert("RGB"), dtype=np.float32) / 255
    linear = srgb_to_linear(rgb)
    if "P3" in name:
        linear = linear @ P3_TO_SRGB.T
    elif "sRGB" not in name:
        raise b.BaselineError("REFERENCE_PROFILE_UNSUPPORTED")
    if crop:  # round-5 renders keep the full sensor area; align them to Lightroom's default crop
        linear = crop_default(linear)
    return resize_linear(linear, fit_size(linear.shape[1], linear.shape[0]))


def crop_default(linear: np.ndarray) -> np.ndarray:
    h, w = linear.shape[:2]
    my, mx = (CROP_MARGIN[0], CROP_MARGIN[1]) if h > w else (CROP_MARGIN[1], CROP_MARGIN[0])
    return linear[my:h - my, mx:w - mx]


def load_dng(path: Path, wb: str = "camera", multipliers: list[float] | None = None) -> np.ndarray:
    rgb, _ = b.decode(path, "sigmafp", wb, multipliers)
    linear = crop_default(rgb.astype(np.float32) / 65535)
    return resize_linear(linear, fit_size(linear.shape[1], linear.shape[0]))


# ---------- DNG embedded camera profile (spec-following, diagnostic) ----------

TAG_TYPES = {3: ("H", 2), 4: ("I", 4), 5: ("II", 8), 10: ("ii", 8), 11: ("f", 4)}
PROFILE_TAGS = {50721: "cm1", 50722: "cm2", 50728: "neutral", 50778: "illum1", 50779: "illum2",
                50937: "hsm_dims", 50938: "hsm1", 50939: "hsm2"}
ILLUMINANT_CCT = {17: 2856., 21: 6504., 20: 5503., 23: 5003., 22: 7504.}
XYZ_TO_SRGB = np.array([[3.2404542, -1.5371385, -.4985314], [-.9692660, 1.8760108, .0415560],
                        [.0556434, -.2040259, 1.0572252]])
PROPHOTO_TO_XYZ_D50 = np.array([[.7976749, .1351917, .0313534], [.2880402, .7118741, .0000857], [0., 0., .8252100]])
BRADFORD = np.array([[.8951, .2664, -.1614], [-.7502, 1.7135, .0367], [.0389, -.0685, 1.0296]])
D50, D65 = np.array([.3457, .3585]), np.array([.3127, .3290])


def dng_profile_tags(path: Path) -> dict:
    """Read only the embedded colour-profile tags from IFD0."""
    result = {}
    with path.open("rb") as handle:
        endian = "<" if handle.read(2) == b"II" else ">"
        handle.read(2)
        offset = struct.unpack(endian + "I", handle.read(4))[0]
        handle.seek(offset)
        count = struct.unpack(endian + "H", handle.read(2))[0]
        for index in range(count):
            handle.seek(offset + 2 + 12 * index)
            tag, kind, length, raw = struct.unpack(endian + "HHI4s", handle.read(12))
            if tag not in PROFILE_TAGS or kind not in TAG_TYPES:
                continue
            fmt, size = TAG_TYPES[kind]
            data = raw if size * length <= 4 else (handle.seek(struct.unpack(endian + "I", raw)[0]), handle.read(size * length))[1]
            values = struct.unpack(endian + fmt * length, data[:size * length])
            if kind in (5, 10):
                values = tuple(values[i] / values[i + 1] if values[i + 1] else 0. for i in range(0, len(values), 2))
            result[PROFILE_TAGS[tag]] = values
    return result


def xy_to_xyz(xy: np.ndarray) -> np.ndarray:
    return np.array([xy[0] / xy[1], 1., (1 - xy[0] - xy[1]) / xy[1]])


def xy_to_cct(xy: np.ndarray) -> float:
    n = (xy[0] - .3320) / (.1858 - xy[1])
    return float(449 * n ** 3 + 3525 * n ** 2 + 6823.3 * n + 5520.33)


def bradford(src_xy: np.ndarray, dst_xy: np.ndarray) -> np.ndarray:
    src, dst = BRADFORD @ xy_to_xyz(src_xy), BRADFORD @ xy_to_xyz(dst_xy)
    return np.linalg.inv(BRADFORD) @ np.diag(dst / src) @ BRADFORD


def profile_weight(cct: float, tags: dict) -> float:
    t1, t2 = ILLUMINANT_CCT[tags["illum1"][0]], ILLUMINANT_CCT[tags["illum2"][0]]
    if cct <= t1:
        return 1.
    if cct >= t2:
        return 0.
    return (1 / cct - 1 / t2) / (1 / t1 - 1 / t2)


def neutral_white(tags: dict, neutral: np.ndarray) -> tuple[np.ndarray, float]:
    """DNG SDK style: iterate white xy so the interpolated ColorMatrix maps it to the neutral."""
    cm1, cm2 = np.array(tags["cm1"]).reshape(3, 3), np.array(tags["cm2"]).reshape(3, 3)
    xy, weight = D50.copy(), .5
    for _ in range(30):
        weight = profile_weight(xy_to_cct(xy), tags)
        xyz = np.linalg.inv(weight * cm1 + (1 - weight) * cm2) @ neutral
        new = xyz[:2] / xyz.sum()
        if np.abs(new - xy).max() < 1e-7:
            break
        xy = new
    return xy, weight


def apply_hue_sat_map(prophoto: np.ndarray, table: np.ndarray, hue_divs: int, sat_divs: int) -> np.ndarray:
    """ProfileHueSatMap in linear ProPhoto HSV (value divisions = 1), bilinear over hue/saturation."""
    r, g, bb = prophoto[..., 0], prophoto[..., 1], prophoto[..., 2]
    value = np.maximum(np.maximum(r, g), bb)
    low = np.minimum(np.minimum(r, g), bb)
    delta = value - low
    sat = np.where(value > 0, delta / np.maximum(value, 1e-12), 0)
    safe = np.maximum(delta, 1e-12)
    hue = np.where(value == r, (g - bb) / safe, np.where(value == g, 2 + (bb - r) / safe, 4 + (r - g) / safe))
    hue = np.where(delta > 0, (hue % 6) * 60, 0.)
    entries = table.reshape(hue_divs, sat_divs, 3)  # DNG order: hue-major, then saturation
    hf = hue / 360 * hue_divs
    h0 = np.floor(hf).astype(int) % hue_divs
    h1 = (h0 + 1) % hue_divs
    hw = hf - np.floor(hf)
    sf = sat * (sat_divs - 1)
    s0 = np.clip(np.floor(sf).astype(int), 0, sat_divs - 1)
    s1 = np.clip(s0 + 1, 0, sat_divs - 1)
    sw = sf - np.floor(sf)

    def pick(hi, si):
        return entries[hi, si]

    mixed = ((1 - hw)[..., None] * ((1 - sw)[..., None] * pick(h0, s0) + sw[..., None] * pick(h0, s1))
             + hw[..., None] * ((1 - sw)[..., None] * pick(h1, s0) + sw[..., None] * pick(h1, s1)))
    hue = (hue + mixed[..., 0]) % 360
    sat = np.clip(sat * mixed[..., 1], 0, 1)
    value = value * mixed[..., 2]
    sector = hue / 60
    i = np.floor(sector).astype(int) % 6
    f = sector - np.floor(sector)
    p, q, t = value * (1 - sat), value * (1 - sat * f), value * (1 - sat * (1 - f))
    choices = [np.stack(c, -1) for c in ((value, t, p), (q, value, p), (p, value, t), (p, q, value), (t, p, value), (value, p, q))]
    out = np.zeros_like(prophoto)
    for k in range(6):
        out = np.where((i == k)[..., None], choices[k], out)
    return out


def decode_dng_profile(path: Path, hue_sat: bool, multipliers: list[float] | None = None) -> tuple[np.ndarray, dict]:
    """Camera RGB from LibRaw (WB applied, no colour matrix), then the DNG's own profile maths."""
    import rawpy
    tags = dng_profile_tags(path)
    with rawpy.imread(str(path)) as raw:
        camera = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD, output_color=rawpy.ColorSpace.raw,
                                 use_camera_wb=multipliers is None, user_wb=multipliers, use_auto_wb=False,
                                 output_bps=16, gamma=(1, 1), no_auto_bright=True, bright=1.0,
                                 highlight_mode=rawpy.HighlightMode.Clip)
        applied = np.array(multipliers if multipliers else raw.camera_whitebalance, dtype=float)[:3]
    camera = crop_default(camera.astype(np.float32) / 65535)
    camera = resize_linear(camera, fit_size(camera.shape[1], camera.shape[0])).astype(np.float64)
    neutral = (1 / applied) / (1 / applied).max()
    white, weight = neutral_white(tags, neutral)
    cm = weight * np.array(tags["cm1"]).reshape(3, 3) + (1 - weight) * np.array(tags["cm2"]).reshape(3, 3)
    to_xyz = np.linalg.inv(cm)
    scale = (to_xyz @ neutral)[1]
    xyz = (camera * neutral) @ (to_xyz / scale).T
    xyz_d50 = xyz @ bradford(white, D50).T
    if hue_sat and "hsm1" in tags:
        dims = tags["hsm_dims"]
        table = weight * np.array(tags["hsm1"]) + (1 - weight) * np.array(tags["hsm2"])
        prophoto = xyz_d50 @ np.linalg.inv(PROPHOTO_TO_XYZ_D50).T
        prophoto = apply_hue_sat_map(np.maximum(prophoto, 0), table, dims[0], dims[1])
        xyz_d50 = prophoto @ PROPHOTO_TO_XYZ_D50.T
    srgb = xyz_d50 @ (XYZ_TO_SRGB @ bradford(D50, D65)).T
    return srgb.astype(np.float32), {"white_xy": white.tolist(), "white_cct": xy_to_cct(white), "matrix_weight_A": weight}


# ---------- measurement ----------

def box_pixels(image: np.ndarray, box: list[float]) -> np.ndarray:
    h, w = image.shape[:2]
    x0, y0, x1, y1 = box
    return image[int(y0 * h):max(int(y1 * h), int(y0 * h) + 1), int(x0 * w):max(int(x1 * w), int(x0 * w) + 1)].reshape(-1, 3)


def stats(pixels: np.ndarray) -> dict[str, float | None]:
    lab = oklab(pixels)
    lightness, a, bb = lab[:, 0], lab[:, 1], lab[:, 2]
    chroma = np.hypot(a, bb)
    coloured = (chroma > .03) & (lightness > .12)
    hue = None
    if coloured.sum() >= 20:
        weight = chroma[coloured]
        hue = math.degrees(math.atan2(float((bb[coloured] * weight).sum()), float((a[coloured] * weight).sum()))) % 360
    return {"Y": float(np.median(pixels @ LUMA)), "L": float(lightness.mean()), "C": float(chroma.mean()),
            "C_coloured": float(chroma[coloured].mean()) if coloured.any() else None,
            "hue": hue, "coloured_fraction": float(coloured.mean())}


def image_stats(image: np.ndarray, focus: dict[str, list[float]]) -> dict[str, dict]:
    result = {name: stats(box_pixels(image, focus[name])) for name in REGIONS}
    flat = image.reshape(-1, 3)
    lab = oklab(flat)
    shadows = lab[:, 0] < .45
    result["shadows_all"] = {"C": float(np.hypot(lab[shadows, 1], lab[shadows, 2]).mean()) if shadows.any() else None,
                             "fraction": float(shadows.mean())}
    return result


def hue_delta(a: float | None, c: float | None) -> float | None:
    if a is None or c is None:
        return None
    return (c - a + 180) % 360 - 180


def match_exposure(image: np.ndarray, reference: np.ndarray, box: list[float]) -> tuple[np.ndarray, float]:
    ours = float(np.median(box_pixels(image, box) @ LUMA))
    target = float(np.median(box_pixels(reference, box) @ LUMA))
    gain = target / max(ours, 1e-6)
    return image * gain, math.log2(gain)


# ---------- inputs ----------

def scene_inputs(args, scene: dict) -> dict[str, Path]:
    name = Path(scene["source"]).stem.split("-", 1)[-1]
    paths = {"dng": args.livepicture / scene["source"], "edited": args.livepicture / scene["edited"],
             "r5": args.r5 / "assets" / f"{scene['id']}-clear.jpg"}
    for variant in (*LR_VARIANTS, "b2-neutral-zero"):
        path = args.references / variant / f"{name}.jpg"
        if path.exists():
            paths[variant] = path
    return paths


def thumb(image: np.ndarray, path: Path, focus: dict | None = None) -> None:
    pixels = linear_to_srgb8(image)
    out = Image.fromarray(pixels)
    if focus:
        from PIL import ImageDraw
        draw = ImageDraw.Draw(out)
        colours = {"skin": (255, 200, 160), "costume": (200, 255, 200), "light": (160, 200, 255), "dark": (255, 255, 255)}
        h, w = pixels.shape[:2]
        for name, (x0, y0, x1, y1) in focus.items():
            if name in colours:
                draw.rectangle([x0 * w, y0 * h, x1 * w, y1 * h], outline=colours[name], width=2)
    out.save(path, "JPEG", quality=90, icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())


def axis1(args, scenes: list[dict], out: Path) -> dict:
    report = {}
    for scene in scenes:
        paths = scene_inputs(args, scene)
        images = {key: load_jpeg(path, crop=key == "r5") for key, path in paths.items() if key not in ("dng",)}
        reference = images["a-portrait-zero"]
        ours = load_dng(paths["dng"])
        if ours.shape != reference.shape:
            ours = resize_linear(ours, (reference.shape[1], reference.shape[0]))
        images["ours"], ev = match_exposure(ours, reference, scene["focus"]["skin"])
        profile_info = {}
        for key, hue_sat in (("dngmatrix", False), ("dngprofile", True)):
            decoded, profile_info = decode_dng_profile(paths["dng"], hue_sat)
            if decoded.shape != reference.shape:
                decoded = resize_linear(decoded, (reference.shape[1], reference.shape[0]))
            images[key], _ = match_exposure(decoded, reference, scene["focus"]["skin"])
        for key in images:
            if images[key].shape != reference.shape:
                images[key] = resize_linear(images[key], (reference.shape[1], reference.shape[0]))
        measured = {key: image_stats(image, scene["focus"]) for key, image in images.items()}
        order = [key for key in STAGES if key in images] + [key for key in images if key not in STAGES]
        for key in order:
            thumb(images[key], out / f"{scene['id']}-{key}.jpg", scene["focus"] if key == "ours" else None)
        report[scene["id"]] = {"ours_exposure_match_ev": ev, "order": order, "stats": measured, "profile": profile_info,
                               "note": scene.get("note", "")}
    return report


def stage_table(report: dict) -> list[dict]:
    rows = []
    chain = ("ours", "dngmatrix", "dngprofile", "b-standard-zero", "a-portrait-zero", "c-portrait-editwb", "edited")
    for sid, item in report.items():
        stats_by = item["stats"]
        present = [key for key in chain if key in stats_by]
        for region in REGIONS:
            for before, after in zip(present, present[1:]):
                s0, s1 = stats_by[before][region], stats_by[after][region]
                rows.append({"scene": sid, "region": region, "from": before, "to": after,
                             "hue_shift": hue_delta(s0["hue"], s1["hue"]),
                             "chroma_ratio": s1["C"] / s0["C"] if s0["C"] > 1e-6 else None,
                             "dL": s1["L"] - s0["L"]})
    return rows


# ---------- round-5 foundation (copied semantics of the private round-5 look_compare.transform) ----------

def skin_mask(width: int, height: int, ellipses: list[list[float]]) -> np.ndarray:
    x = (np.arange(width, dtype=np.float32) + .5) / width
    y = (np.arange(height, dtype=np.float32) + .5) / height
    mask = np.zeros((height, width), dtype=np.float32)
    for cx, cy, rx, ry in ellipses:
        radius = np.sqrt(((x[None, :] - cx) / rx) ** 2 + ((y[:, None] - cy) / ry) ** 2)
        t = np.clip((1 - radius) / .45, 0, 1)
        mask = np.maximum(mask, t * t * (3 - 2 * t))
    return mask


def foundation(linear: np.ndarray, mask: np.ndarray, base_ev: float, look: dict, local_ev: float = 0.,
               luma_gain: np.ndarray | None = None) -> np.ndarray:
    """Round-5 bounded tone + odds contrast + RGB tint + ROI yellow gate + saturation.

    luma_gain (axis 3) multiplies the target luminance after the curve, before contrast.
    """
    luma = linear @ LUMA
    local = mask * np.clip(luma / .025, 0, 1)
    gain = np.exp2(base_ev + look["ev"] + local_ev * local)
    target = luma * gain / (1 + (gain - 1) * luma)
    if luma_gain is not None:
        target = np.clip(target * luma_gain, 0, 1)
    power = 1 + (look["contrast"] - 1) * (1 - .75 * local)
    raised = np.power(np.clip(target, 0, 1), power)
    lower = np.power(np.clip(1 - target, 0, 1), power) * (.18 / .82) ** (power - 1)
    target = raised / np.maximum(raised + lower, 1e-8)
    values = linear * (target / np.maximum(luma, 1e-8))[..., None]
    tinted = values * np.array(look["rgb"], dtype=np.float32)
    values = tinted * (target / np.maximum(tinted @ LUMA, 1e-8))[..., None]
    yellow = np.clip(3 * (np.minimum(values[..., 0], values[..., 1]) - values[..., 2]) / np.maximum(target, 1e-8), 0, 1)
    tint_local = 1 + (np.array(look.get("yellow_rgb", [1., 1., 1.]), dtype=np.float32) - 1) * (local * yellow)[..., None]
    tinted = values * tint_local
    values = tinted * (target / np.maximum(tinted @ LUMA, 1e-8))[..., None]
    saturation = look["saturation"] * (1 - local) + look["skin_saturation"] * local
    return target[..., None] + (values - target[..., None]) * saturation[..., None]


def gamut_compress(values: np.ndarray) -> np.ndarray:
    center = np.clip(values @ LUMA, 0, 1)
    delta = values - center[..., None]
    limit = np.ones_like(center)
    for c in range(3):
        d = delta[..., c]
        bound = np.where(d > 0, (1 - center) / np.maximum(d, 1e-8), np.where(d < 0, -center / np.minimum(d, -1e-8), 1))
        limit = np.minimum(limit, bound)
    return np.clip(center[..., None] + delta * np.clip(limit, 0, 1)[..., None], 0, 1)


def render_foundation(linear: np.ndarray, scene: dict, look: dict, **kwargs) -> np.ndarray:
    mask = skin_mask(linear.shape[1], linear.shape[0], scene["skin"])
    return gamut_compress(foundation(linear, mask, scene["base_ev"], look, **kwargs))


# ---------- temperature / tint (relative shifts only) ----------

def planck_uv(temp: float) -> np.ndarray:
    t = temp
    u = (.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t)
    v = (.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t)
    return np.array([u, v])


def tint_normal(temp: float) -> np.ndarray:
    """Unit normal for the *illuminant* tint in Lightroom's convention.

    Lightroom's Tint is a correction: a positive value renders more magenta, i.e. it assumes
    a greener illuminant, which lies above the Planckian locus (higher v).
    """
    d = planck_uv(temp * 1.001) - planck_uv(temp * .999)
    n = np.array([-d[1], d[0]]) / np.hypot(*d)
    return n if n[1] > 0 else -n


def uv_to_xy(uv: np.ndarray) -> np.ndarray:
    d = 2 * uv[0] - 8 * uv[1] + 4
    return np.array([3 * uv[0] / d, 2 * uv[1] / d])


def xy_to_uv(xy: np.ndarray) -> np.ndarray:
    d = -2 * xy[0] + 12 * xy[1] + 3
    return np.array([4 * xy[0] / d, 6 * xy[1] / d])


def temp_tint_to_xy(temp: float, tint: float) -> np.ndarray:
    return uv_to_xy(planck_uv(temp) + tint_normal(temp) * tint / 3000)


def xy_to_temp_tint(xy: np.ndarray) -> tuple[float, float]:
    uv = xy_to_uv(xy)
    mireds = np.linspace(1e6 / 15000, 1e6 / 1700, 4000)
    temps = 1e6 / mireds
    dist = [np.hypot(*(uv - planck_uv(t))) for t in temps]
    temp = float(temps[int(np.argmin(dist))])
    offset = uv - planck_uv(temp)
    return temp, float(offset @ tint_normal(temp) * 3000)


def shifted_multipliers(path: Path, mired_shift: float, tint_shift: float) -> tuple[list[float], dict]:
    """Shift our as-shot white by a Lightroom-relative mired/tint delta, return LibRaw user_wb."""
    import rawpy
    tags = dng_profile_tags(path)
    with rawpy.imread(str(path)) as raw:
        camera_wb = np.array(raw.camera_whitebalance, dtype=float)[:3]
    neutral = (1 / camera_wb) / (1 / camera_wb).max()
    white, _ = neutral_white(tags, neutral)
    temp0, tint0 = xy_to_temp_tint(white)
    temp1 = 1e6 / (1e6 / temp0 + mired_shift)
    tint1 = tint0 + tint_shift
    xy1 = temp_tint_to_xy(temp1, tint1)
    weight = profile_weight(temp1, tags)
    cm = weight * np.array(tags["cm1"]).reshape(3, 3) + (1 - weight) * np.array(tags["cm2"]).reshape(3, 3)
    neutral1 = cm @ xy_to_xyz(xy1)
    mult = 1 / (neutral1 / neutral1[1])
    return [float(mult[0]), 1., float(mult[2]), 1.], {"ours_as_shot": [temp0, tint0], "target": [temp1, tint1]}


def xmp_wb(path: Path) -> tuple[float, float]:
    import re
    data = path.read_bytes()
    text = data[data.find(b"<x:xmpmeta"):data.find(b"</x:xmpmeta>")].decode("utf-8", "replace")
    found = dict(re.findall(r'crs:(Temperature|Tint)="([^"]*)"', text))
    return float(found["Temperature"]), float(found["Tint"])


# ---------- axes 2-4 ----------

def skin_hue_gain(linear: np.ndarray, strength: float) -> np.ndarray:
    """HSL-luminance-like lift for orange-red-yellow hues (OKLab hue 20-90°), no spatial mask."""
    lab = oklab(np.maximum(linear, 0))
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    centre, half = 55., 35.
    band = np.clip(1 - np.abs((hue - centre + 180) % 360 - 180) / half, 0, 1)
    band = band * band * (3 - 2 * band)
    weight = band * np.clip((chroma - .015) / .03, 0, 1) * np.clip(1 - (chroma - .16) / .06, 0, 1)
    return 1 + (2 ** strength - 1) * weight


def region_delta(base: dict, other: dict) -> dict:
    return {r: {"dL": other[r]["L"] - base[r]["L"], "hue": hue_delta(base[r]["hue"], other[r]["hue"]),
                "C_ratio": other[r]["C"] / base[r]["C"] if base[r]["C"] > 1e-6 else None} for r in REGIONS}


def axes234(args, scenes: list[dict], look: dict, out: Path) -> dict:
    neutral_look = dict(look, rgb=[1., 1., 1.])
    report = {"axis2": {}, "axis3": {}, "axis4": {}, "check": {}}
    for scene in scenes:
        sid = scene["id"]
        paths = scene_inputs(args, scene)
        base = load_dng(paths["dng"])
        size = (base.shape[1], base.shape[0])
        r5_asset = load_jpeg(paths["r5"], crop=True)
        r5_asset = resize_linear(r5_asset, size) if r5_asset.shape != base.shape else r5_asset
        variants = {"r5-repro": render_foundation(base, scene, look)}
        # Reproduction check against the round-5 asset (both 8-bit sRGB).
        diff = np.abs(linear_to_srgb8(variants["r5-repro"]).astype(int) - linear_to_srgb8(r5_asset).astype(int))
        report["check"][sid] = {"r5_mean_abs_8bit": float(diff.mean()), "r5_p99_abs_8bit": float(np.percentile(diff, 99))}
        variants["wb-camera-notint"] = render_foundation(base, scene, neutral_look)
        wb_info = {}
        if "a-portrait-zero" in paths:
            as_t, as_tint = xmp_wb(paths["a-portrait-zero"])
            ed_t, ed_tint = xmp_wb(paths["edited"])
            for key, (mired, tint) in {"wb-edit": (1e6 / ed_t - 1e6 / as_t, ed_tint - as_tint),
                                       "wb-rule": (1e6 / (as_t - 550) - 1e6 / as_t, 0.)}.items():
                mult, info = shifted_multipliers(paths["dng"], mired, tint)
                decoded = load_dng(paths["dng"], "user", mult)
                decoded = resize_linear(decoded, size) if decoded.shape != base.shape else decoded
                variants[key] = render_foundation(decoded, scene, neutral_look)
                wb_info[key] = dict(info, lightroom_shift={"mired": mired, "tint": tint}, user_wb=mult)
        stats_by = {key: image_stats(image, scene["focus"]) for key, image in variants.items()}
        report["axis2"][sid] = {"stats": stats_by, "wb": wb_info,
                                "delta_vs_notint": {k: region_delta(stats_by["wb-camera-notint"], v)
                                                    for k, v in stats_by.items() if k != "wb-camera-notint"}}
        # Axis 3: manual-ROI local exposure vs hue-band luminance lift, on the neutral-tint foundation.
        roi = render_foundation(base, scene, neutral_look, local_ev=.5)
        hue = render_foundation(base, scene, neutral_look, luma_gain=skin_hue_gain(base, .5))
        s3 = {"none": stats_by["wb-camera-notint"], "roi": image_stats(roi, scene["focus"]),
              "hueband": image_stats(hue, scene["focus"])}
        report["axis3"][sid] = {"stats": s3, "delta": {k: region_delta(s3["none"], s3[k]) for k in ("roi", "hueband")}}
        variants["skin-roi"], variants["skin-hueband"] = roi, hue
        # Axis 4 input: centre top-percentile luminance of the fixed baseline decode.
        h, w = base.shape[:2]
        centre = base[h // 4:3 * h // 4, w // 4:3 * w // 4] @ LUMA
        report["axis4"][sid] = {"c97": float(np.percentile(centre, 97)), "c90": float(np.percentile(centre, 90)),
                                "skin": float(np.median(box_pixels(base, scene["focus"]["skin"]) @ LUMA)),
                                "p50": float(np.median(base @ LUMA)), "manual_ev": scene["base_ev"] + look["ev"]}
        for key, image in variants.items():
            thumb(image, out / f"{sid}-{key}.jpg")
    return report


def fit_exposure(axis4: dict) -> dict:
    """Leave-one-out residuals of 'manual EV = a + slope * log2(metric)' for two metering metrics."""
    ids = list(axis4)
    y = np.array([axis4[i]["manual_ev"] for i in ids])
    result = {}
    for metric in ("c97", "skin"):
        x = np.array([math.log2(axis4[i][metric]) for i in ids])
        for slope in (-.40, -1.):
            intercept = float(np.mean(y - slope * x))
            loo = [y[k] - (np.mean(np.delete(y - slope * x, k)) + slope * x[k]) for k in range(len(ids))]
            result[f"{metric}_{slope:+.2f}"] = {"metric": metric, "slope": slope, "intercept": intercept,
                                                "loo_residual": dict(zip(ids, np.round(loo, 3).tolist()))}
    return result


# ---------- report ----------

def fmt(value, digits=1):
    return "–" if value is None else f"{value:.{digits}f}"


def write_page(out: Path, results: dict) -> None:
    parts = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>第6回 色差の段階診断</title>",
             "<style>body{background:#1d2023;color:#eee;font:15px/1.7 system-ui,sans-serif;max-width:1400px;margin:auto;padding:24px}"
             "img{width:100%;display:block}figure{margin:0}figcaption{font-size:12px;color:#bcc}"
             ".row{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:8px}"
             "table{border-collapse:collapse;font-size:13px}td,th{border:1px solid #444;padding:3px 8px;text-align:right}"
             "th{background:#2a2e31}td:first-child,th:first-child{text-align:left}.big{color:#ffcf7a}</style>",
             "<h1>第6回 色差の段階診断（非公開）</h1>",
             "<p>Lightroom書き出しは診断用参照としてのみ使用し、処理へ取り込まず自動fitもしない。値はOKLab（L・C・色相°）。"
             "「当方の基準」は肌領域の中央輝度を Camera Portrait 版に合わせた露出で表示・測定。</p>"]
    for key, section in results.items():
        parts.append(section)
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8")


def axis1_html(report: dict, rows: list[dict]) -> str:
    parts = ["<h2>軸1：差はどの段階で生じるか</h2>"]
    for sid, item in report.items():
        parts.append(f"<h3>{sid.upper()}　<small>{html.escape(item['note'][:80])}</small></h3><div class='row'>")
        for key in item["order"]:
            parts.append(f"<figure><img src='{sid}-{key}.jpg'><figcaption>{STAGE_NAMES.get(key, key)}</figcaption></figure>")
        parts.append("</div><table><tr><th>段階</th>" + "".join(
            f"<th>{r} 色相</th><th>{r} C</th><th>{r} L</th>" for r in REGIONS) + "<th>暗部C</th></tr>")
        for key in item["order"]:
            s = item["stats"][key]
            parts.append(f"<tr><td>{STAGE_NAMES.get(key, key)}</td>" + "".join(
                f"<td>{fmt(s[r]['hue'], 0)}</td><td>{fmt(s[r]['C'], 3)}</td><td>{fmt(s[r]['L'], 2)}</td>" for r in REGIONS)
                + f"<td>{fmt(s['shadows_all']['C'], 3)}</td></tr>")
        parts.append(f"</table><p>当方基準の露出合わせ量：{item['ours_exposure_match_ev']:+.2f}EV</p>")
    parts.append("<h3>段階ごとの変化（色相差° / 彩度比 / ΔL）、|色相差|≥10° か 彩度比が0.8未満・1.25超を強調</h3>"
                 "<table><tr><th>シーン</th><th>領域</th><th>段階</th><th>色相差</th><th>彩度比</th><th>ΔL</th></tr>")
    for row in rows:
        hot = (row["hue_shift"] is not None and abs(row["hue_shift"]) >= 10) or (
            row["chroma_ratio"] is not None and not .8 <= row["chroma_ratio"] <= 1.25)
        cls = " class='big'" if hot else ""
        parts.append(f"<tr{cls}><td>{row['scene'].upper()}</td><td>{row['region']}</td>"
                     f"<td>{STAGE_NAMES[row['from']]} → {STAGE_NAMES[row['to']]}</td><td>{fmt(row['hue_shift'])}</td>"
                     f"<td>{fmt(row['chroma_ratio'], 2)}</td><td>{fmt(row['dL'], 3)}</td></tr>")
    parts.append("</table>")
    return "\n".join(parts)


def axes_html(rep: dict, fit: dict) -> str:
    names = {"r5-repro": "第5回①（再現）", "wb-camera-notint": "撮影時WB・暖色倍率なし", "wb-edit": "本人編集のWB差分",
             "wb-rule": "撮影時WB −550K", "skin-roi": "肌：手動ROI +0.5EV", "skin-hueband": "肌：色相帯の輝度+0.5EV"}
    parts = ["<h2>再現確認</h2><table><tr><th>シーン</th><th>第5回①との平均差(8bit)</th><th>99%点</th></tr>"]
    for sid, c in rep["check"].items():
        parts.append(f"<tr><td>{sid.upper()}</td><td>{c['r5_mean_abs_8bit']:.2f}</td><td>{c['r5_p99_abs_8bit']:.0f}</td></tr>")
    parts.append("</table><h2>軸2：RAW段階のWB（土台は第5回①、暖色倍率を外して比較）</h2>")
    for sid, item in rep["axis2"].items():
        keys = list(item["stats"])
        parts.append(f"<h3>{sid.upper()}</h3><div class='row'>" + "".join(
            f"<figure><img src='{sid}-{k}.jpg'><figcaption>{names[k]}</figcaption></figure>" for k in keys) + "</div>")
        parts.append("<table><tr><th>案</th>" + "".join(f"<th>{r} 色相</th><th>{r} C</th>" for r in REGIONS) + "<th>暗部C</th></tr>")
        for k in keys:
            s = item["stats"][k]
            parts.append(f"<tr><td>{names[k]}</td>" + "".join(f"<td>{fmt(s[r]['hue'], 0)}</td><td>{fmt(s[r]['C'], 3)}</td>" for r in REGIONS)
                         + f"<td>{fmt(s['shadows_all']['C'], 3)}</td></tr>")
        parts.append("</table>")
        for k, info in item["wb"].items():
            parts.append(f"<p>{names[k]}：当方の撮影時推定 {info['ours_as_shot'][0]:.0f}K/{info['ours_as_shot'][1]:+.0f} → "
                         f"{info['target'][0]:.0f}K/{info['target'][1]:+.0f}（LR差分 {info['lightroom_shift']['mired']:+.0f}mired, tint {info['lightroom_shift']['tint']:+.0f}）</p>")
    parts.append("<h2>軸3：肌の明るさの付け方（撮影時WB・暖色倍率なしの土台に対する変化）</h2>"
                 "<table><tr><th>シーン</th><th>方式</th>" + "".join(f"<th>{r} ΔL</th><th>{r} 色相差</th>" for r in REGIONS) + "</tr>")
    for sid, item in rep["axis3"].items():
        for k, d in item["delta"].items():
            parts.append(f"<tr><td>{sid.upper()}</td><td>{names['skin-' + k]}</td>" + "".join(
                f"<td>{d[r]['dL']:+.3f}</td><td>{fmt(d[r]['hue'])}</td>" for r in REGIONS) + "</tr>")
    parts.append("</table><div class='row'>" + "".join(
        f"<figure><img src='{sid}-skin-{k}.jpg'><figcaption>{sid.upper()} {names['skin-' + k]}</figcaption></figure>"
        for sid in rep["axis3"] for k in ("roi", "hueband")) + "</div>")
    parts.append("<h2>軸4：自動露出の候補（手動露出を測光値から再現できるか、診断のみ）</h2>"
                 "<p>モデル：手動EV = a + 傾き×log2(測光値)。各シーンを除いて a を決めた残差（LOO, EV）。"
                 "本人編集195組の傾向は傾き約−0.40。</p><table><tr><th>シーン</th><th>中央上位3%</th><th>肌領域中央値</th>"
                 "<th>手動EV</th>" + "".join(f"<th>{v['metric']} 傾き{v['slope']:+.1f}</th>" for v in fit.values()) + "</tr>")
    for sid, a4 in rep["axis4"].items():
        parts.append(f"<tr><td>{sid.upper()}</td><td>{a4['c97']:.3f}</td><td>{a4['skin']:.4f}</td><td>{a4['manual_ev']:+.2f}</td>"
                     + "".join(f"<td>{v['loo_residual'][sid]:+.2f}</td>" for v in fit.values()) + "</tr>")
    parts.append("</table>")
    return "\n".join(parts)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--livepicture", type=Path, required=True, help="authorized root containing adopted/")
    parser.add_argument("--references", type=Path, required=True, help="folder with Lightroom diagnostic exports")
    parser.add_argument("--r5", type=Path, required=True, help="round-5 comparison folder (recipe.json, settings, assets/)")
    parser.add_argument("--scenes", default="b,e,f,g", help="axis-1 scenes (need Lightroom exports)")
    parser.add_argument("--axes-scenes", default="b,d,e,f,g", help="axis 2-4 scenes")
    parser.add_argument("--axes", default="1,2")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    for name in ("livepicture", "references", "r5"):
        setattr(args, name, b.root_path(getattr(args, name)))
    output = args.output.absolute()
    if output.exists() or any(output.is_relative_to(getattr(args, n)) for n in ("livepicture", "references", "r5")):
        raise SystemExit("ERROR OUTPUT_EXISTS_OR_OVERLAPS_INPUT")
    recipe = json.loads((args.r5 / "recipe.json").read_text(encoding="utf-8"))
    look = json.loads((args.r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"]
    by_id = {scene["id"]: scene for scene in recipe["scenes"]}
    scenes1 = [by_id[i] for i in args.scenes.split(",")]
    scenes2 = [by_id[i] for i in args.axes_scenes.split(",")]
    before = {p: b.digest(p) for s in by_id.values() for p in scene_inputs(args, s).values()}
    output.mkdir(parents=True)
    results, record = {}, {"environment": b.versions(), "look": look}
    if "1" in args.axes:
        report1 = axis1(args, scenes1, output)
        rows = stage_table(report1)
        results["axis1"] = axis1_html(report1, rows)
        record["axis1"] = {"report": report1, "stages": rows}
    if "2" in args.axes:
        rep = axes234(args, scenes2, look, output)
        fit = fit_exposure(rep["axis4"])
        results["axes"] = axes_html(rep, fit)
        record["axes234"] = dict(rep, axis4_fit=fit)
    write_page(output, results)
    (output / "record.json").write_text(json.dumps(record, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "axes": args.axes}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
