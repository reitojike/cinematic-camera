"""Private round-10d study: toned-down character looks on the new standard glow.

Standard (from the round-10c picks): bloom + halation B1 with light diffusion D1, no grain.
Character looks from round 10b, without grain, mixed back over the standard at two strengths
(like layer opacity). Writes an artifact-ready page (no doctype; images next to it).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image

import baseline as b
import r6_diagnose as r
import r7_final as fin
import r7b_skin as sk
import r10_iconic as ic
import r10b_extreme as xb
import r10c_glow as gc

r.WORK_LONG = 1400
STANDARD = gc.setting("B1", "D1")
VARIANTS = {"std": ("標準", None, 0.),
            "X2a": ("ミスト 弱", "X2", .35), "X2b": ("ミスト 中", "X2", .6),
            "X5a": ("ハイキー透明 弱", "X5", .35), "X5b": ("ハイキー透明 中", "X5", .6),
            "X3a": ("照明一色 中", "X3", .6), "X3b": ("照明一色 強", "X3", 1.),
            "X6a": ("光だけが色を持つ 中", "X6", .6), "X6b": ("光だけが色を持つ 強", "X6", 1.)}


def variant(base: np.ndarray, standard: np.ndarray, skin: np.ndarray, key: str) -> np.ndarray:
    _, look, strength = VARIANTS[key]
    if not look:
        return standard
    graded = xb.grade(base, {k: v for k, v in xb.LOOKS[look].items() if k != "grain"}, skin)
    return standard + strength * (graded - standard)


def save(values: np.ndarray, path: Path) -> None:
    ic.save(values, None, path)
    with Image.open(path) as image:
        small = image.copy()
    small.thumbnail((480, 480), Image.Resampling.LANCZOS)
    small.save(path.with_name(path.stem + "-s.jpg"), "JPEG", quality=88)


PAGE_HEAD = """<!DOCTYPE html>
<html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}</style>
<title>ライブ写真 キャラクタールック</title>
<meta name="claude:viewport-fit-sides" content="cover">
<style>
/* Layout: one column of scenes; each scene is a contact sheet of variants, tap to open a viewer; tap the image to toggle the standard. */
:root{--bg:#141517;--panel:#1d1f22;--line:#2c2f33;--fg:#e9e6e1;--muted:#9a9a96;--accent:#d9a46b;
 --display:"Zen Kaku Gothic New",system-ui,sans-serif;--body:"Noto Sans JP",system-ui,sans-serif;color-scheme:dark}
body{background:var(--bg);color:var(--fg);font:15px/1.75 var(--body);padding-block:24px 48px;
 padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
main{max-width:1500px;margin:0 auto;display:grid;gap:40px}
h1{font:700 clamp(22px,3vw,30px)/1.3 var(--display);margin:0;text-wrap:balance}
h2{font:700 20px/1.3 var(--display);margin:0;letter-spacing:.08em}
.intro{max-width:68ch;display:grid;gap:10px;color:var(--fg)}.intro p{margin:0}
.picks{display:grid;gap:4px;margin:0;padding:12px 16px;background:var(--panel);border:1px solid var(--line);border-radius:6px}
.picks dt{color:var(--accent);font-size:13px;letter-spacing:.04em}.picks dd{margin:0 0 6px}
section{display:grid;gap:12px}
.sheet{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:10px}
figure{margin:0;display:grid;gap:4px;min-width:0}
figure button{all:unset;cursor:zoom-in;display:block;border-radius:3px;overflow:hidden;outline-offset:2px}
figure button:focus-visible{outline:2px solid var(--accent)}
figure img{display:block;width:100%;height:auto;background:var(--panel)}
figcaption{font-size:13px;color:var(--muted)}figure.std figcaption{color:var(--accent)}
#viewer{position:fixed;inset:0;background:#0b0b0cf2;display:grid;grid-template-rows:auto 1fr;z-index:20;
 padding:env(safe-area-inset-top,0px) env(safe-area-inset-right,0px) env(safe-area-inset-bottom,0px) env(safe-area-inset-left,0px)}
