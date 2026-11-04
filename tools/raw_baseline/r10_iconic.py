"""Private round-10 study: glow x grain on top of the round-7 base look.

Glow (rows): none / light-source bloom + halation / whole-image diffusion.
Grain (columns): none / fine / medium, luminance-weighted, sized relative to the output.
Glow is added in display-referred linear light before gamut mapping and is reduced over
skin; grain is added to the encoded image. Diagnostic comparison only.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageCms

import baseline as b
import r6_diagnose as r
import r7_final as fin
import r7_look as k
import r7b_skin as sk

r.WORK_LONG = 1600
GLOW_LEVELS = {"G0": None,
               "G1": {"kind": "bloom", "threshold": .70, "bloom": .55, "halation": .35},
               "G2": {"kind": "diffusion", "threshold": .45, "bloom": .35, "halation": .15, "diffusion": .22}}
GRAIN_LEVELS = {"N0": None, "N1": {"amount": .022, "size": .0009}, "N2": {"amount": .038, "size": .0016}}
HALATION_TINT = np.array([1.0, .32, .12], dtype=np.float32)
SEED = 20261104


def blur(channel: np.ndarray, radius_fraction: float) -> np.ndarray:
    """Approximate Gaussian blur with radius relative to the long side (box down / bilinear up).

    Bilinear (not bicubic) upsampling: bicubic overshoot rings negative around bright lights and
    would draw dark halos exactly where the glow should be.
    """
    h, w = channel.shape
    cell = max(radius_fraction * max(h, w), 1.)
    small = (max(2, round(w / cell)), max(2, round(h / cell)))
    down = Image.fromarray(channel.astype(np.float32), "F").resize(small, Image.Resampling.BOX)
    return np.asarray(down.resize((w, h), Image.Resampling.BILINEAR), dtype=np.float32)


def blur_rgb(image: np.ndarray, radius_fraction: float) -> np.ndarray:
    return np.stack([blur(image[..., c], radius_fraction) for c in range(3)], -1)


def glow(values: np.ndarray, setting: dict | None) -> np.ndarray:
    """Add light-source bloom/halation (and optional diffusion) in display-referred linear light."""
    if not setting:
        return values
    y = np.maximum(values @ r.LUMA, 0)
    t = setting["threshold"]
    # Soft knee: only the energy above the threshold spreads, so dark flat areas gain nothing.
    excess = np.clip((y - t) / (1 - t), 0, None) ** 1.5
    source = values * (excess / np.maximum(y, 1e-6))[..., None]
    bloom = sum(weight * blur_rgb(source, radius) for radius, weight in ((.006, .5), (.02, .3), (.06, .2)))
    halation = blur(excess.astype(np.float32), .012)[..., None] * HALATION_TINT
    skin = sk.skin_weight(r.oklab(values))[..., None]
    protect = 1 - .6 * np.clip(blur(skin[..., 0], .01), 0, 1)[..., None]
    out = values + (setting["bloom"] * bloom + setting["halation"] * halation) * protect
    if setting.get("diffusion"):
        soft = np.clip(blur_rgb(out, .03), 0, 1)
        # Screen-like blend on [0,1] values: lifts shadows a little and softens highlights, like a
        # mist filter. Already-saturated (>1) pixels get no change instead of a negative term.
        clipped = np.clip(out, 0, 1)
        out = out + setting["diffusion"] * (soft - clipped * soft) * protect
    return out


def grain(encoded: np.ndarray, setting: dict | None, seed: int = SEED) -> np.ndarray:
    """Monochrome, luminance-weighted grain on the 8-bit encoded image (peak in mid-tones)."""
    if not setting:
        return encoded
    h, w = encoded.shape[:2]
    rng = np.random.default_rng(seed)
    cell = max(setting["size"] * max(h, w), 1.)
    small = (max(2, round(w / cell)), max(2, round(h / cell)))
    noise = Image.fromarray(rng.standard_normal((small[1], small[0])).astype(np.float32), "F")
    noise = np.array(noise.resize((w, h), Image.Resampling.BICUBIC), dtype=np.float32)
    noise /= max(float(noise.std()), 1e-6)
    luma = (encoded.astype(np.float32) / 255) @ r.LUMA
    envelope = 4 * luma * (1 - luma)  # 0 at black/white, 1 at mid grey
    delta = setting["amount"] * 255 * envelope * noise
    return np.clip(np.rint(encoded.astype(np.float32) + delta[..., None]), 0, 255).astype(np.uint8)


def save(values: np.ndarray, grain_setting: dict | None, path: Path) -> None:
    encoded = r.linear_to_srgb8(k.gamut_compress_luma(values, r.LUMA))
    Image.fromarray(grain(encoded, grain_setting)).save(
        path, "JPEG", quality=94, icc_profile=ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())


def page(out: Path, scenes: list[dict]) -> None:
    names = {"G0": "にじみなし", "G1": "光源のブルーム＋ハレーション", "G2": "全体のディフュージョン",
             "N0": "粒子なし", "N1": "細かい粒子", "N2": "中くらいの粒子"}
    parts = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>第10回 にじみ×粒子</title><style>"
             "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1700px;padding:20px}"
             ".grid{display:grid;grid-template-columns:150px repeat(3,1fr);gap:6px;align-items:center}"
             ".small{display:grid;grid-template-columns:repeat(9,1fr);gap:4px;max-width:1100px}"
             "img{width:100%;display:block;cursor:zoom-in}.lbl{font-size:13px;color:#cdd}"
             "#zoom{position:fixed;inset:0;background:#000d;display:none;align-items:center;justify-content:center}"
             "#zoom img{max-width:98vw;max-height:98vh;width:auto}</style>",
             "<h1>第10回 アイコニック層：にじみ × 粒子（非公開）</h1>",
             "<p>土台は第7回の確定ルック（P2・I1・S2・E2・K1・W1、BのみI0・W0）。行＝にじみ、列＝粒子。"
             "顔の上ではにじみを弱めている。画像をクリックで等倍に近い大きさで表示。各シーンの下に、SNS縮小相当（長辺約360px）の9案を並べた。</p>"]
    for scene in scenes:
        sid = scene["id"]
        parts.append(f"<h2>{sid.upper()}</h2><div class='grid'><div></div>"
                     + "".join(f"<div class='lbl'>{names[n]}</div>" for n in GRAIN_LEVELS))
        for g in GLOW_LEVELS:
            parts.append(f"<div class='lbl'>{names[g]}</div>" + "".join(f"<img src='{sid}-{g}-{n}.jpg'>" for n in GRAIN_LEVELS))
        parts.append("</div><p class='lbl'>縮小表示</p><div class='small'>" + "".join(
            f"<img src='{sid}-{g}-{n}-s.jpg' title='{names[g]} / {names[n]}'>" for g in GLOW_LEVELS for n in GRAIN_LEVELS) + "</div>")
    parts.append("<div id='zoom' onclick=\"this.style.display='none'\"><img></div><script>"
                 "document.querySelectorAll('.grid img').forEach(i=>i.onclick=()=>{const z=document.getElementById('zoom');"
                 "z.querySelector('img').src=i.src;z.style.display='flex'})</script>")
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--livepicture", type=Path, required=True)
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--scenes", default="b,d,e,f,g")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    args.livepicture, args.r5 = b.root_path(args.livepicture), b.root_path(args.r5)
    out = r.resolve_output(args.output, [args.livepicture, args.r5])
    recipe = json.loads((args.r5 / "recipe.json").read_text(encoding="utf-8"))
    look = dict(json.loads((args.r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    by_id = {s["id"]: s for s in recipe["scenes"]}
    scenes = [by_id[i] for i in args.scenes.split(",")]
    before = {p: b.digest(p) for s in scenes for p in (args.livepicture / s["source"],)}
    out.mkdir(parents=True)
    for scene in scenes:
        sid = scene["id"]
        choice = fin.chosen(sid)
        dng = args.livepicture / scene["source"]
        reference = r.load_dng(dng)
        mired = fin.cp.WB_LEVELS[choice["wb"]]
        decoded = sk.decode(dng, reference, r.shifted_multipliers(dng, mired, 0.)[0] if mired else None)
        base = fin.render(decoded, scene, look, choice)
        for g, glow_setting in GLOW_LEVELS.items():
            glowed = glow(base, glow_setting)
            for n, grain_setting in GRAIN_LEVELS.items():
                save(glowed, grain_setting, out / f"{sid}-{g}-{n}.jpg")
                with Image.open(out / f"{sid}-{g}-{n}.jpg") as image:
                    small = image.copy()
                small.thumbnail((360, 360), Image.Resampling.LANCZOS)
                small.save(out / f"{sid}-{g}-{n}-s.jpg", "JPEG", quality=90)
        print(sid, end=" ", flush=True)
    page(out, scenes)
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "recipe": fin.RECIPE, "glow": GLOW_LEVELS,
                                                 "grain": GRAIN_LEVELS, "seed": SEED}, ensure_ascii=False, indent=1),
                                     encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
