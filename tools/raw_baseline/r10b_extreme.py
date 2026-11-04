"""Private round-10b study: deliberately extreme character looks on top of the round-7 base.

Each look is one bold idea pushed far, to find which kind of individuality is worth keeping
(then dial it back). Glow is added in display-referred linear light; grading runs in OKLab with
skin protected from colour moves; grain goes on the encoded image. Diagnostic comparison only.
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
import r10_iconic as ic

r.WORK_LONG = 1600
LOOKS = {
    "X1": {"name": "タングステン・フィルム", "note": "強い赤いハレーションと光のにじみ、青い影、温かいハイライト、黒は締める、粗めの粒子",
           "glow": {"kind": "bloom", "threshold": .55, "bloom": .8, "halation": 1.1},
           "contrast": .35, "chroma": 1.15, "shadow_tint": (235., .035), "highlight_tint": (60., .015),
           "grain": {"amount": .05, "size": .0018}},
    "X2": {"name": "ミスト", "note": "濃いディフュージョンで全体が発光、黒を浮かせて低コントラスト、ハイライトに薄いピンク",
           "glow": {"kind": "diffusion", "threshold": .35, "bloom": .5, "halation": .2, "diffusion": .6},
           "fade": .08, "contrast": -.2, "chroma": .85, "highlight_tint": (0., .012), "grain": ic.GRAIN_LEVELS["N1"]},
    "X3": {"name": "照明一色", "note": "画面の主な照明色へ、肌以外の色相を寄せて彩度を上げ、影もその色に染める",
           "glow": {"kind": "bloom", "threshold": .65, "bloom": .7, "halation": .3},
           "contrast": .2, "chroma": 1.3, "unify": .65, "shadow_unify": .05},
    "X4": {"name": "褪色フィルム", "note": "マットな黒と丸めたハイライト、緑→シアン・青→ティール・赤→オレンジのクロス、低彩度、強い粒子",
           "fade": .10, "shoulder": .9, "chroma": .78, "shadow_tint": (190., .02),
           "rotate": [(140., 60., 25.), (262., 40., -15.), (30., 30., 8.)], "grain": {"amount": .06, "size": .0022}},
    "X5": {"name": "ハイキー透明", "note": "+0.8EV、パステルの低彩度、薄いラベンダー、白い光のディフュージョン",
           "ev": .8, "glow": {"kind": "diffusion", "threshold": .5, "bloom": .5, "halation": .05, "diffusion": .4},
           "shoulder": 1., "fade": .05, "chroma": .55, "tint": (300., .012)},
    "X6": {"name": "光だけが色を持つ", "note": "影を深く沈めて暗部の色を抜き、明るい照明だけに色とにじみを残す",
           "glow": {"kind": "bloom", "threshold": .5, "bloom": 1.0, "halation": .5},
           "toe": .15, "light_only": True, "grain": {"amount": .045, "size": .0014}},
}
SKIN_PROTECT = .85


def lch(lab: np.ndarray):
    return lab[..., 0], np.hypot(lab[..., 1], lab[..., 2]), np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360


def from_lch(lightness, chroma, hue):
    rad = np.radians(hue)
    return np.stack([lightness, chroma * np.cos(rad), chroma * np.sin(rad)], -1).astype(np.float32)


def add_tint(lab: np.ndarray, hue: float, amount) -> np.ndarray:
    lab = lab.copy()
    lab[..., 1] += amount * np.cos(np.radians(hue))
    lab[..., 2] += amount * np.sin(np.radians(hue))
    return lab


def dominant_hue(lab: np.ndarray, skin: np.ndarray) -> float:
    """Chroma-weighted mean hue of bright, saturated, non-skin pixels (the stage light)."""
    lightness, chroma, _ = lch(lab)
    weight = np.clip((chroma - .06) / .06, 0, 1) * np.clip((lightness - .25) / .2, 0, 1) * (1 - skin) * chroma
    return float(np.degrees(np.arctan2((weight * lab[..., 2]).sum(), (weight * lab[..., 1]).sum())) % 360)


def tone(lightness: np.ndarray, look: dict) -> np.ndarray:
    out = np.maximum(lightness, 0)
    if look.get("toe"):  # crush: L^2/(L+b), renormalised so 1 stays 1
        b0 = look["toe"]
        out = out * out / (out + b0) * (1 + b0)
    if look.get("contrast"):
        unit = np.clip(out, 0, 1)
        out = np.where(out <= 1, out + look["contrast"] * (unit * unit * (3 - 2 * unit) - unit), out)
    if look.get("shoulder"):
        w = look["shoulder"]
        out = out / (1 + (out / w) ** 3) ** (1 / 3) / (1 / (1 + w ** -3) ** (1 / 3))  # 1 maps to 1
    if look.get("fade"):
        f = look["fade"]
        out = f + (1 - f) * out
    return out


def grade(values: np.ndarray, look: dict, skin: np.ndarray) -> np.ndarray:
    if look.get("ev"):
        values = values * 2 ** look["ev"]
    if look.get("glow"):
        values = ic.glow(values, look["glow"])
    lab = r.oklab(values)
    lightness, chroma, hue = lch(lab)
    keep = 1 - SKIN_PROTECT * skin  # colour moves stay off faces
    keep_hue = 1 - np.clip(skin, 0, 1)  # hue moves can be large (toward the light colour), so none on skin
    new_l = tone(lightness, look)
    new_c, new_h = chroma.copy(), hue.copy()
    for centre, half, shift in look.get("rotate", []):
        new_h = new_h + shift * k.hue_band(hue, centre, half) * keep_hue
    if look.get("unify"):
        target = dominant_hue(lab, skin)
        delta = (target - hue + 180) % 360 - 180
        new_h = new_h + look["unify"] * delta * np.clip((chroma - .02) / .05, 0, 1) * keep_hue
    if look.get("light_only"):
        gate = np.clip((new_l - .35) / .3, 0, 1)
        new_c = new_c * np.maximum(.25 + 1.0 * gate, skin)
    if look.get("chroma"):
        new_c = new_c * (1 + (look["chroma"] - 1) * keep)
    out = from_lch(new_l, new_c, new_h)
    unit = np.clip(new_l, 0, 1)
    if look.get("shadow_tint"):
        out = add_tint(out, look["shadow_tint"][0], look["shadow_tint"][1] * (1 - unit) ** 2 * keep)
    if look.get("highlight_tint"):
        out = add_tint(out, look["highlight_tint"][0], look["highlight_tint"][1] * unit ** 2 * keep)
    if look.get("tint"):
        out = add_tint(out, look["tint"][0], look["tint"][1] * keep)
    if look.get("shadow_unify"):
        out = add_tint(out, dominant_hue(lab, skin), look["shadow_unify"] * (1 - unit) ** 1.5 * keep)
    return k.oklab_to_linear(out).astype(np.float32)


def save(values: np.ndarray, grain_setting, path: Path) -> None:
    ic.save(values, grain_setting, path)
    with Image.open(path) as image:
        small = image.copy()
    small.thumbnail((360, 360), Image.Resampling.LANCZOS)
    small.save(path.with_name(path.stem + "-s.jpg"), "JPEG", quality=90)


def page(out: Path, scenes: list[str]) -> None:
    columns = [("base", "第7回の土台", "")] + [(key, look["name"], look["note"]) for key, look in LOOKS.items()]
    parts = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>第10b回 極端な個性案</title><style>"
             "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1900px;padding:20px}"
             ".grid{display:grid;grid-template-columns:repeat(7,1fr);gap:6px}.small{display:grid;grid-template-columns:repeat(7,1fr);gap:4px;max-width:1300px}"
             "img{width:100%;display:block;cursor:zoom-in}.lbl{font-size:13px;color:#cdd}dt{font-weight:bold;margin-top:6px}"
             "#zoom{position:fixed;inset:0;background:#000d;display:none;align-items:center;justify-content:center}"
             "#zoom img{max-width:98vw;max-height:98vh;width:auto}</style>",
             "<h1>第10b回 極端な個性案（非公開）</h1>",
             "<p>土台は第7回の確定ルック。各案はひとつの考え方をわざと強く振ったもの。気になる方向を選んでから強さを戻す前提。"
             "肌の上では色の移動を弱めている。画像クリックで拡大。各シーンの下に、SNS縮小相当（長辺約360px）を並べた。</p><dl>"]
    parts += [f"<dt>{key} {look['name']}</dt><dd>{look['note']}</dd>" for key, look in LOOKS.items()]
    parts.append("</dl>")
    for sid in scenes:
        parts.append(f"<h2>{sid.upper()}</h2><div class='grid'>" + "".join(f"<div class='lbl'>{key} {name}</div>" if key != "base"
                     else f"<div class='lbl'>{name}</div>" for key, name, _ in columns))
        parts.append("".join(f"<img src='{sid}-{key}.jpg'>" for key, _, _ in columns) + "</div>")
        parts.append("<p class='lbl'>縮小表示</p><div class='small'>"
                     + "".join(f"<img src='{sid}-{key}-s.jpg' title='{name}'>" for key, name, _ in columns) + "</div>")
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
    hues = {}
    for scene in scenes:
        sid = scene["id"]
        choice = fin.chosen(sid)
        dng = args.livepicture / scene["source"]
        reference = r.load_dng(dng)
        mired = fin.cp.WB_LEVELS[choice["wb"]]
        decoded = sk.decode(dng, reference, r.shifted_multipliers(dng, mired, 0.)[0] if mired else None)
        base = fin.render(decoded, scene, look, choice)
        skin = np.clip(ic.blur(sk.skin_weight(r.oklab(base)), .01), 0, 1)
        hues[sid] = round(dominant_hue(r.oklab(base), skin), 1)
        save(base, None, out / f"{sid}-base.jpg")
        for key, setting in LOOKS.items():
            save(grade(base, setting, skin), setting.get("grain"), out / f"{sid}-{key}.jpg")
        print(sid, end=" ", flush=True)
    page(out, [s["id"] for s in scenes])
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "recipe": fin.RECIPE, "looks": LOOKS,
                                                 "dominant_hue": hues, "seed": ic.SEED}, ensure_ascii=False, indent=1),
                                     encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
