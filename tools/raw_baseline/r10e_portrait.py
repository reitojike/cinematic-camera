"""Private round-10e study: portrait/idol effects on top of the new standard glow.

Standard: round-7 base + bloom/halation B1 + light diffusion D1, no grain. Each column adds one
effect: black mist, Orton, split tone, vignette, blush, and a sharp face with a softer surround.
The face position comes from the first manual skin ellipse of each scene (on the phone it would
come from face detection). Writes an artifact-ready page. Diagnostic comparison only.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np

import baseline as b
import r6_diagnose as r
import r7_final as fin
import r7_look as k
import r7b_skin as sk
import r10_iconic as ic
import r10b_extreme as xb
import r10d_character as cd

r.WORK_LONG = 1400
EFFECTS = {
    "std": ("標準", ""),
    "BM": ("ブラック・ミスト", "黒は沈めたまま、中間より明るい部分だけを大きくにじませる"),
    "OR": ("オートン効果", "明るくぼかした画像を掛け合わせ、輪郭を残したまま全体をふわっと光らせる"),
    "ST": ("スプリットトーン", "影を青紫、ハイライトをピンク寄りの白に寄せる"),
    "VG": ("周辺減光", "画面の端を少し暗くして、視線を中央に集める"),
    "BC": ("血色", "肌に薄い赤みを足し、頬や唇の赤を少し上げる"),
    "FS": ("顔くっきり・周りやわらか", "顔の周りは輪郭を締め、それ以外をやわらかくぼかしてにじませる"),
}


def black_mist(values: np.ndarray, skin: np.ndarray) -> np.ndarray:
    """Wide glow from mid-highlights only; nothing is added where the image is dark."""
    y = np.maximum(values @ r.LUMA, 0)
    weight = np.clip((y - .18) / .5, 0, 1) ** 1.2
    source = values * weight[..., None]
    halo = .6 * ic.blur_rgb(source, .025) + .4 * ic.blur_rgb(source, .07)
    protect = 1 - .5 * skin[..., None]
    return values + .32 * halo * protect


def orton(values: np.ndarray) -> np.ndarray:
    """Screen the image with itself, blur that copy and multiply the two (encoded domain), then mix back."""
    encoded = linear_to_unit(values)
    bright = 1 - (1 - encoded) ** 2
    soft = np.clip(ic.blur_rgb(bright, .012), 0, 1)
    mixed = unit_to_linear(encoded + .45 * (bright * soft - encoded))
    return np.where(values > 1, np.maximum(values, mixed), mixed)  # keep over-white light for gamut mapping


def linear_to_unit(values: np.ndarray) -> np.ndarray:
    v = np.clip(values, 0, 1)
    return np.where(v <= .0031308, 12.92 * v, 1.055 * v ** (1 / 2.4) - .055).astype(np.float32)


def unit_to_linear(encoded: np.ndarray) -> np.ndarray:
    e = np.clip(encoded, 0, 1)
    return np.where(e <= .04045, e / 12.92, ((e + .055) / 1.055) ** 2.4).astype(np.float32)


def split_tone(values: np.ndarray, skin: np.ndarray) -> np.ndarray:
    lab = r.oklab(values)
    unit = np.clip(lab[..., 0], 0, 1)
    keep = 1 - .85 * skin
    lab = xb.add_tint(lab, 285., .035 * (1 - unit) ** 2 * keep)  # blue-violet shadows
    lab = xb.add_tint(lab, 355., .018 * unit ** 2 * (1 - .5 * skin))  # pink-white highlights
    return k.oklab_to_linear(lab).astype(np.float32)


def vignette(values: np.ndarray, strength: float = .35) -> np.ndarray:
    h, w = values.shape[:2]
    aspect = w / h
    x = (np.arange(w, dtype=np.float32) + .5) / w * 2 - 1
    y = (np.arange(h, dtype=np.float32) + .5) / h * 2 - 1
    radius = np.sqrt((x[None, :] * min(aspect, 1)) ** 2 + (y[:, None] / max(aspect, 1)) ** 2)
    t = np.clip((radius - .45) / .9, 0, 1)
    return values * (1 - strength * t * t * (3 - 2 * t))[..., None]


def blush(values: np.ndarray, skin: np.ndarray) -> np.ndarray:
    lab = r.oklab(values)
    lightness, chroma, hue = xb.lch(lab)
    near_skin = np.clip(ic.blur(skin, .02) * 1.5, 0, 1)
    reds = k.hue_band(hue, 15., 30.) * np.clip((chroma - .06) / .04, 0, 1) * near_skin  # lips, flushed cheeks
    out = xb.from_lch(lightness, chroma * (1 + .18 * reds), hue)
    return k.oklab_to_linear(xb.add_tint(out, 15., .016 * skin)).astype(np.float32)


def face_mask(shape: tuple[int, int], scene: dict) -> np.ndarray:
    cx, cy, rx, ry = scene["skin"][0]
    return r.skin_mask(shape[1], shape[0], [[cx, cy, rx * 1.7, ry * 1.7]])


def face_focus(values: np.ndarray, face: np.ndarray) -> np.ndarray:
    """Unsharp mask on the face, a gentle blur plus diffusion everywhere else."""
    detail = values - ic.blur_rgb(values, .003)
    sharp = values + .6 * detail
    soft = .4 * values + .6 * ic.blur_rgb(values, .004)
    soft = ic.glow(soft, dict(ic.GLOW_LEVELS["G1"], bloom=0., halation=0., diffusion=.18))
    f = face[..., None]
    return f * sharp + (1 - f) * soft


def effect(standard: np.ndarray, key: str, skin: np.ndarray, face: np.ndarray) -> np.ndarray:
    return {"std": lambda: standard, "BM": lambda: black_mist(standard, skin), "OR": lambda: orton(standard),
            "ST": lambda: split_tone(standard, skin), "VG": lambda: vignette(standard),
            "BC": lambda: blush(standard, skin), "FS": lambda: face_focus(standard, face)}[key]()


def page(out: Path, scenes: list[str]) -> None:
    html = cd.PAGE_HEAD.replace("<title>ライブ写真 キャラクタールック</title>", "<title>ライブ写真 ポートレート効果</title>")
    parts = [html, "<main><div class='intro'><h1>第10e回 アイドル・ポートレート向けの効果</h1>",
             "<p>標準（第7回の土台＋ブルーム＋ハレーション＋弱いディフュージョン、粒子なし）の上に、効果をひとつずつ重ねた。"
             "強さはどれも中くらいの初期値。肌の上では色の移動を弱めている。</p>",
             "<p>画像を押すと大きく表示します。表示中に画像をタップするたびに、同じシーンの標準と切り替わります。</p><dl class='picks'>"]
    parts += [f"<dt>{name}</dt><dd>{note}</dd>" for key, (name, note) in EFFECTS.items() if key != "std"]
    parts.append("<dt>注意</dt><dd>顔の位置は、手作業で付けた肌の範囲から取っている。アプリでは顔検出の結果を使う。</dd></dl></div>")
    for sid in scenes:
        parts.append(f"<section><h2>{sid.upper()}</h2><div class='sheet'>")
        for key, (name, _) in EFFECTS.items():
            cls = " class='std'" if key == "std" else ""
            parts.append(f"<figure{cls}><button type='button' data-full='{sid}-{key}.jpg' data-std='{sid}-std.jpg' "
                         f"data-name='{sid.upper()} / {name}' aria-label='{sid.upper()} {name}を拡大'>"
                         f"<img src='{sid}-{key}-s.jpg' alt='{sid.upper()} {name}' loading='lazy'></button>"
                         f"<figcaption>{name}</figcaption></figure>")
        parts.append("</div></section>")
    parts.append("</main>" + cd.PAGE_SCRIPT)
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8", newline="\n")


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
        skin = np.clip(ic.blur(sk.skin_weight(r.oklab(base)), .01), 0, 1)
        standard = ic.glow(base, cd.STANDARD)
        face = face_mask(standard.shape[:2], scene)
        for key in EFFECTS:
            cd.save(effect(standard, key, skin, face), out / f"{sid}-{key}.jpg")
        print(sid, end=" ", flush=True)
    page(out, [s["id"] for s in scenes])
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "recipe": fin.RECIPE, "standard": cd.STANDARD, "effects": list(EFFECTS)},
        ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
