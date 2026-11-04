"""Private round-10h study: fix reddish faces and blown whites without moving photos that were fine.

Changes against round 10g, each limited to where the problem is:
- Skin: a soft upper limit on skin chroma after the iconic layer (knee 0.085, slope 0.35). Hue,
  lightness and white balance are untouched, and skin below the knee is unchanged.
- Orton: no chroma gain on skin, and no lift where the image is already near white.
- Automatic strength: judged on the near-white area (luminance > 0.9) instead of only on
  over-white pixels.
Per photo: before (round 10g), after, and the user's edit. Writes an artifact-ready page.
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
import r10e_portrait as pe
import r10f_combo as cb

r.WORK_LONG = 1400
SKIN_KNEE = .085  # OKLab chroma above which skin is compressed
SKIN_SLOPE = .35
NEAR_WHITE = .9
ORTON_HIGHLIGHT = (.75, .95)  # encoded luma range over which the Orton lift fades out
RED_TARGET = 28.  # pink-red skin hues below this are pulled toward it (skin already at or above stays put)
# name, knee, slope, hue lift, area. "M" = round-10h first version (skin-colour area near face-like
# colours). The user asked for stronger ones; those are limited to detected faces, because a stronger
# cap on the colour-based area turned an orange-lit background brown and missed pink-lit faces.
LEVELS = {"M": ("弱（前回の修正）", SKIN_KNEE, SKIN_SLOPE, 0., "colour"),
          "S": ("中（顔検出の範囲だけ、上限を強く＋色相を少し戻す）", .07, .15, .35, "face"),
          "X": ("強（顔検出の範囲だけ、さらに強く）", .06, .08, .65, "face")}
CHOSEN_LEVEL = "S"  # the user picked 中 (2026-11-04): face-limited cap, knee .07 / slope .15, hue lift .35


def skin_red_weight(lab: np.ndarray, vivid: float = .16) -> np.ndarray:
    """Skin-like pixels including pink-red skin (skin_weight alone is centred on 40° and misses it)."""
    lightness = lab[..., 0]
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    red = k.hue_band(hue, 10., 30.) * np.clip((chroma - .015) / .025, 0, 1) * np.clip(1 - (chroma - vivid) / .06, 0, 1)         * np.clip((lightness - .3) / .15, 0, 1)  # saturated red lights (chroma > 0.22) are not skin
    return np.maximum(sk.skin_weight(lab), red)


def skin_chroma_cap(values: np.ndarray, near_skin: np.ndarray, knee: float = SKIN_KNEE, slope: float = SKIN_SLOPE,
                    lift: float = 0., vivid: float = .16) -> np.ndarray:
    """Soft upper limit on skin chroma; lightness untouched, skin below the knee unchanged.

    With lift > 0, pink-red skin hues (below RED_TARGET) also move that fraction of the way toward
    RED_TARGET; hues at or above it (yellow-orange skin) never move, so good skin cannot turn orange.
    """
    lab = r.oklab(values)
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    capped = np.where(chroma > knee, knee + (chroma - knee) * slope, chroma)
    weight = skin_red_weight(lab, vivid) * near_skin
    new_chroma = chroma * (1 + weight * (capped / np.maximum(chroma, 1e-6) - 1))
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1]))  # -180..180; pink-red skin is about -20..28
    if lift:
        below = (hue < RED_TARGET) & (hue > -40)
        hue = np.where(below, hue + lift * weight * (RED_TARGET - hue), hue)
    rad = np.radians(hue)
    lab[..., 1], lab[..., 2] = new_chroma * np.cos(rad), new_chroma * np.sin(rad)
    return k.oklab_to_linear(lab).astype(np.float32)


def orton(values: np.ndarray, skin: np.ndarray) -> np.ndarray:
    encoded = pe.linear_to_unit(values)
    bright = 1 - (1 - encoded) ** 2
    soft = np.clip(ic.blur_rgb(bright, .012), 0, 1)
    luma = encoded @ r.LUMA
    lo, hi = ORTON_HIGHLIGHT
    fade = 1 - np.clip((luma - lo) / (hi - lo), 0, 1)[..., None]  # leave near-white areas alone
    mixed = pe.unit_to_linear(encoded + .45 * fade * (bright * soft - encoded))
    out = np.where(values > 1, np.maximum(values, mixed), mixed)
    before, after = r.oklab(values), r.oklab(out)
    keep = np.clip(skin, 0, 1)
    c0, c1 = np.hypot(before[..., 1], before[..., 2]), np.hypot(after[..., 1], after[..., 2])
    scale = (1 - keep) + keep * np.minimum(1, c0 / np.maximum(c1, 1e-6))  # no chroma gain on skin
    after[..., 1] *= scale
    after[..., 2] *= scale
    return k.oklab_to_linear(after).astype(np.float32)


def near_white_fraction(values: np.ndarray) -> float:
    return float(((values @ r.LUMA) > NEAR_WHITE).mean())


def auto_strength(values: np.ndarray) -> float:
    """1 at <=1% near-white area, 0.5 at >=3.5%."""
    return 1 - .5 * float(np.clip((near_white_fraction(values) - .01) / .025, 0, 1))


FACE_MODEL: Path | None = None  # OpenCV Zoo YuNet ONNX (diagnostic only); set from --face-model
UPRIGHT_SCORE, TILTED_SCORE = .75, .85  # tilted passes find leaning heads but give more false hits


def detect_faces(values: np.ndarray) -> list[tuple[int, int, int, int]]:
    """Faces from OpenCV's YuNet detector, a stand-in for the app's ML Kit / Camera2 faces.

    Runs upright and on copies rotated by +-25 and +-45 degrees (singers tilt their heads); a tilted
    hit is mapped back by its centre and kept only when it does not overlap an earlier face.
    """
    import cv2
    if FACE_MODEL is None:
        raise SystemExit("ERROR FACE_MODEL: pass --face-model")
    image = np.ascontiguousarray((np.clip(pe.linear_to_unit(values), 0, 1) * 255).astype(np.uint8)[..., ::-1])
    h, w = image.shape[:2]
    detector = cv2.FaceDetectorYN.create(str(FACE_MODEL), "", (w, h), .5, .3, 5000)
    faces: list[tuple[int, int, int, int]] = []
    for angle in (0, 25, -25, 45, -45):
        rotation = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1)
        _, found = detector.detect(cv2.warpAffine(image, rotation, (w, h)) if angle else image)
        back = cv2.invertAffineTransform(rotation)
        for row in ([] if found is None else found):
            if row[-1] < (TILTED_SCORE if angle else UPRIGHT_SCORE):
                continue
            fx, fy, fw, fh = (float(v) for v in row[:4])
            cx, cy = back @ np.array([fx + fw / 2, fy + fh / 2, 1.])
            box = (int(cx - fw / 2), int(cy - fh / 2), int(fw), int(fh))
            if all(abs(cx - (x + bw / 2)) > (fw + bw) / 3 or abs(cy - (y + bh / 2)) > (fh + bh) / 3 for x, y, bw, bh in faces):
                faces.append(box)
    return faces


def face_area(values: np.ndarray) -> tuple[np.ndarray, int]:
    """Soft area over detected faces, extended down to the chin and neck."""
    faces = detect_faces(values)
    h, w = values.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32) + .5
    area = np.zeros((h, w), dtype=np.float32)
    for fx, fy, fw, fh in sorted(faces, key=lambda f: -f[2] * f[3])[:6]:
        cx, cy, rx, ry = fx + fw / 2, fy + fh * .62, fw * .58, fh * .72
        radius = np.hypot((xx - cx) / max(rx, 1), (yy - cy) / max(ry, 1))
        area = np.maximum(area, np.clip((1.25 - radius) / .35, 0, 1))
    return area, faces


def chosen_layer(standard: np.ndarray, skin: np.ndarray, level: str = "M", faces: np.ndarray | None = None) -> np.ndarray:
    """Black mist -> Orton -> vignette (light-adding steps scaled automatically), then vibrance."""
    auto = auto_strength(standard)
    out = standard + auto * (pe.black_mist(standard, skin) - standard)
    out = out + auto * (orton(out, skin) - out)
    out = k.vibrance(pe.vignette(out), cb.VIBRANCE)
    # Limit faces that came out too red. `skin` is the face-like weight (centred on 40°), dilated so
    # the reddest parts of a face are included while red-lit walls and curtains are not.
    _, knee, slope, lift, area = LEVELS[level]
    near_skin = np.clip(ic.blur(skin, .02) * 2, 0, 1) if area == "colour" else faces
    # Inside a detected face even very red skin is skin, so the red-light gate opens further there.
    return skin_chroma_cap(out, near_skin, knee, slope, lift, .16 if area == "colour" else .24)


def skin_hue(values: np.ndarray, weight: np.ndarray) -> tuple[float, float]:
    lab = r.oklab(values)
    w = weight / max(float(weight.sum()), 1e-6)
    a, bb = float((lab[..., 1] * w).sum()), float((lab[..., 2] * w).sum())
    return float(np.degrees(np.arctan2(bb, a)) % 360), float(np.hypot(a, bb))


def develop(decoded, scene, look, choice):
    """Round-10g output and round-10h output from one decode."""
    old_base = fin.render(decoded, scene, look, choice)
    old_skin = np.clip(ic.blur(sk.skin_weight(r.oklab(old_base)), .01), 0, 1)
    old_std = ic.glow(old_base, cd.STANDARD)
    old = cb.combo(old_std, "ALLsA", old_skin, np.zeros(old_std.shape[:2], dtype=np.float32))
    new_base = old_base
    new_skin = old_skin
    metric = np.clip(ic.blur(skin_red_weight(r.oklab(new_base)), .01), 0, 1) * np.clip(ic.blur(old_skin, .02) * 2, 0, 1)
    new_std = ic.glow(new_base, cd.STANDARD)
    faces, count = face_area(new_std)
    news = {level: chosen_layer(new_std, new_skin, level, faces) for level in LEVELS}
    return old, news, metric, new_std, count  # metrics weigh pink-red skin near faces


COLUMNS = (("old", "前（第10g回）"),) + tuple((lv, name) for lv, (name, *_) in LEVELS.items()) + (("edited", "本人の編集"),)


def page(out: Path, rows: list[dict]) -> None:
    html = cd.PAGE_HEAD.replace("<title>ライブ写真 キャラクタールック</title>", "<title>ライブ写真 赤みの抑えの強さ</title>")
    parts = [html, "<main><div class='intro'><h1>第10h回 顔の赤みの抑えを3段階で比較</h1>",
             "<p>前回の修正（弱）では差が分かりにくかったので、強めた2段階を足した。中と強は、顔を検出した範囲（あご・首まで。顔検出はOpenCVのYuNetで、アプリではML Kitなどが代わる）だけで、"
             "肌の彩度の上限を下げ、ピンク〜赤に寄った肌の色相を黄み側へ戻す（もともと黄み〜オレンジの肌の色相は動かさない）。"
             "顔の範囲に限るのは、色だけで肌を選ぶとオレンジの照明の背景まで茶色くなり、ピンクの照明の顔は逆に選べなかったため。"
             "中と強では、顔が検出できなかった写真の赤みは抑えない（弱よりも変化が小さくなる）。腕など顔以外の肌も対象外。"
             "白飛び対策（オートン・自動調整）は3段階とも同じ。</p>",
             "<p>直したのは3点。(1) 肌の彩度にやわらかい上限をかける（鮮やかすぎる赤みだけを抑える）。色相・明るさ・WBは動かさず、上限より穏やかな肌はそのまま。"
             "(2) オートン効果で、肌の彩度を上げない・白に近い部分を押し上げない。(3) にじみの自動調整を、白に近い範囲（輝度0.9以上）の広さで判定する。</p>",
             "<p>見出しの数字は、肌の彩度（前→後、大きいほど鮮やか）と、にじみの強さ（前→後）。"
             "画像を押すと大きく表示し、タップするたびに「前」と切り替わります。</p></div>"]
    for row in rows:
        tag = row["tag"]
        chroma = "／".join(f"{row['chroma'][lv]:.3f}" for lv in LEVELS)
        parts.append(f"<section><h2>{tag.upper()}</h2><p class='lbl'>肌の彩度 前{row['chroma_old']:.3f}→弱／中／強 {chroma}　"
                     f"にじみの強さ {row['auto_old'] * 100:.0f}%→{row['auto_new'] * 100:.0f}%　検出した顔 {row['faces']}</p><div class='sheet'>")
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
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--face-model", type=Path, required=True, help="OpenCV Zoo YuNet ONNX (face_detection_yunet_2023mar)")
    args = parser.parse_args(argv)
    global FACE_MODEL
    FACE_MODEL = args.face_model
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
    jobs = []
    for scene in (s for s in recipe["scenes"] if s["id"] in "bdefg"):
        dng = livepicture / scene["source"]
        jobs.append((scene["id"], dng, scene, fin.chosen(scene["id"]), None))
    for prev, ed in zip(previous, photos):
        scene = {"skin": [], "base_ev": prev["base_ev"]}  # as g.look_render (fin.render adds the E2 brightness)
        jobs.append((prev["tag"], originals[g.stem(ed)], scene, fin.chosen("_default"), ed))
    before = {p: b.digest(p) for _, dng, _, _, ed in jobs for p in ([dng, ed] if ed else [dng])}
    out.mkdir(parents=True)
    rows = []
    for tag, dng, scene, choice, ed in jobs:
        reference = r.load_dng(dng)
        mired = cp.WB_LEVELS[choice["wb"]]
        decoded = sk.decode(dng, reference, r.shifted_multipliers(dng, mired, 0.)[0] if mired else None)
        old, new, weight, new_std, count = develop(decoded, scene, look, choice)
        cd.save(old, out / f"{tag}-old.jpg")
        for lv, img in new.items():
            cd.save(img, out / f"{tag}-{lv}.jpg")
        stats = {lv: skin_hue(k.gamut_compress_luma(img, r.LUMA), weight) for lv, img in new.items()}
        old_stats = skin_hue(k.gamut_compress_luma(old, r.LUMA), weight)
        row = {"tag": tag, "has_edit": ed is not None, "faces": len(count), "boxes": count,
               "hue_old": old_stats[0], "chroma_old": old_stats[1],
               "hue": {lv: v[0] for lv, v in stats.items()}, "chroma": {lv: v[1] for lv, v in stats.items()},
               "diff": {lv: float(np.abs(pe.linear_to_unit(img) - pe.linear_to_unit(old)).mean() * 255) for lv, img in new.items()},
               "auto_old": cb.auto_strength(ic.glow(fin.render(decoded, scene, look, choice), cd.STANDARD)),
               "auto_new": auto_strength(new_std),
               "white_old": float((r.oklab(k.gamut_compress_luma(old, r.LUMA))[..., 0] > .96).mean()),
               "white_new": float((r.oklab(k.gamut_compress_luma(new["M"], r.LUMA))[..., 0] > .96).mean())}
        if ed is not None:
            target = r.load_jpeg(ed)
            cd.save(target, out / f"{tag}-edited.jpg")
            row["hue_edit"] = skin_hue(target, sk.skin_weight(r.oklab(target)))[0]
        rows.append(row)
        print(tag, end=" ", flush=True)
    page(out, rows)
    (out.parent / f"{out.name}-record.json").write_text(json.dumps(
        {"environment": b.versions(), "recipe": fin.RECIPE, "levels": LEVELS, "red_target": RED_TARGET,
         "near_white": NEAR_WHITE, "orton_highlight": ORTON_HIGHLIGHT, "photos": rows}, ensure_ascii=False, indent=1),
        encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "photos": len(rows)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
