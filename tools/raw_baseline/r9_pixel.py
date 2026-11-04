"""Private round-9 study: develop Pixel 9a DNGs per the DNG spec, then apply the round-7 look.

Pixel DNGs differ from the SIGMA fp files the look was tuned on: the as-shot white is stored
as AsShotWhiteXY, the embedded profile uses ForwardMatrix1/2 and a 3-D ProfileLookTable, and
the CFA image carries GainMap opcodes (lens shading). This module reads only those tags,
applies them with numpy, and keeps LibRaw for demosaicing. Diagnostic only.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import struct
import sys

import numpy as np
import rawpy

import baseline as b
import r6_diagnose as r
import r7_final as fin
import r7_look as k
import r7b_skin as sk
import r7c_punch as cp

r.WORK_LONG = 1600
TYPES = {1: ("B", 1), 2: ("s", 1), 3: ("H", 2), 4: ("I", 4), 5: ("II", 8), 7: ("B", 1), 10: ("ii", 8), 11: ("f", 4), 12: ("d", 8)}
IFD0_TAGS = {50721: "cm1", 50722: "cm2", 50964: "fm1", 50965: "fm2", 50778: "illum1", 50779: "illum2",
             50729: "white_xy", 50728: "neutral", 50730: "baseline_exposure", 50981: "look_dims", 50982: "look",
             51108: "look_encoding", 50937: "hsm_dims", 50938: "hsm1", 50939: "hsm2", 330: "subifds"}


# ---------- TIFF reading ----------

class Tiff:
    def __init__(self, path: Path):
        self.data = path.read_bytes()
        self.e = "<" if self.data[:2] == b"II" else ">"

    def ifd(self, offset: int) -> dict[int, tuple[int, int, bytes]]:
        n = struct.unpack_from(self.e + "H", self.data, offset)[0]
        out = {}
        for i in range(n):
            tag, kind, count, raw = struct.unpack_from(self.e + "HHI4s", self.data, offset + 2 + 12 * i)
            out[tag] = (kind, count, raw)
        return out

    def blob(self, entry: tuple[int, int, bytes]) -> bytes:
        kind, count, raw = entry
        size = TYPES.get(kind, ("B", 1))[1] * count
        if size <= 4:
            return raw[:size]
        start = struct.unpack(self.e + "I", raw)[0]
        return self.data[start:start + size]

    def values(self, entry: tuple[int, int, bytes]) -> tuple:
        kind, count, _ = entry
        fmt, _ = TYPES[kind]
        vals = struct.unpack(self.e + fmt * count, self.blob(entry))
        if kind in (5, 10):
            vals = tuple(vals[i] / vals[i + 1] if vals[i + 1] else 0. for i in range(0, len(vals), 2))
        return vals


def read_profile(path: Path) -> tuple[dict, list[bytes]]:
    """IFD0 colour tags plus the OpcodeList2 blobs of the CFA image (IFD0 itself or a sub-IFD)."""
    tiff = Tiff(path)
    ifd0 = tiff.ifd(struct.unpack_from(tiff.e + "I", tiff.data, 4)[0])
    tags = {name: tiff.values(ifd0[tag]) for tag, name in IFD0_TAGS.items() if tag in ifd0}
    opcodes = []
    # Single-frame DNGs (e.g. Android DngCreator) keep the CFA image and its opcodes in IFD0.
    if 262 in ifd0 and tiff.values(ifd0[262])[0] == 32803 and 51009 in ifd0:
        opcodes.append(tiff.blob(ifd0[51009]))
    for offset in tags.get("subifds", ()):
        sub = tiff.ifd(int(offset))
        if 262 in sub and tiff.values(sub[262])[0] == 32803 and 51009 in sub:
            opcodes.append(tiff.blob(sub[51009]))
    return tags, opcodes


# ---------- GainMap (DNG opcode 9) ----------

def parse_gain_maps(blob: bytes) -> list[dict]:
    """OpcodeList data is big-endian per the DNG spec regardless of the file's byte order."""
    count = struct.unpack(">I", blob[:4])[0]
    pos, maps = 4, []
    for _ in range(count):
        opcode, _version, _flags, size = struct.unpack(">IIII", blob[pos:pos + 16])
        body = blob[pos + 16:pos + 16 + size]
        if opcode == 9:
            top, left, bottom, right, plane, planes, row_pitch, col_pitch, points_v, points_h = struct.unpack(">10I", body[:40])
            spacing_v, spacing_h, origin_v, origin_h = struct.unpack(">4d", body[40:72])
            map_planes = struct.unpack(">I", body[72:76])[0]
            gains = np.frombuffer(body[76:76 + 4 * points_v * points_h * map_planes], dtype=">f4").astype(np.float32)
            maps.append({"area": (top, left, bottom, right), "pitch": (row_pitch, col_pitch),
                         "points": (points_v, points_h), "spacing": (spacing_v, spacing_h), "origin": (origin_v, origin_h),
                         "gains": gains.reshape(points_v, points_h, map_planes)[..., 0]})
        pos += 16 + size
    return maps


