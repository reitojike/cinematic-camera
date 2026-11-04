"""Private round-11 check (Issues #77, #86): Stage Clear v1 at strength 0 / 0.5 / 0.75 / 1 per lighting condition.

Photos: the five tuning scenes, the 24 round-8 photos (exposure matched to the user's edit, as
before) and this app's own Pixel 9a DNGs (automatic exposure; shot in a dark room lit by a monitor
showing red, blue and purple, so they stand in for stage light but are not a stage). Each photo is
put in one lighting condition (S01-S05) from the round-7 base, faces excluded; S06 lists the
highest-ISO photos separately. Per photo: the baseline (round-5 "clear" tone, as-shot white
balance, same exposure), Stage Clear 0 / 0.5 / 0.75 / 1, and the user's edit or the app's JPEG.
Diagnostic only; the default strength lives in stage_clear.DEFAULT_STRENGTH.
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
import r7c_punch as cp
import r8_generalize as g
import r9_app as app
import r9_pixel as px
import r10_iconic as ic
import r10d_character as cd
import r10h_refine as h
import r10i_highlights as hi
import r10j_final as j
import stage_clear as sc

r.WORK_LONG = 1400
PER_CONDITION = 3  # camera photos per condition (all Pixel photos are shown)
HIGH_ISO = 3200  # the highest ISO in the material at hand; no darker stage material yet
CONDITIONS = {"S01": "中立光（色の弱い照明）", "S02": "赤い光（赤・橙）", "S03": "青い光", "S04": "紫・マゼンタ",
              "S05": "スポット・逆光（明るい顔と暗い背景）", "S06": f"暗所・高感度（ISO {HIGH_ISO}、手元の素材の最高）"}
COLUMNS = (("base", "基準処理（第5回の階調・撮影時WB）"), ("s0", "Stage Clear 0"), ("s05", "Stage Clear 0.5（既定）"), ("s075", "Stage Clear 0.75"),
           ("s1", "Stage Clear 1（第10回の確定形）"), ("ref", "本人の編集／アプリのJPEG"))
TOGGLE = {"base": "s1", "s0": "base", "s05": "s0", "s075": "s05", "s1": "s075", "ref": "s1"}
COMPARED = (("s0", 0.), ("s05", .5), ("s075", .75), ("s1", 1.))


def iso(dng: Path, jpeg: Path | None) -> int | None:
    for path in (dng, jpeg):
        if path is None:
            continue
        try:
            with Image.open(path) as image:
                value = image.getexif().get_ifd(0x8769).get(0x8827)
        except Exception:  # DngCreator DNGs are not readable by PIL; the app JPEG has the same EXIF
            continue
        if value:
            return int(value[0] if isinstance(value, tuple) else value)
    return None


def upright_jpeg(path: Path) -> np.ndarray:
    """The JPEG turned by its EXIF orientation (the app writes landscape pixels with an orientation tag)."""
    with Image.open(path) as image:
        orientation = image.getexif().get(0x0112, 1)
    return np.ascontiguousarray(np.rot90(r.load_jpeg(path), {3: 2, 6: -1, 8: 1}.get(orientation, 0)))


def light_features(base: np.ndarray, faces: np.ndarray) -> dict:
    """Colour of the light outside faces, and how bright faces are against the background."""
    lab = r.oklab(base)
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    outside = faces < .1
    lit = outside & (chroma > .08) & (lab[..., 0] > .2)
    weights = chroma[lit]
    groups = {"S02": (hue[lit] < 70) | (hue[lit] >= 345), "S03": (hue[lit] >= 200) & (hue[lit] < 285),
              "S04": (hue[lit] >= 285) & (hue[lit] < 345)}
    share = {key: float(weights[mask].sum() / max(weights.sum(), 1e-6)) for key, mask in groups.items()}
    display = hi.encode(base @ r.LUMA)
    inside = faces > .5
    face_level = float(np.median(display[inside])) if inside.sum() >= 50 else None
    return {"coloured": float(lit.mean()), "share": share, "dark": float((display[outside] < .12).mean()),
            "face": face_level, "background": float(np.median(display[outside]))}


def condition(features: dict) -> str:
    face = features["face"]
    if face is not None and features["dark"] > .5 and face > .4 and face > 4 * max(features["background"], .02):
        return "S05"
    if features["coloured"] < .06:
        return "S01"
    key, value = max(features["share"].items(), key=lambda item: item[1])
    return key if value >= .45 else "S01"


def develop(decoded: np.ndarray, plain: np.ndarray, scene: dict, look: dict, choice: dict) -> tuple[dict, dict, dict]:
    base = fin.render(decoded, scene, look, choice)
    faces, boxes = h.face_area(ic.glow(base, cd.STANDARD))  # as in round 10j
    images = {"base": r.foundation(plain, np.zeros(plain.shape[:2], dtype=np.float32),
                                   scene["base_ev"] + sk.BRIGHTNESS_LEVELS[choice["brightness"]], look)}
    infos = {}
    for key, strength in COMPARED:
        images[key], infos[key] = sc.render(base, faces, strength)
    return images, dict(light_features(base, faces), faces=len(boxes)), infos


def page(out: Path, rows: list[dict]) -> None:
    html = cd.PAGE_HEAD.replace("<title>ライブ写真 キャラクタールック</title>", "<title>Stage Clear 強度比較</title>")
    script = cd.PAGE_SCRIPT.replace("cur.name.split(' / ')[0]+' / 標準'", "cur.name.split(' / ')[0]+' / '+cur.stdname") \
        .replace("name:b.dataset.name}", "name:b.dataset.name,stdname:b.dataset.stdname}") \
        .replace("画像をタップで標準と切り替え", "画像をタップで隣の強度と切り替え")
    names = dict(COLUMNS)
    parts = [html, "<main><div class='intro'><h1>第11回 Stage Clear の強度（照明条件別）</h1>",
             "<p>同じ原本・同じ露出で、基準処理と Stage Clear の強度 0／0.5／0.75／1 を並べた。"
             "強度で変わるのは、にじみ（光源の広がり・ハレーション・薄いディフューズ）、ブラックミスト・Orton、周辺減光、彩度。"
             "顔の赤みの抑え、顔が飛ぶ写真だけ暗くする処理、ハイライトをなだらかにする処理は、どの強度でも同じにかかる。"
             "強度0は第7回の色の土台だけ、1は第10回で決めた最終形。既定は0.5（#86）。</p>",
             "<p>照明条件は顔以外の照明の色と、顔と背景の明るさの差から自動で分けた（目安）。暗所・高感度は ISO で別に並べた。"
             "Pixel 9a の写真は、暗い部屋でモニタの赤・青・紫の光を当てた試し撮りで、本物のステージではない。露出は自動。</p>",
             "<p>画像を押すと大きく表示します。タップするたびに一つ前の強度と切り替わります（基準処理と編集は強度1と切り替え）。</p></div>"]
    for key, title in CONDITIONS.items():
        members = [row for row in rows if key in row["conditions"]]
        parts.append(f"<h2 class='group'>{key} {title}　<small>{len(members)}枚</small></h2>")
        if not members:
            parts.append("<p class='lbl'>該当する写真なし（素材が足りない条件）。</p>")
        for row in members:
            tag = row["tag"]
            notes = [row["source"], f"顔 {row['faces']}"] + ([f"ISO {row['iso']}"] if row["iso"] else [])
            if row["face_cut_ev"] < 0:
                notes.append(f"顔の白飛びで {row['face_cut_ev']:+.2f}EV（強度1）")
            parts.append(f"<section><h3>{tag.upper()}</h3><p class='lbl'>{'　'.join(notes)}</p><div class='sheet'>")
            for col, name in COLUMNS:
                if col == "ref" and not row["has_ref"]:
                    continue
                other = TOGGLE[col]
                cls = " class='std'" if col == "s1" else ""
                parts.append(f"<figure{cls}><button type='button' data-full='{tag}-{col}.jpg' data-std='{tag}-{other}.jpg' "
                             f"data-name='{tag.upper()} / {name}' data-stdname='{names[other]}' aria-label='{tag.upper()} {name}を拡大'>"
                             f"<img src='{tag}-{col}-s.jpg' alt='{tag.upper()} {name}' loading='lazy'></button>"
                             f"<figcaption>{name}</figcaption></figure>")
            parts.append("</div></section>")
    parts.append("</main>" + script)
    (out / "index.html").write_text("\n".join(parts), encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--livepicture", type=Path, required=True)
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--r8", type=Path, required=True)
    parser.add_argument("--app", type=Path, required=True, help="folder of this app's Pixel 9a DNG+JPEG pairs")
    parser.add_argument("--face-model", type=Path, required=True, help="OpenCV Zoo YuNet ONNX (face_detection_yunet_2023mar)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    h.FACE_MODEL = args.face_model
    livepicture, r5, r8, appdir = (b.root_path(p) for p in (args.livepicture, args.r5, args.r8, args.app))
    adopted = livepicture / "adopted"
    out = r.resolve_output(args.output, [livepicture, r5, r8, appdir])
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
    pixel, left = app.shots(appdir)
    if left:
        raise SystemExit("ERROR UNPAIRED: " + ", ".join(f.name for f in left))
    jobs = [("camera", s["id"], livepicture / s["source"], None, s, fin.chosen(s["id"])) for s in recipe["scenes"] if s["id"] in "bdefg"]
    jobs += [("camera", p["tag"], originals[g.stem(ed)], ed, {"skin": [], "base_ev": p["base_ev"]}, fin.chosen("_default"))
             for p, ed in zip(previous, photos)]
    jobs += [("pixel", f"x{i:02d}", dng, jpg, None, fin.chosen("_default")) for i, (dng, jpg) in enumerate(pixel, 1)]
    before = {p: b.digest(p) for _, _, dng, ref, _, _ in jobs for p in ([dng, ref] if ref else [dng])}
    out.mkdir(parents=True)
    rows = []
    for kind, tag, dng, ref, scene, choice in jobs:
        mired = cp.WB_LEVELS[choice["wb"]]
        if kind == "pixel":
            plain, _ = px.decode_pixel(dng)
            decoded, _ = px.decode_pixel(dng, mired_shift=mired)
            scene = {"skin": [], "base_ev": px.auto_base_ev(plain)}
        else:
            plain = r.load_dng(dng)
            decoded = sk.decode(dng, plain, r.shifted_multipliers(dng, mired, 0.)[0] if mired else None)
        images, features, infos = develop(decoded, plain, scene, look, choice)
        for key, img in images.items():
            cd.save(img, out / f"{tag}-{key}.jpg")
        if ref is not None:
            cd.save(upright_jpeg(ref), out / f"{tag}-ref.jpg")
        rows.append({"tag": tag, "source": "Pixel 9a（モニタ照明の試し撮り）" if kind == "pixel" else "fp",
                     "has_ref": ref is not None, "iso": iso(dng, ref), "base_ev": float(scene["base_ev"]),
                     "face_cut_ev": infos["s1"]["face_cut_ev"], "glow": {k: v["glow_strength"] for k, v in infos.items()},
                     "primary": condition(features), **features})
        print(tag, rows[-1]["primary"], end="  ", flush=True)
    # One condition per photo; camera photos are thinned to PER_CONDITION per condition, Pixel ones all kept.
    for key in CONDITIONS:
        if key == "S06":
            chosen = [row for row in rows if (row["iso"] or 0) >= HIGH_ISO]
            chosen = j.spread(sorted(chosen, key=lambda row: -(row["iso"] or 0)), PER_CONDITION) if chosen else []
        else:
            camera = [row for row in rows if row["primary"] == key and row["source"] == "fp"]
            chosen = (j.spread(camera, PER_CONDITION) if camera else []) + \
                [row for row in rows if row["primary"] == key and row["source"] != "fp"]
        for row in chosen:
            row.setdefault("conditions", []).append(key)
    shown = [row for row in rows if row.get("conditions")]
    for row in rows:
        row.setdefault("conditions", [])
    page(out, shown)
    for row in rows:  # drop the images of photos not shown
        if not row["conditions"]:
            for col, _ in COLUMNS:
                for suffix in ("", "-s"):
                    (out / f"{row['tag']}-{col}{suffix}.jpg").unlink(missing_ok=True)
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "version": sc.VERSION, "strengths": [s for _, s in COMPARED], "default_strength": sc.DEFAULT_STRENGTH, "photos": rows},
        ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != hh for p, hh in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "photos": len(rows), "shown": len(shown)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
