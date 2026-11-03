"""Private round-7 study: profile layer x immersion layer grid on the DNG's own profile.

Profile layer: pull high-chroma blue-violet hues toward blue in OKLCh (skin hues untouched).
Immersion layer: local tone compression (highlights down / shadows up) and a vibrance-like
chroma lift that favours low-chroma and dark areas, with skin-hue protection.
Diagnostic comparison only; no reference is fitted and nothing becomes a default.
"""
from __future__ import annotations

import argparse
import html
import io
import json
import math
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageCms

import baseline as b
import r6_diagnose as r

r.WORK_LONG = 1600
PROFILE_LEVELS = {"P0": 0., "P1": 12., "P2": 24.}
IMMERSION_LEVELS = {"I0": None, "I1": {"highlights": .35, "shadows": .3, "vibrance": .6},
                    "I2": {"highlights": .55, "shadows": .5, "vibrance": 1.1}}
P3_LUMA = np.array([.2289746, .6917385, .0792869], dtype=np.float32)
SRGB_TO_P3 = np.linalg.inv(r.P3_TO_SRGB).astype(np.float32)


# ---------- OKLab inverse ----------

def oklab_to_linear(lab: np.ndarray) -> np.ndarray:
    m2_inv = np.array([[1., .3963377774, .2158037573], [1., -.1055613458, -.0638541728], [1., -.0894841775, -1.2914855480]],
                      dtype=np.float32)
    m1_inv = np.array([[4.0767416621, -3.3077115913, .2309699292], [-1.2684380046, 2.6097574011, -.3413193965],
                       [-.0041960863, -.7034186147, 1.7076147010]], dtype=np.float32)
    lms = (lab @ m2_inv.T) ** 3
    return lms @ m1_inv.T


def hue_band(hue: np.ndarray, centre: float, half: float) -> np.ndarray:
    t = np.clip(1 - np.abs((hue - centre + 180) % 360 - 180) / half, 0, 1)
    return t * t * (3 - 2 * t)


# ---------- layers ----------

def profile_layer(linear: np.ndarray, shift_deg: float) -> np.ndarray:
    """Rotate saturated blue-violet (OKLab hue ~250-350°) toward blue by up to shift_deg."""
    if not shift_deg:
        return linear
    lab = r.oklab(np.maximum(linear, 0))
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    weight = hue_band(hue, 300., 50.) * np.clip((chroma - .03) / .07, 0, 1)
    new_hue = np.radians(hue - shift_deg * weight)
    lab[..., 1], lab[..., 2] = chroma * np.cos(new_hue), chroma * np.sin(new_hue)
    return oklab_to_linear(lab).astype(np.float32)


def smooth_log_luma(y: np.ndarray, fraction: float = .04) -> np.ndarray:
    """Large-scale log-luminance base via box down/up sampling (edge-unaware, diagnostic)."""
    h, w = y.shape
    cell = fraction * max(h, w)
    small = (max(4, round(w / cell)), max(4, round(h / cell)))
    log_y = np.log2(np.maximum(y, 1e-5)).astype(np.float32)
    down = Image.fromarray(log_y, "F").resize(small, Image.Resampling.BOX)
    return np.asarray(down.resize((w, h), Image.Resampling.BICUBIC), dtype=np.float32)


def local_tone(linear: np.ndarray, highlights: float, shadows: float) -> np.ndarray:
    y = np.maximum(linear @ r.LUMA, 1e-5)
    base = smooth_log_luma(y)
    # Separate pivots: only the brightest large areas (spots, white costumes) are pulled down,
    # only areas below the median are lifted, so faces between them keep their exposure.
    high, low = float(np.percentile(base, 92)), float(np.median(base))
    above = np.maximum(base - high, 0)
    below = np.minimum(base - low, 0)
    new_base = base - highlights * above - shadows * below
    return linear * np.exp2(new_base - base)[..., None]