def apply_gain_maps(raw_image: np.ndarray, maps: list[dict], black: float, white: float) -> None:
    """In place: multiply (value - black) by the bilinearly interpolated gain at each CFA site."""
    h, w = raw_image.shape
    for gm in maps:
        top, left, bottom, right = gm["area"]
        rp, cpitch = gm["pitch"]
        rows = np.arange(top, min(bottom, h), rp)
        cols = np.arange(left, min(right, w), cpitch)
        # Map coordinates are relative to the full image (0..1), per the DNG spec.
        v = (rows / h - gm["origin"][0]) / gm["spacing"][0]
        u = (cols / w - gm["origin"][1]) / gm["spacing"][1]
        pv, ph = gm["points"]
        v = np.clip(v, 0, pv - 1)
        u = np.clip(u, 0, ph - 1)
        v0, u0 = np.floor(v).astype(int), np.floor(u).astype(int)
        v1, u1 = np.minimum(v0 + 1, pv - 1), np.minimum(u0 + 1, ph - 1)
        fv, fu = (v - v0)[:, None], (u - u0)[None, :]
        g = gm["gains"]
        gain = ((1 - fv) * ((1 - fu) * g[v0][:, u0] + fu * g[v0][:, u1]) + fv * ((1 - fu) * g[v1][:, u0] + fu * g[v1][:, u1]))
        block = raw_image[np.ix_(rows, cols)].astype(np.float32)
        corrected = black + (block - black) * gain
        raw_image[np.ix_(rows, cols)] = np.clip(np.rint(corrected), 0, white).astype(raw_image.dtype)


# ---------- colour ----------

def as_shot_white(tags: dict) -> np.ndarray:
    """As-shot white chromaticity from AsShotWhiteXY, or solved from AsShotNeutral (DngCreator)."""
    if "white_xy" in tags:
        return np.array(tags["white_xy"][:2])
    neutral = np.array(tags["neutral"][:3], dtype=float)
    white, _ = r.neutral_white(tags, neutral / neutral.max())
    return white


def neutral_from_white_xy(tags: dict) -> tuple[np.ndarray, float]:
    xy = as_shot_white(tags)
    weight = r.profile_weight(r.xy_to_cct(xy), tags)
    cm = weight * np.array(tags["cm1"]).reshape(3, 3) + (1 - weight) * np.array(tags["cm2"]).reshape(3, 3)
    neutral = cm @ r.xy_to_xyz(xy)
    return neutral / neutral.max(), weight


