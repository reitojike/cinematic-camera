"""Private round-10g check: the chosen iconic layer on the round-8 generalisation photos.

Same photos and exposure as round 8 (picked across capture dates, exposure matched to the
user's edit), so only the iconic layer differs. Per photo: standard glow, the chosen layer
(black mist + Orton + vignette + vibrance, light-adding steps reduced on clipped photos), and
the user's edit for reference. Writes an artifact-ready page. Diagnostic comparison only.
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
import r7c_punch as cp
import r8_generalize as g
import r10_iconic as ic
import r10d_character as cd
import r10f_combo as cb

r.WORK_LONG = 1400
CHOSEN = "ALLsA"
COLUMNS = (("std", "標準（第7回＋標準のにじみ）"), ("look", "確定したアイコニック層"), ("edited", "本人の編集"))


def page(out: Path, rows: list[dict]) -> None:
    html = cd.PAGE_HEAD.replace("<title>ライブ写真 キャラクタールック</title>", "<title>ライブ写真 アイコニック層の汎用性</title>")
    parts = [html, "<main><div class='intro'><h1>第10g回 アイコニック層の汎用性の確認</h1>",
             f"<p>第8回の汎用性の確認と同じ{len(rows)}枚（調整に使っていない写真を撮影日ごとに自動選択）に、"
             "第10f回で選んだ組み合わせ（ブラック・ミスト＋オートン＋周辺減光＋彩度、白飛びが多い写真ではにじみを自動で弱める）をかけた。"
             "露出は第8回と同じ（本人の編集の明るさに合わせたもの）なので、違いはアイコニック層だけ。</p>",
             "<p>画像を押すと大きく表示します。表示中に画像をタップするたびに、同じ写真の標準と切り替わります。</p></div>"]
    for row in rows:
        tag = row["tag"]
        parts.append(f"<section><h2>{tag.upper()}</h2><p class='lbl'>{row['date']}　白飛び {row['clipped'] * 100:.1f}%　"
                     f"にじみの強さ {row['auto'] * 100:.0f}%</p><div class='sheet'>")
        for key, name in COLUMNS:
            cls = " class='std'" if key == "look" else ""
            parts.append(f"<figure{cls}><button type='button' data-full='{tag}-{key}.jpg' data-std='{tag}-std.jpg' "
                         f"data-name='{tag.upper()} / {name}' aria-label='{tag.upper()} {name}を拡大'>"
                         f"<img src='{tag}-{key}-s.jpg' alt='{tag.upper()} {name}' loading='lazy'></button>"
                         f"<figcaption>{name}</figcaption></figure>")
        parts.append("</div></section>")
    parts.append("</main>" + cd.PAGE_SCRIPT)
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adopted", type=Path, required=True, help="authorized folder with original/ and edited/")
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--r8", type=Path, required=True, help="round-8 generalisation output (record.json)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    adopted, r5, r8 = (b.root_path(p) for p in (args.adopted, args.r5, args.r8))
    out = r.resolve_output(args.output, [adopted, r5, r8])
    g.LOOK = dict(json.loads((r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                  rgb=[1., 1., 1.])
    tuned = {g.stem(Path(s["source"])) for s in json.loads((r5 / "recipe.json").read_text(encoding="utf-8"))["scenes"]}
    originals = {g.stem(p): p for p in (adopted / "original").iterdir() if p.suffix.lower() == ".dng"}
    edited = [p for p in (adopted / "edited").glob("*.jpg") if g.stem(p) in originals]
    photos = g.pick(edited, tuned)
    previous = json.loads((r8 / "record.json").read_text(encoding="utf-8"))["photos"]
    if len(previous) != len(photos) or any(p["date"] != g.capture_date(ed) for p, ed in zip(previous, photos)):
        raise SystemExit("ERROR ROUND8_MISMATCH: the photo selection no longer matches the round-8 record")
    before = {p: b.digest(p) for ed in photos for p in (ed, originals[g.stem(ed)])}
    out.mkdir(parents=True)
    rows = []
    for prev, ed in zip(previous, photos):
        dng = originals[g.stem(ed)]
        reference = r.load_dng(dng)
        mult = r.shifted_multipliers(dng, cp.WB_LEVELS[fin.chosen("_default")["wb"]], 0.)[0]
        base = g.look_render(sk.decode(dng, reference, mult), prev["base_ev"])
        skin = np.clip(ic.blur(sk.skin_weight(r.oklab(base)), .01), 0, 1)
        standard = ic.glow(base, cd.STANDARD)
        no_face = np.zeros(standard.shape[:2], dtype=np.float32)  # the chosen combo does not use the face mask
        tag = prev["tag"]
        cd.save(standard, out / f"{tag}-std.jpg")
        cd.save(cb.combo(standard, CHOSEN, skin, no_face), out / f"{tag}-look.jpg")
        cd.save(r.load_jpeg(ed), out / f"{tag}-edited.jpg")
        rows.append({"tag": tag, "date": prev["date"][:10].replace(":", "-"),
                     "clipped": round(cb.clipped_fraction(standard), 4), "auto": round(cb.auto_strength(standard), 3)})
        print(tag, end=" ", flush=True)
    page(out, rows)
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "recipe": fin.RECIPE, "standard": cd.STANDARD, "combo": CHOSEN, "photos": rows},
        ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "photos": len(rows)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
