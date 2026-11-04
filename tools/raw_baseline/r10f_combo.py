"""Private round-10f study: combining the liked portrait effects (black mist, Orton, vignette).

Standard: round-7 base + bloom/halation B1 + light diffusion D1, no grain. Effects are chained in
the order black mist -> Orton -> vignette; the weak column mixes each step back at 55%. The last
column adds plain saturation (vibrance) in place of split toning. Writes an artifact-ready page.
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
import r10d_character as cd
import r10e_portrait as pe

r.WORK_LONG = 1400
WEAK = .55
VIBRANCE = .35
COMBOS = {
    "std": ("標準", [], 1., 0.),
    "BMVG": ("ブラック・ミスト＋周辺減光", ["BM", "VG"], 1., 0.),
    "ORVG": ("オートン＋周辺減光", ["OR", "VG"], 1., 0.),
    "ALL": ("3つ全部", ["BM", "OR", "VG"], 1., 0.),
    "ALLw": ("3つ全部 弱め", ["BM", "OR", "VG"], WEAK, 0.),
    "ALLs": ("3つ全部＋彩度", ["BM", "OR", "VG"], 1., VIBRANCE),
    "ALLsA": ("3つ全部＋彩度・白飛びで自動調整", ["BM", "OR", "VG"], "auto", VIBRANCE),
}
GLOW_STEPS = ("BM", "OR")  # the steps that add light; vignette and vibrance keep full strength
NOTES = {"BMVG": "黒は締めたまま光を大きくにじませ、端を暗くして中央に視線を集める",
         "ORVG": "輪郭を残したまま全体をふわっと光らせ、端を暗くする",
         "ALL": "ブラック・ミストとオートンを重ね、周辺減光で締める（各効果は第10e回と同じ強さ）",
         "ALLw": f"3つ全部を、それぞれ{int(WEAK * 100)}%の強さで重ねる",
         "ALLs": "3つ全部に、肌と白を避けた彩度アップ（スプリットトーンの代わり）を足す",
         "ALLsA": "白飛びしている画素（輝度が白を超える部分）が0.5%を超えると、ブラック・ミストとオートンを弱める。2.5%以上で半分"}


def clipped_fraction(values: np.ndarray) -> float:
    return float(((values @ r.LUMA) > 1).mean())


def auto_strength(values: np.ndarray) -> float:
    """Already over-white photos glow on their own, so add less light: 1 at <=0.5% clipped, 0.5 at >=2.5%."""
    return 1 - .5 * float(np.clip((clipped_fraction(values) - .005) / .02, 0, 1))


def combo(standard: np.ndarray, key: str, skin: np.ndarray, face: np.ndarray) -> np.ndarray:
    _, steps, strength, vibrance = COMBOS[key]
    out = standard
    auto = auto_strength(standard) if strength == "auto" else None
    for step in steps:
        applied = pe.effect(out, step, skin, face)
        weight = (auto if step in GLOW_STEPS else 1.) if auto is not None else strength
        out = out + weight * (applied - out)
    return k.vibrance(out, vibrance) if vibrance else out


def page(out: Path, scenes: list[str], strengths: dict) -> None:
    html = cd.PAGE_HEAD.replace("<title>ライブ写真 キャラクタールック</title>", "<title>ライブ写真 効果の組み合わせ</title>")
    html = html.replace("#stage{", "[hidden]{display:none!important}\n#stage{", 1) if "[hidden]{" not in html else html
    parts = [html, "<main><div class='intro'><h1>第10f回 好みの効果の組み合わせ</h1>",
             "<p>標準（第7回の土台＋ブルーム＋ハレーション＋弱いディフュージョン、粒子なし）の上に、第10e回で好みだった"
             "ブラック・ミスト、オートン効果、周辺減光を組み合わせた。重ねる順は、ブラック・ミスト→オートン→周辺減光。</p>",
             "<p>画像を押すと大きく表示します。表示中に画像をタップするたびに、同じシーンの標準と切り替わります。</p><dl class='picks'>"]
    parts += [f"<dt>{COMBOS[key][0]}</dt><dd>{note}</dd>" for key, note in NOTES.items()]
    parts.append("</dl></div>")
    for sid in scenes:
        info = strengths[sid]
        parts.append(f"<section><h2>{sid.upper()}</h2><p class='lbl'>白飛び {info['clipped'] * 100:.1f}%　"
                     f"自動調整でのにじみの強さ {info['auto'] * 100:.0f}%</p><div class='sheet'>")
        for key, (name, _, _, _) in COMBOS.items():
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
    strengths = {}
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
        face = pe.face_mask(standard.shape[:2], scene)
        strengths[sid] = {"clipped": round(clipped_fraction(standard), 4), "auto": round(auto_strength(standard), 3)}
        for key in COMBOS:
            cd.save(combo(standard, key, skin, face), out / f"{sid}-{key}.jpg")
        print(sid, end=" ", flush=True)
    page(out, [s["id"] for s in scenes], strengths)
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "recipe": fin.RECIPE, "standard": cd.STANDARD, "auto": strengths,
         "combos": {key: {"steps": v[1], "strength": v[2], "vibrance": v[3]} for key, v in COMBOS.items()}},
        ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