def apply_look_table(prophoto: np.ndarray, table: np.ndarray, dims: tuple[int, int, int], srgb_encoded: bool) -> np.ndarray:
    """ProfileLookTable in linear ProPhoto HSV: trilinear over hue (wrapping), saturation and value."""
    hue_divs, sat_divs, val_divs = dims
    rgb = np.maximum(prophoto, 0)
    value = rgb.max(-1)
    low = rgb.min(-1)
    delta = value - low
    sat = np.where(value > 0, delta / np.maximum(value, 1e-12), 0)
    safe = np.maximum(delta, 1e-12)
    rr, gg, bb = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    hue = np.where(value == rr, (gg - bb) / safe, np.where(value == gg, 2 + (bb - rr) / safe, 4 + (rr - gg) / safe))
    hue = np.where(delta > 0, (hue % 6) * 60, 0.)
    v_axis = np.clip(value, 0, 1)
    if srgb_encoded:
        v_axis = np.where(v_axis <= .0031308, 12.92 * v_axis, 1.055 * v_axis ** (1 / 2.4) - .055)
    entries = table.reshape(val_divs, hue_divs, sat_divs, 3)  # DNG order: value, hue, saturation
    hf = hue / 360 * hue_divs
    h0 = np.floor(hf).astype(int) % hue_divs
    h1 = (h0 + 1) % hue_divs
    hw = (hf - np.floor(hf))[..., None]
    sf = sat * (sat_divs - 1)
    s0 = np.clip(np.floor(sf).astype(int), 0, sat_divs - 1)
    s1 = np.minimum(s0 + 1, sat_divs - 1)
    sw = (sf - np.floor(sf))[..., None]
    vf = v_axis * (val_divs - 1)
    z0 = np.clip(np.floor(vf).astype(int), 0, val_divs - 1)
    z1 = np.minimum(z0 + 1, val_divs - 1)
    zw = (vf - np.floor(vf))[..., None]

    def plane(z):
        return ((1 - hw) * ((1 - sw) * entries[z, h0, s0] + sw * entries[z, h0, s1])
                + hw * ((1 - sw) * entries[z, h1, s0] + sw * entries[z, h1, s1]))

    mixed = (1 - zw) * plane(z0) + zw * plane(z1)
    hue = (hue + mixed[..., 0]) % 360
    sat = np.clip(sat * mixed[..., 1], 0, 1)
    if srgb_encoded:
        # DNG SDK: scale the sRGB-encoded value, then decode back to linear (values above 1,
        # which the camera-normalised input should not contain, keep a linear scale).
        scaled = v_axis * mixed[..., 2]
        decoded = np.where(scaled <= .04045, scaled / 12.92, ((np.maximum(scaled, 0) + .055) / 1.055) ** 2.4)
        value = np.where(value <= 1, decoded, value * mixed[..., 2])
    else:
        value = value * mixed[..., 2]
    sector = hue / 60
    i = np.floor(sector).astype(int) % 6
    f = sector - np.floor(sector)
    p, q, t = value * (1 - sat), value * (1 - sat * f), value * (1 - sat * (1 - f))
    choices = [np.stack(c, -1) for c in ((value, t, p), (q, value, p), (p, value, t), (p, q, value), (t, p, value), (value, p, q))]
    out = np.zeros_like(rgb)
    for k_ in range(6):
        out = np.where((i == k_)[..., None], choices[k_], out)
    return out


def decode_pixel(path: Path, gain_map: bool = True, look_table: bool = True, mired_shift: float = 0.) -> tuple[np.ndarray, dict]:
    """Linear sRGB (D65) at r.WORK_LONG; as-shot WB (optionally shifted), FM + LookTable when present."""
    tags, opcode_blobs = read_profile(path)
    neutral, weight = neutral_from_white_xy(tags)
    if mired_shift:
        xy = as_shot_white(tags)
        temp, tint = r.xy_to_temp_tint(xy)
        shifted = r.temp_tint_to_xy(1e6 / (1e6 / temp + mired_shift), tint)
        weight = r.profile_weight(r.xy_to_cct(shifted), tags)
        cm = weight * np.array(tags["cm1"]).reshape(3, 3) + (1 - weight) * np.array(tags["cm2"]).reshape(3, 3)
        neutral = cm @ r.xy_to_xyz(shifted)
        neutral = neutral / neutral.max()
    maps = [m for blob in opcode_blobs for m in parse_gain_maps(blob)] if gain_map else []
    with rawpy.imread(str(path)) as raw:
        if maps:
            apply_gain_maps(raw.raw_image_visible, maps, float(np.mean(raw.black_level_per_channel)), float(raw.white_level))
        mult = (1 / neutral) / (1 / neutral)[1]
        camera = raw.postprocess(demosaic_algorithm=rawpy.DemosaicAlgorithm.AHD, output_color=rawpy.ColorSpace.raw,
                                 user_wb=[float(mult[0]), 1., float(mult[2]), 1.], use_camera_wb=False, use_auto_wb=False,
                                 output_bps=16, gamma=(1, 1), no_auto_bright=True, no_auto_scale=False, bright=1.0,
                                 exp_shift=1.0, exp_preserve_highlights=0.0, adjust_maximum_thr=0.0,
                                 highlight_mode=rawpy.HighlightMode.Clip,
                                 fbdd_noise_reduction=rawpy.FBDDNoiseReductionMode.Off, noise_thr=None, median_filter_passes=0)
    camera = camera.astype(np.float32) / 65535
    camera = r.resize_linear(camera, r.fit_size(camera.shape[1], camera.shape[0])).astype(np.float64)
    fm = weight * np.array(tags["fm1"]).reshape(3, 3) + (1 - weight) * np.array(tags["fm2"]).reshape(3, 3)
    xyz_d50 = camera @ fm.T  # white-balanced camera RGB -> XYZ(D50); FM maps [1,1,1] to the D50 white
    # DNG SDK order: BaselineExposure is applied before the value-dependent LookTable.
    xyz_d50 = xyz_d50 * 2 ** tags.get("baseline_exposure", (0.,))[0]
    if look_table and "look" in tags:
        prophoto = xyz_d50 @ np.linalg.inv(r.PROPHOTO_TO_XYZ_D50).T
        prophoto = apply_look_table(prophoto, np.array(tags["look"], dtype=np.float64), tuple(tags["look_dims"]),
                                    tags.get("look_encoding", (0,))[0] == 1)
        xyz_d50 = prophoto @ r.PROPHOTO_TO_XYZ_D50.T
    srgb = xyz_d50 @ (r.XYZ_TO_SRGB @ r.bradford(r.D50, r.D65)).T
    info = {"white_xy": as_shot_white(tags).tolist(), "matrix_weight_A": weight, "gain_maps": len(maps),
            "look_table": bool(look_table and "look" in tags), "baseline_exposure": tags.get("baseline_exposure", (0,))[0]}
    return srgb.astype(np.float32), info