def vibrance(linear: np.ndarray, amount: float) -> np.ndarray:
    lab = r.oklab(np.maximum(linear, 0))
    lightness = lab[..., 0]
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    # Skin-like hues at any lightness (shaded legs/arms too), but only moderate chroma.
    skin = hue_band(hue, 50., 40.) * np.clip((chroma - .015) / .03, 0, 1) * np.clip(1 - (chroma - .13) / .05, 0, 1)
    # Leave near-neutrals and bright whites alone so white costumes do not turn yellow.
    neutral_guard = np.clip((chroma - .012) / .03, 0, 1) * np.clip(1 - (lightness - .72) / .2, 0, 1)
    gain = 1 + amount * neutral_guard * np.clip(1 - chroma / .22, 0, 1) * (1 + .6 * np.clip(1 - lightness / .6, 0, 1)) * (1 - .85 * skin)
    lab[..., 1] *= gain
    lab[..., 2] *= gain
    return oklab_to_linear(lab).astype(np.float32)


def gamut_compress_luma(values: np.ndarray, luma: np.ndarray) -> np.ndarray:
    center = np.clip(values @ luma, 0, 1)
    delta = values - center[..., None]
    limit = np.ones_like(center)
    for c in range(3):
        d = delta[..., c]
        bound = np.where(d > 0, (1 - center) / np.maximum(d, 1e-8), np.where(d < 0, -center / np.minimum(d, -1e-8), 1))
        limit = np.minimum(limit, bound)
    return np.clip(center[..., None] + delta * np.clip(limit, 0, 1)[..., None], 0, 1)


def render(linear: np.ndarray, scene: dict, look: dict, shift: float, immersion: dict | None, base_ev: float) -> np.ndarray:
    """Returns pre-gamut linear sRGB (may exceed [0,1]) after profile, immersion and foundation."""
    x = profile_layer(linear, shift)
    if immersion:
        x = local_tone(x, immersion["highlights"], immersion["shadows"])
    mask = r.skin_mask(x.shape[1], x.shape[0], scene["skin"])
    x = r.foundation(np.maximum(x, 0), mask, base_ev, look)
    if immersion:
        x = vibrance(x, immersion["vibrance"])
    return x


# ---------- output ----------

def save_srgb(values: np.ndarray, path: Path) -> None:
    Image.fromarray(r.linear_to_srgb8(gamut_compress_luma(values, r.LUMA))).save(
        path, "JPEG", quality=92, icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())


def save_p3(values: np.ndarray, path: Path, icc: bytes) -> None:
    p3 = gamut_compress_luma(values @ SRGB_TO_P3.T, P3_LUMA)
    Image.fromarray(b.display_pixels(p3)).save(path, "JPEG", quality=92, icc_profile=icc)


def measure(values: np.ndarray, focus: dict) -> dict:
    return r.image_stats(gamut_compress_luma(values, r.LUMA), focus)


# ---------- page ----------

