"""Private round-10j check: the whole chain, stage by stage, with the round-10 look as decided.

The decided round-10 look in one function (`final_render`):
round-7 base -> standard glow (bloom/halation B1 + light diffusion D1) -> face guard on exposure
(round 10i "B") -> iconic layer (black mist + Orton + vignette + vibrance, light-adding steps
reduced on bright photos) -> skin chroma cap inside detected faces (round 10h "S") -> highlight
shoulder (round 10i).

The page shows, per photo: the plain decode, a simple correction from before the look study
(round-5 "clear" tone at as-shot white balance, same exposure), the round-7 look with the standard
glow, the final look, and the user's edit. Photos: the five tuning scenes, the 24 round-8 photos,
and photos used in no round so far (picked the same way, excluding everything already used).
Exposure is still matched to the user's edit (round 8); metering is a later step.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np

import baseline as b
import r6_diagnose as r
import r7_exposure as e
import r7_final as fin
import r7b_skin as sk
import r7c_punch as cp
import r8_generalize as g
import r10_iconic as ic
import r10d_character as cd
import r10h_refine as h
import r10i_highlights as hi

r.WORK_LONG = 1400
SMALL = 480
NEW_PHOTOS = 12
STAGES = (("plain", "元画像（補正なし）"), ("simple", "シンプルな補正（ルック検討前）"),
          ("look", "ルック適用（第7回＋標準のにじみ）"), ("final", "最終形（第10回）"), ("edited", "本人の編集"))
TOGGLE = {"plain": "final", "simple": "plain", "look": "simple", "final": "look", "edited": "final"}


def spread(items: list, count: int) -> list:
    """Up to `count` evenly spaced items, each picked once (all of them when there are fewer)."""
    n = min(count, len(items))
    return [items[int((i + .5) * len(items) / n)] for i in range(n)]


def final_render(decoded: np.ndarray, scene: dict, look: dict, choice: dict) -> tuple[np.ndarray, dict]:
    """The decided round-10 look. Returns the image and what the automatic steps did."""
    base = fin.render(decoded, scene, look, choice)
    skin = np.clip(ic.blur(sk.skin_weight(r.oklab(base)), .01), 0, 1)
    standard = ic.glow(base, cd.STANDARD)
    faces, boxes = h.face_area(standard)
    first = h.chosen_layer(standard, skin, h.CHOSEN_LEVEL, faces)
    factor = hi.face_cut(first, faces)
    layered = h.chosen_layer(standard * factor, skin, h.CHOSEN_LEVEL, faces) if factor < 1 else first
    info = {"faces": len(boxes), "face_cut_ev": float(np.log2(factor)), "glow_strength": h.auto_strength(standard * factor)}
    return hi.shoulder(layered), info


def stages(dng: Path, scene: dict, look: dict, choice: dict) -> tuple[dict, dict]:
    reference = r.load_dng(dng)
    mired = cp.WB_LEVELS[choice["wb"]]
    decoded = sk.decode(dng, reference, r.shifted_multipliers(dng, mired, 0.)[0] if mired else None)
    ev = scene["base_ev"] + sk.BRIGHTNESS_LEVELS[choice["brightness"]]
    simple = r.foundation(reference, np.zeros(reference.shape[:2], dtype=np.float32), ev, look)
    final, info = final_render(decoded, scene, look, choice)
    return {"plain": reference, "simple": simple, "look": ic.glow(fin.render(decoded, scene, look, choice), cd.STANDARD),
            "final": final}, info


def page(out: Path, rows: list[dict]) -> None:
    html = cd.PAGE_HEAD.replace("<title>ライブ写真 キャラクタールック</title>", "<title>ライブ写真 通し確認</title>")
    script = cd.PAGE_SCRIPT.replace("cur.name.split(' / ')[0]+' / 標準'", "cur.name.split(' / ')[0]+' / '+cur.stdname") \
        .replace("name:b.dataset.name}", "name:b.dataset.name,stdname:b.dataset.stdname}") \
        .replace("画像をタップで標準と切り替え", "画像をタップで隣の段階と切り替え")
    names = dict(STAGES)
    parts = [html, "<main><div class='intro'><h1>第10j回 通し確認</h1>",
             "<p>左から、元画像（RAWをそのまま現像）、シンプルな補正（ルック検討前の第5回の階調、撮影時のWB）、"
             "ルック適用（第7回の土台＋標準のにじみ）、最終形（第10回で決めたアイコニック層・顔の赤みの抑え「中」・白飛び対策B）、本人の編集。"
             "露出はどれも同じ（本人の編集の明るさに合わせたもの。アプリでの顔測光は次の段階）。</p>",
             "<p>前半は調整や確認に使ってきた写真、後半の「初めて使う写真」はこれまでどの回でも使っていない写真。</p>",
             "<p>画像を押すと大きく表示します。タップするたびに一つ前の段階と切り替わります（元画像と本人の編集は最終形と切り替え）。</p></div>"]
    group = None
    for row in rows:
        if row["group"] != group:
            group = row["group"]
            parts.append(f"<h2 class='group'>{group}</h2>")
        tag = row["tag"]
        notes = [f"顔 {row['faces']}"]
        if row["face_cut_ev"] < 0:
            notes.append(f"顔の白飛びで {row['face_cut_ev']:+.2f}EV")
        if row["glow_strength"] < 1:
            notes.append(f"にじみ {row['glow_strength'] * 100:.0f}%")
        parts.append(f"<section><h3>{tag.upper()}</h3><p class='lbl'>{'　'.join(notes)}</p><div class='sheet'>")
        for key, name in STAGES:
            if key == "edited" and not row["has_edit"]:
                continue
            other = TOGGLE[key]
            cls = " class='std'" if key == "final" else ""
            parts.append(f"<figure{cls}><button type='button' data-full='{tag}-{key}.jpg' data-std='{tag}-{other}.jpg' "
                         f"data-name='{tag.upper()} / {name}' data-stdname='{names[other]}' aria-label='{tag.upper()} {name}を拡大'>"
                         f"<img src='{tag}-{key}-s.jpg' alt='{tag.upper()} {name}' loading='lazy'></button>"
                         f"<figcaption>{name}</figcaption></figure>")
        parts.append("</div></section>")
    parts.append("</main>" + script)
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--livepicture", type=Path, required=True)
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--r8", type=Path, required=True)
    parser.add_argument("--face-model", type=Path, required=True, help="OpenCV Zoo YuNet ONNX (face_detection_yunet_2023mar)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    h.FACE_MODEL = args.face_model
    livepicture, r5, r8 = (b.root_path(p) for p in (args.livepicture, args.r5, args.r8))
    adopted = livepicture / "adopted"
    out = r.resolve_output(args.output, [livepicture, r5, r8])
    look = dict(json.loads((r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    g.LOOK = look
    recipe = json.loads((r5 / "recipe.json").read_text(encoding="utf-8"))
    tuned = {g.stem(Path(s["source"])) for s in recipe["scenes"]}
    originals = {g.stem(p): p for p in (adopted / "original").iterdir() if p.suffix.lower() == ".dng"}
    edited = [p for p in (adopted / "edited").glob("*.jpg") if g.stem(p) in originals]
    photos = g.pick(edited, tuned)
    previous = json.loads((r8 / "record.json").read_text(encoding="utf-8"))["photos"]
    if len(previous) != len(photos) or any(p["date"] != g.capture_date(ed) for p, ed in zip(previous, photos)):
        raise SystemExit("ERROR ROUND8_MISMATCH: the photo selection no longer matches the round-8 record")
    used = tuned | {g.stem(ed) for ed in photos}
    fresh = g.pick(edited, used, per_busy_date=1)
    fresh = spread(fresh, NEW_PHOTOS)
    if used & {g.stem(ed) for ed in fresh}:
        raise SystemExit("ERROR REUSED_PHOTO")
    jobs = [("調整に使ったシーン", s["id"], livepicture / s["source"], s, fin.chosen(s["id"]), None)
            for s in recipe["scenes"] if s["id"] in "bdefg"]
    jobs += [("汎用性の確認に使った写真（第8回〜）", p["tag"], originals[g.stem(ed)], {"skin": [], "base_ev": p["base_ev"]},
              fin.chosen("_default"), ed) for p, ed in zip(previous, photos)]
    before = {p: b.digest(p) for *_, dng, _, _, ed in jobs for p in ([dng, ed] if ed else [dng])}
    before.update({p: b.digest(p) for ed in fresh for p in (ed, originals[g.stem(ed)])})
    out.mkdir(parents=True)
    for i, ed in enumerate(fresh, 1):  # exposure matched to the edit, as for the round-8 photos
        dng = originals[g.stem(ed)]
        reference = r.load_dng(dng)
        decoded = sk.decode(dng, reference, r.shifted_multipliers(dng, cp.WB_LEVELS[fin.chosen("_default")["wb"]], 0.)[0])
        size = (round(reference.shape[1] * SMALL / max(reference.shape[:2])), round(reference.shape[0] * SMALL / max(reference.shape[:2])))
        ev = g.match_ev(r.resize_linear(decoded, size), e.output_c97(r.load_jpeg(ed)))
        jobs.append(("初めて使う写真", f"n{i:02d}", dng, {"skin": [], "base_ev": ev}, fin.chosen("_default"), ed))
    rows = []
    for group, tag, dng, scene, choice, ed in jobs:
        images, info = stages(dng, scene, look, choice)
        for key, img in images.items():
            cd.save(img, out / f"{tag}-{key}.jpg")
        if ed is not None:
            cd.save(r.load_jpeg(ed), out / f"{tag}-edited.jpg")
        rows.append(dict(info, group=group, tag=tag, has_edit=ed is not None, base_ev=float(scene["base_ev"])))
        print(tag, end=" ", flush=True)
    page(out, rows)
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "recipe": fin.RECIPE, "standard": cd.STANDARD, "skin_level": h.CHOSEN_LEVEL,
         "highlights": {"shoulder": hi.SHOULDER, "face_limit": hi.FACE_LIMIT, "max_cut": hi.MAX_CUT}, "photos": rows},
        ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != hh for p, hh in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "photos": len(rows)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