# ---------- study ----------

def auto_base_ev(linear: np.ndarray) -> float:
    """Expose so the centre top-3% luminance lands near the fp edits' typical level (diagnostic)."""
    h, w = linear.shape[:2]
    c97 = float(np.percentile(linear[h // 4:3 * h // 4, w // 4:3 * w // 4] @ r.LUMA, 97))
    return float(np.clip(-.13 - .83 * np.log2(max(c97, 1e-5)), -3, 6)) - sk.BRIGHTNESS_LEVELS["E2"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pixel", type=Path, required=True, help="authorized folder of Pixel 9a DNGs")
    parser.add_argument("--r5", type=Path, required=True, help="round-5 folder (reproducer/settings.json)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    pixel, r5 = b.root_path(args.pixel), b.root_path(args.r5)
    out = r.resolve_output(args.output, [pixel, r5])
    look = dict(json.loads((r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    files = sorted(p for p in pixel.iterdir() if p.suffix.lower() == ".dng")
    before = {p: b.digest(p) for p in files}
    out.mkdir(parents=True)
    rows, record = [], []
    choice = fin.chosen("_default")
    for index, path in enumerate(files, 1):
        tag = f"x{index:02d}"
        plain, info = decode_pixel(path)
        no_gain, _ = decode_pixel(path, gain_map=False)
        cooler, _ = decode_pixel(path, mired_shift=cp.WB_LEVELS[choice["wb"]])
        ev = auto_base_ev(plain)
        scene = {"skin": [], "base_ev": ev}
        variants = {
            "profile": plain * 2 ** (ev + sk.BRIGHTNESS_LEVELS["E2"]),
            "nogain": no_gain * 2 ** (ev + sk.BRIGHTNESS_LEVELS["E2"]),
            "look": fin.render(cooler, scene, look, choice),
            "look-p0": fin.render(cooler, scene, look, dict(choice, profile="P0")),
        }
        for key, values in variants.items():
            k.save_srgb(values, out / f"{tag}-{key}.jpg")
        record.append({"tag": tag, "info": info, "base_ev": ev,
                       "stats": {key: r.image_stats(k.gamut_compress_luma(v, r.LUMA),
                                                    {n: [0, 0, 1, 1] for n in r.REGIONS}) for key, v in variants.items()}})
        labels = (("profile", "Googleの内蔵プロファイル（露出のみ）"), ("nogain", "周辺補正なし"),
                  ("look-p0", "確定ルック（プロファイル層なし）"), ("look", "第7回の確定ルック"))
        rows.append(f"<h2>{tag}</h2><div class='row'>" + "".join(
            f"<figure><img src='{tag}-{key}.jpg'><figcaption>{label}</figcaption></figure>" for key, label in labels) + "</div>")
        print(tag, end=" ", flush=True)
    (out / "index.html").write_text(
        "<!doctype html><html lang='ja'><meta charset='utf-8'><title>Pixel 9a の確認</title><style>"
        "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1700px;padding:20px}"
        ".row{display:grid;grid-template-columns:repeat(4,1fr);gap:8px}figure{margin:0}img{width:100%;display:block}"
        "figcaption{font-size:13px;color:#cdd}</style><h1>Pixel 9a の確認（非公開）</h1>"
        "<p>Google カメラの HDR+ による DNG を、DNG 仕様どおり（AsShotWhiteXY の WB、ForwardMatrix、ProfileLookTable、GainMap）に現像し、"
        "第7回の確定ルックをかけた。露出は fp の編集から学んだ規則で自動。本アプリの DNG とは作りが異なる可能性がある。</p>"
        + "".join(rows), encoding="utf-8")
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "recipe": fin.RECIPE, "photos": record},
                                                ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "photos": len(record)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