def page(out: Path, scenes: list[dict], data: dict, exposure: dict | None) -> None:
    parts = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>第7回 プロファイル×浸る</title><style>"
             "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1600px;padding:20px}"
             ".grid{display:grid;grid-template-columns:110px repeat(3,1fr) 1fr;gap:6px;align-items:center}"
             ".refs{display:grid;grid-template-columns:repeat(3,1fr);gap:6px}"
             "img{width:100%;display:block;cursor:zoom-in}td,th{border:1px solid #444;padding:3px 7px;text-align:right}"
             "table{border-collapse:collapse;font-size:13px;margin:8px 0 28px}th{background:#262a2d}"
             "td:first-child{text-align:left}.lbl{font-size:13px;color:#cdd}#zoom{position:fixed;inset:0;background:#000d;"
             "display:none;align-items:center;justify-content:center}#zoom img{max-width:98vw;max-height:98vh;width:auto}</style>",
             "<h1>第7回 プロファイル層 × 浸る層（非公開）</h1>",
             "<p>行＝プロファイル層（青〜紫を青へ寄せる強さ P0:0° / P1:12° / P2:24°、肌の色相は対象外）。"
             "列＝浸る層（I0:なし / I1:中 / I2:強。ハイライト圧縮・シャドウ持ち上げ・自然な彩度）。土台はDNG内蔵プロファイル＋第5回①（暖色倍率なし）。"
             "露出は第5回の手動値で固定。画像をクリックで拡大。</p>"]
    for scene in scenes:
        sid = scene["id"]
        d = data[sid]
        parts.append(f"<h2>{sid.upper()}</h2><div class='grid'><div></div>"
                     + "".join(f"<div class='lbl'>{k}</div>" for k in IMMERSION_LEVELS) + "<div class='lbl'>参照</div>")
        for p_index, p in enumerate(PROFILE_LEVELS):
            parts.append(f"<div class='lbl'>{p}（{PROFILE_LEVELS[p]:.0f}°）</div>"
                         + "".join(f"<img src='{sid}-{p}-{i}.jpg'>" for i in IMMERSION_LEVELS))
            ref = ["edited", "r5", "wb"][p_index]
            label = {"r5": "第5回①", "edited": "本人の編集", "wb": "P1×I1＋WB約−550K"}[ref]
            parts.append(f"<figure style='margin:0'><img src='{sid}-ref-{ref}.jpg'><div class='lbl'>{label}</div></figure>")
        parts.append("</div>")
        if (out / f"{sid}-ref-auto.jpg").exists():
            parts.append(f"<div class='refs' style='grid-template-columns:repeat(4,1fr);margin-top:6px'>"
                         f"<figure style='margin:0'><img src='{sid}-ref-auto.jpg'><div class='lbl'>P2×I2・自動露出"
                         f"（{d['info']['auto_base_ev']:+.2f} / 手動 {d['info']['manual_base_ev']:+.2f}）</div></figure></div>")
        parts.append("<table><tr><th>案</th><th>照明 色相</th><th>照明 C</th><th>肌 色相</th><th>肌 C</th><th>肌 L</th>"
                     "<th>衣装 C</th><th>暗部 C</th><th>暗部全体 C</th></tr>")
        for key, s in d["stats"].items():
            parts.append(f"<tr><td>{key}</td><td>{r.fmt(s['light']['hue'], 0)}</td><td>{r.fmt(s['light']['C'], 3)}</td>"
                         f"<td>{r.fmt(s['skin']['hue'], 0)}</td><td>{r.fmt(s['skin']['C'], 3)}</td><td>{r.fmt(s['skin']['L'], 2)}</td>"
                         f"<td>{r.fmt(s['costume']['C'], 3)}</td><td>{r.fmt(s['dark']['C'], 3)}</td>"
                         f"<td>{r.fmt(s['shadows_all']['C'], 3)}</td></tr>")
        parts.append("</table>")
        if (out / f"{sid}-P2-I2-p3.jpg").exists():
            parts.append(f"<h3>{sid.upper()} 出力色域：sRGB と Display P3（P2×I2）</h3><div class='refs'>"
                         f"<figure style='margin:0'><img src='{sid}-P2-I2.jpg'><div class='lbl'>sRGB</div></figure>"
                         f"<figure style='margin:0'><img src='{sid}-P2-I2-p3.jpg'><div class='lbl'>Display P3（P3対応画面で差が出る）</div></figure></div>")
    if exposure:
        parts.append("<h2>露出：本人編集195組から学習した測光規則</h2><table><tr><th>測光値</th><th>傾き</th><th>平均誤差</th>"
                     "<th>90%点</th><th>±0.5EV以内</th></tr>")
        for f in sorted(exposure["fits"], key=lambda f: f["mae"])[:6]:
            parts.append(f"<tr><td>{f['metric']}</td><td>{f['slope']:+.2f}</td><td>{f['mae']:.2f}EV</td>"
                         f"<td>{f['p90_abs']:.2f}EV</td><td>{100 * f['within_0.5']:.0f}%</td></tr>")
        parts.append("</table>")
    parts.append("<div id='zoom' onclick=\"this.style.display='none'\"><img></div><script>"
                 "document.querySelectorAll('main img, .grid img, .refs img').forEach(i=>i.onclick=()=>{const z=document.getElementById('zoom');"
                 "z.querySelector('img').src=i.src;z.style.display='flex'})</script>")
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--livepicture", type=Path, required=True)
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--exposure-rule", type=Path, help="JSON from r7_exposure.py (optional)")
    parser.add_argument("--scenes", default="b,d,e,f,g")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    args.livepicture, args.r5 = b.root_path(args.livepicture), b.root_path(args.r5)
    args.references = args.r5  # scene_inputs needs the attribute; no Lightroom exports are read here
    out = args.output.absolute()
    if out.exists() or out.is_relative_to(args.livepicture) or out.is_relative_to(args.r5):
        raise SystemExit("ERROR OUTPUT_EXISTS_OR_OVERLAPS_INPUT")
    recipe = json.loads((args.r5 / "recipe.json").read_text(encoding="utf-8"))
    look = dict(json.loads((args.r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    by_id = {s["id"]: s for s in recipe["scenes"]}
    scenes = [by_id[i] for i in args.scenes.split(",")]
    exposure = json.loads(args.exposure_rule.read_text(encoding="utf-8")) if args.exposure_rule else None
    best_rule = min(exposure["fits"], key=lambda f: f["mae"]) if exposure else None
    before = {p: b.digest(p) for s in scenes for p in (args.livepicture / s["source"], args.livepicture / s["edited"])}
    out.mkdir(parents=True)
    data = {}
    for scene in scenes:
        sid = scene["id"]
        dng, edited = args.livepicture / scene["source"], args.livepicture / scene["edited"]
        with Image.open(edited) as image:
            p3_icc = image.info.get("icc_profile")
        profiled, info = r.decode_dng_profile(dng, hue_sat=True)
        reference = r.load_dng(dng)  # LibRaw baseline: only to keep round-5 exposure units
        if reference.shape != profiled.shape:
            reference = r.resize_linear(reference, (profiled.shape[1], profiled.shape[0]))
        scale = float(np.median(reference @ r.LUMA) / max(np.median(profiled @ r.LUMA), 1e-6))
        linear = profiled * scale
        stats = {}
        for p, shift in PROFILE_LEVELS.items():
            for i, imm in IMMERSION_LEVELS.items():
                values = render(linear, scene, look, shift, imm, scene["base_ev"])
                save_srgb(values, out / f"{sid}-{p}-{i}.jpg")
                stats[f"{p}-{i}"] = measure(values, scene["focus"])
                if p == "P2" and i == "I2" and p3_icc and "P3" in ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(p3_icc))):
                    save_p3(values, out / f"{sid}-P2-I2-p3.jpg", p3_icc)
        for ref, path in (("r5", args.r5 / "assets" / f"{sid}-clear.jpg"), ("edited", edited)):
            image = r.load_jpeg(path, crop=ref == "r5")
            image = r.resize_linear(image, (linear.shape[1], linear.shape[0])) if image.shape != linear.shape else image
            save_srgb(image, out / f"{sid}-ref-{ref}.jpg")
            stats[ref] = measure(image, scene["focus"])
        # WB reference: the user's typical habit (~550 K cooler at ~5000 K, i.e. +22 mired), tint unchanged.
        mult, wb_info = r.shifted_multipliers(dng, 22., 0.)
        cooler, _ = r.decode_dng_profile(dng, hue_sat=True, multipliers=mult)
        cooler *= float(np.median(reference @ r.LUMA) / max(np.median(cooler @ r.LUMA), 1e-6))
        values = render(cooler, scene, look, PROFILE_LEVELS["P1"], IMMERSION_LEVELS["I1"], scene["base_ev"])
        save_srgb(values, out / f"{sid}-ref-wb.jpg")
        stats["P1-I1-wb"] = measure(values, scene["focus"])
        info["wb_cooler"] = wb_info
        if best_rule:
            from r7_exposure import input_metrics
            metric = input_metrics(reference)[best_rule["metric"]]
            auto_ev = best_rule["intercept"] + best_rule["slope"] * math.log2(max(metric, 1e-6))
            values = render(linear, scene, look, PROFILE_LEVELS["P2"], IMMERSION_LEVELS["I2"], auto_ev)
            save_srgb(values, out / f"{sid}-ref-auto.jpg")
            stats["P2-I2-auto"] = measure(values, scene["focus"])
            info["auto_base_ev"], info["manual_base_ev"] = auto_ev, scene["base_ev"]
        data[sid] = {"stats": stats, "info": info, "scale": scale}
        print(sid, end=" ", flush=True)
    page(out, scenes, data, exposure)
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "look": look, "profile_levels": PROFILE_LEVELS,
                                                 "immersion_levels": IMMERSION_LEVELS, "exposure_rule": best_rule, "data": data},
                                                ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
