"""Private round-10i study: blown whites (faces and costumes) after the chosen look.

Diagnosis: the RAW files are almost never clipped (sensor-saturated pixels are at most a few
hundredths of a percent on the flagged photos), so the white areas come from development: the
exposure, the light-adding iconic steps, and a hard clip at output. Two changes, compared against
the current look (round 10h, skin level "S"):
- A: a highlight shoulder. Display brightness above SHOULDER rolls off smoothly toward white
  instead of clipping, so over-white areas keep some gradation. Hue and chroma ratio are kept.
- B: A plus a face guard. When the brightest tenth of a detected face is above FACE_LIMIT
  (display value), the whole image is darkened just enough to bring it there, at most MAX_CUT EV.
Per photo: before, A, B, and the user's edit. Writes an artifact-ready page. Diagnostic only.
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
import r10h_refine as h

r.WORK_LONG = 1400
SHOULDER = .80  # display value where the roll-off starts
FACE_LIMIT = .90  # display value allowed for the brightest tenth of a face
MAX_CUT = .7  # EV
CHOSEN = "B"  # the user picked B (2026-11-04): the shoulder alone was hard to see; the darkened photos still read fine alone
COLUMNS = (("old", "前（第10h回の中）"), ("A", "A ハイライトをなだらかに"), ("B", "B A＋顔が飛ぶ写真だけ暗く"),
           ("edited", "本人の編集"))


def encode(y: np.ndarray) -> np.ndarray:
    """sRGB transfer without clipping, so over-white values stay above 1."""
    y = np.maximum(y, 0)
    return np.where(y <= .0031308, 12.92 * y, 1.055 * y ** (1 / 2.4) - .055)


def decode(u: np.ndarray) -> np.ndarray:
    u = np.maximum(u, 0)
    return np.where(u <= .04045, u / 12.92, ((u + .055) / 1.055) ** 2.4)


def shoulder(values: np.ndarray, start: float = SHOULDER) -> np.ndarray:
    """Roll display brightness above `start` toward 1 (slope 1 at the start, never reaching 1)."""
    y = np.maximum(values @ r.LUMA, 1e-6)
    u = encode(y)
    span = 1 - start
    rolled = np.where(u > start, start + span * np.tanh((u - start) / span), u)
    return (values * (decode(rolled) / y)[..., None]).astype(np.float32)


def face_cut(values: np.ndarray, faces: np.ndarray) -> float:
    """Exposure factor (<= 1) that brings the brightest tenth of the faces down to FACE_LIMIT."""
    inside = faces > .5
    if inside.sum() < 50:
        return 1.
    top = float(np.percentile(encode(values @ r.LUMA)[inside], 90))
    if top <= FACE_LIMIT:
        return 1.
    return float(max(decode(np.float64(FACE_LIMIT)) / decode(np.float64(top)), 2 ** -MAX_CUT))


def develop(decoded, scene, look, choice):
    base = fin.render(decoded, scene, look, choice)
    skin = np.clip(ic.blur(sk.skin_weight(r.oklab(base)), .01), 0, 1)
    standard = ic.glow(base, cd.STANDARD)
    faces, boxes = h.face_area(standard)
    old = h.chosen_layer(standard, skin, h.CHOSEN_LEVEL, faces)
    factor = face_cut(old, faces)
    a = shoulder(old)
    bb = shoulder(h.chosen_layer(standard * factor, skin, h.CHOSEN_LEVEL, faces)) if factor < 1 else a
    return {"old": old, "A": a, "B": bb}, faces, boxes, factor


def white_stats(values: np.ndarray, faces: np.ndarray) -> dict:
    display = encode(k.gamut_compress_luma(values, r.LUMA) @ r.LUMA)
    inside = faces > .5
    return {"white": float((display >= .98).mean()),
            "face_white": float((display[inside] >= .98).mean()) if inside.any() else None}


def page(out: Path, rows: list[dict]) -> None:
    html = cd.PAGE_HEAD.replace("<title>ライブ写真 キャラクタールック</title>", "<title>ライブ写真 白飛びの調整</title>")
    parts = [html, "<main><div class='intro'><h1>第10i回 白飛びの調整</h1>",
             "<p>RAWを調べると、白飛びが気になった写真でもセンサーで飽和している画素はほぼ0%だった。"
             "つまり白飛びは撮影ではなく現像（露出、にじみの効果、出力時に白で切り捨てる処理）で起きていて、現像で戻せる余地がある。</p>",
             f"<p>A: 明るさが{SHOULDER:.2f}を超える部分を、白で切らずになだらかに白へ近づける（白い衣装や顔の明るい部分に階調が残る）。"
             f"B: Aに加えて、検出した顔の明るい部分（上位1割）が{FACE_LIMIT:.2f}を超える写真だけ、顔がそこに収まるまで全体を暗くする（最大{MAX_CUT}EV）。"
             "赤みの抑えは第10h回の「中」のまま。</p>",
             "<p>見出しの数字は、白く飛んだ画素の割合（全体／顔）と、Bで暗くした量。画像を押すと大きく表示し、タップするたびに「前」と切り替わります。</p></div>"]
    for row in rows:
        tag = row["tag"]
        stats = "　".join(f"{key} {row[key]['white'] * 100:.1f}%" + (f"／顔 {row[key]['face_white'] * 100:.0f}%" if row[key]["face_white"] is not None else "")
                         for key in ("old", "A", "B"))
        parts.append(f"<section><h2>{tag.upper()}</h2><p class='lbl'>白飛び {stats}　B {row['ev']:+.2f}EV</p><div class='sheet'>")
        for key, name in COLUMNS:
            if key == "edited" and not row["has_edit"]:
                continue
            cls = " class='std'" if key == "old" else ""
            parts.append(f"<figure{cls}><button type='button' data-full='{tag}-{key}.jpg' data-std='{tag}-old.jpg' "
                         f"data-name='{tag.upper()} / {name}' aria-label='{tag.upper()} {name}を拡大'>"
                         f"<img src='{tag}-{key}-s.jpg' alt='{tag.upper()} {name}' loading='lazy'></button>"
                         f"<figcaption>{name}</figcaption></figure>")
        parts.append("</div></section>")
    parts.append("</main>" + cd.PAGE_SCRIPT.replace("' / 標準'", "' / 前'").replace("標準と切り替え", "前と切り替え"))
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--livepicture", type=Path, required=True)
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--r8", type=Path, required=True)
    parser.add_argument("--face-model", type=Path, required=True)
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
    jobs = [(s["id"], livepicture / s["source"], s, fin.chosen(s["id"]), None) for s in recipe["scenes"] if s["id"] in "bdefg"]
    jobs += [(p["tag"], originals[g.stem(ed)], {"skin": [], "base_ev": p["base_ev"]}, fin.chosen("_default"), ed)
             for p, ed in zip(previous, photos)]
    before = {p: b.digest(p) for _, dng, _, _, ed in jobs for p in ([dng, ed] if ed else [dng])}
    out.mkdir(parents=True)
    rows = []
    for tag, dng, scene, choice, ed in jobs:
        reference = r.load_dng(dng)
        mired = cp.WB_LEVELS[choice["wb"]]
        decoded = sk.decode(dng, reference, r.shifted_multipliers(dng, mired, 0.)[0] if mired else None)
        images, faces, boxes, factor = develop(decoded, scene, look, choice)
        row = {"tag": tag, "has_edit": ed is not None, "faces": len(boxes), "ev": float(np.log2(factor))}
        for key, img in images.items():
            cd.save(img, out / f"{tag}-{key}.jpg")
            row[key] = white_stats(img, faces)
        if ed is not None:
            cd.save(r.load_jpeg(ed), out / f"{tag}-edited.jpg")
        rows.append(row)
        print(tag, end=" ", flush=True)
    page(out, rows)
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "shoulder": SHOULDER, "face_limit": FACE_LIMIT, "max_cut": MAX_CUT,
         "skin_level": h.CHOSEN_LEVEL, "photos": rows}, ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != hh for p, hh in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "photos": len(rows)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
