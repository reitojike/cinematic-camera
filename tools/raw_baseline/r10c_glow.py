"""Private round-10c study: light-source bloom/halation strength x a light whole-image diffusion.

Follows the round-10 picks: bloom + halation liked overall, full diffusion (G2) sometimes better and
sometimes too much, grain mostly not wanted (so none here). Diagnostic comparison only.
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

r.WORK_LONG = 1600
BLOOM_LEVELS = {"B1": {"bloom": .55, "halation": .35}, "B2": {"bloom": .8, "halation": .55}}
DIFFUSION_LEVELS = {"D0": 0., "D1": .08, "D2": .15}  # G2 used .22 with a lower threshold
NAMES = {"B1": "ブルーム＋ハレーション（第10回G1）", "B2": "ブルーム＋ハレーション 強め",
         "D0": "ディフュージョンなし", "D1": "ディフュージョン 弱", "D2": "ディフュージョン 中"}


def setting(bloom: str, diffusion: str) -> dict:
    return dict(ic.GLOW_LEVELS["G1"], **BLOOM_LEVELS[bloom], diffusion=DIFFUSION_LEVELS[diffusion])


def page(out: Path, scenes: list[str]) -> None:
    parts = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>第10c回 にじみの強さ</title><style>"
             "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1700px;padding:20px}"
             ".grid{display:grid;grid-template-columns:150px repeat(3,1fr);gap:6px;align-items:center}"
             ".small{display:grid;grid-template-columns:repeat(7,1fr);gap:4px;max-width:1100px}"
             "img{width:100%;display:block;cursor:zoom-in}.lbl{font-size:13px;color:#cdd}"
             "#zoom{position:fixed;inset:0;background:#000d;display:none;align-items:center;justify-content:center}"
             "#zoom img{max-width:98vw;max-height:98vh;width:auto}</style>",
             "<h1>第10c回 にじみの強さ：ブルーム＋ハレーション × 弱いディフュージョン（非公開）</h1>",
             "<p>土台は第7回の確定ルック。粒子なし。行＝光源のブルーム＋ハレーションの強さ、列＝全体のディフュージョン"
             "（第10回のG2より弱い段階）。縮小表示の左端は第10回のG2（全体のディフュージョン）。画像クリックで拡大。</p>"]
    for sid in scenes:
        parts.append(f"<h2>{sid.upper()}</h2><div class='grid'><div></div>"
                     + "".join(f"<div class='lbl'>{NAMES[d]}</div>" for d in DIFFUSION_LEVELS))
        for bl in BLOOM_LEVELS:
            parts.append(f"<div class='lbl'>{NAMES[bl]}</div>"
                         + "".join(f"<img src='{sid}-{bl}-{d}.jpg'>" for d in DIFFUSION_LEVELS))
        parts.append("</div><p class='lbl'>縮小表示（左端＝第10回G2）</p><div class='small'>"
                     + f"<img src='{sid}-G2-s.jpg' title='第10回G2'>"
                     + "".join(f"<img src='{sid}-{bl}-{d}-s.jpg' title='{NAMES[bl]} / {NAMES[d]}'>"
                               for bl in BLOOM_LEVELS for d in DIFFUSION_LEVELS) + "</div>")
    parts.append("<div id='zoom' onclick=\"this.style.display='none'\"><img></div><script>"
                 "document.querySelectorAll('img').forEach(i=>i.onclick=()=>{const z=document.getElementById('zoom');"
                 "z.querySelector('img').src=i.src.replace('-s.jpg','.jpg');z.style.display='flex'})</script>")
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8")


def save(values: np.ndarray, path: Path) -> None:
    ic.save(values, None, path)
    with Image.open(path) as image:
        small = image.copy()
    small.thumbnail((360, 360), Image.Resampling.LANCZOS)
    small.save(path.with_name(path.stem + "-s.jpg"), "JPEG", quality=90)


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
        save(ic.glow(base, ic.GLOW_LEVELS["G2"]), out / f"{sid}-G2.jpg")
        for bl in BLOOM_LEVELS:
            for d in DIFFUSION_LEVELS:
                save(ic.glow(base, setting(bl, d)), out / f"{sid}-{bl}-{d}.jpg")
        print(sid, end=" ", flush=True)
    page(out, [s["id"] for s in scenes])
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "recipe": fin.RECIPE,
                                                 "settings": {f"{bl}-{d}": setting(bl, d) for bl in BLOOM_LEVELS
                                                              for d in DIFFUSION_LEVELS}},
                                                ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