#viewer .bar{display:flex;gap:12px;align-items:center;justify-content:space-between;padding:10px 16px;font-size:14px}
#viewer .bar span{color:var(--muted)}#viewer .bar b{color:var(--fg);font-weight:500}
#viewer button{background:var(--panel);color:var(--fg);border:1px solid var(--line);border-radius:4px;padding:6px 12px;font:inherit;cursor:pointer}
[hidden]{display:none!important}
#stage{display:flex;align-items:center;justify-content:center;min-height:0;touch-action:manipulation;cursor:pointer;user-select:none;-webkit-user-select:none}
#stage img{max-width:100%;max-height:100%;object-fit:contain;-webkit-touch-callout:none}
</style>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;500&family=Zen+Kaku+Gothic+New:wght@700&display=swap">
"""

PAGE_SCRIPT = """<div id="viewer" hidden><div class="bar"><div><b id="vname"></b> <span>画像をタップで標準と切り替え</span></div>
<button id="vclose" type="button">閉じる</button></div><div id="stage"><img id="vimg" alt=""></div></div>
<script>
const viewer=document.getElementById('viewer'),vimg=document.getElementById('vimg'),vname=document.getElementById('vname');
let cur=null,showingStd=false;
const show=()=>{if(!cur)return;vimg.src=showingStd?cur.std:cur.src;vname.textContent=showingStd?cur.name.split(' / ')[0]+' / 標準':cur.name;};
document.querySelectorAll('figure button').forEach(b=>b.addEventListener('click',()=>{
 cur={src:b.dataset.full,std:b.dataset.std,name:b.dataset.name};showingStd=false;new Image().src=cur.std;show();viewer.hidden=false;}));
const stage=document.getElementById('stage');
stage.addEventListener('click',()=>{if(cur){showingStd=!showingStd;show();}});
stage.addEventListener('contextmenu',e=>e.preventDefault());
document.getElementById('vclose').addEventListener('click',()=>{viewer.hidden=true;cur=null;});
document.addEventListener('keydown',e=>{if(e.key==='Escape'){viewer.hidden=true;cur=null;}});
</script>"""


def page(out: Path, scenes: list[str]) -> None:
    parts = [PAGE_HEAD, "<main><div class='intro'><h1>第10d回 キャラクタールック</h1>",
             "<p>標準は、第7回の土台に光源のブルーム＋ハレーションと弱いディフュージョンを重ねたもの（粒子なし）。"
             "第10b回の極端な案から粒子を外し、標準の上に弱い段階と中・強の段階で重ねた。肌の上では色の移動を止めている。</p>",
             "<p>画像を押すと大きく表示します。表示中に画像をタップするたびに、同じシーンの標準と切り替わります。</p>",
             "<dl class='picks'><dt>ここまでの選択</dt><dd>粒子はなし。ブルームの強さの差は分からない程度なので第10回G1のまま。"
             "ディフュージョンは弱を標準にし、B・E・Fは中も候補。</dd>"
             "<dt>今回見てほしいこと</dt><dd>ミストとハイキー透明は、狙いすぎに見えない強さがあるか。"
             "照明一色と光だけが色を持つは、使いたい場面と強さ。</dd></dl></div>"]
    for sid in scenes:
        parts.append(f"<section><h2>{sid.upper()}</h2><div class='sheet'>")
        for key, (name, _, _) in VARIANTS.items():
            cls = " class='std'" if key == "std" else ""
            parts.append(f"<figure{cls}><button type='button' data-full='{sid}-{key}.jpg' data-std='{sid}-std.jpg' "
                         f"data-name='{sid.upper()} / {name}' aria-label='{sid.upper()} {name}を拡大'>"
                         f"<img src='{sid}-{key}-s.jpg' alt='{sid.upper()} {name}' loading='lazy'></button>"
                         f"<figcaption>{name}</figcaption></figure>")
        parts.append("</div></section>")
    parts.append("</main>" + PAGE_SCRIPT)
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
        skin = np.clip(ic.blur(sk.skin_weight(r.oklab(base)), .01), 0, 1)
        standard = ic.glow(base, STANDARD)
        for key in VARIANTS:
            save(variant(base, standard, skin, key), out / f"{sid}-{key}.jpg")
        print(sid, end=" ", flush=True)
    page(out, [s["id"] for s in scenes])
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "recipe": fin.RECIPE, "standard": STANDARD,
         "variants": {k: {"look": v[1], "strength": v[2]} for k, v in VARIANTS.items()}}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
