"""Private generalisation check: apply the round-7 base look unchanged to photos it was not tuned on.

Photos are picked deterministically across capture dates (a venue proxy). Exposure is matched
per photo to the user's edit (centre top-percentile luminance) so that only the look is judged;
metering is a separate, unsolved axis. Output: per-photo rows and two series grids.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import re
import sys

import numpy as np
from PIL import Image

import baseline as b
import r6_diagnose as r
import r7_exposure as e
import r7_final as fin
import r7_look as k
import r7b_skin as sk
import r7c_punch as cp

r.WORK_LONG = 1400
SMALL = 480


def capture_date(path: Path) -> str:
    with Image.open(path) as image:
        return str(image.getexif().get_ifd(0x8769).get(0x9003) or "")[:10]


def pick(edited: list[Path], exclude: set[str], per_busy_date: int = 2, busy: int = 10) -> list[Path]:
    """Evenly spaced picks per capture date: 1 per date, `per_busy_date` for dates with >= `busy` photos."""
    by_date: dict[str, list[Path]] = {}
    for path in sorted(edited):
        if stem(path) not in exclude:
            by_date.setdefault(capture_date(path), []).append(path)
    chosen = []
    for date in sorted(by_date):
        items = by_date[date]
        n = per_busy_date if len(items) >= busy else 1
        chosen += [items[int((i + .5) * len(items) / n)] for i in range(n)]
    return chosen


def stem(path: Path) -> str:
    return re.sub(r"^\d+-", "", path.stem).casefold()


def look_render(decoded: np.ndarray, base_ev: float) -> np.ndarray:
    choice = fin.chosen("_default")
    scene = {"skin": [], "base_ev": base_ev}
    return fin.render(decoded, scene, LOOK, choice)


def match_ev(decoded_small: np.ndarray, target_c97: float) -> float:
    """Bisection on base EV (brightness preset included) so the look's centre c97 matches the edit."""
    low, high = -3., 6.
    for _ in range(20):
        mid = (low + high) / 2
        value = e.output_c97(k.gamut_compress_luma(look_render(decoded_small, mid - sk.BRIGHTNESS_LEVELS["E2"]), r.LUMA))
        low, high = (mid, high) if value < target_c97 else (low, mid)
    return (low + high) / 2 - sk.BRIGHTNESS_LEVELS["E2"]


def grid(paths: list[Path], out: Path, columns: int = 6, height: int = 260) -> None:
    images = [Image.open(p) for p in paths]
    images = [im.resize((round(im.width * height / im.height), height)) for im in images]
    rows = [images[i:i + columns] for i in range(0, len(images), columns)]
    width = max(sum(im.width + 6 for im in row) for row in rows)
    canvas = Image.new("RGB", (width, len(rows) * (height + 6)), (22, 24, 26))
    for y, row in enumerate(rows):
        x = 0
        for im in row:
            canvas.paste(im, (x, y * (height + 6)))
            x += im.width + 6
    canvas.save(out, "JPEG", quality=88)


LOOK: dict = {}


def main(argv: list[str] | None = None) -> int:
    global LOOK
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adopted", type=Path, required=True, help="authorized folder with original/ and edited/")
    parser.add_argument("--r5", type=Path, required=True, help="round-5 folder (recipe.json, reproducer/settings.json)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    adopted, r5 = b.root_path(args.adopted), b.root_path(args.r5)
    out = r.resolve_output(args.output, [adopted, r5])
    LOOK = dict(json.loads((r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    tuned = {stem(Path(s["source"])) for s in json.loads((r5 / "recipe.json").read_text(encoding="utf-8"))["scenes"]}
    originals = {stem(p): p for p in (adopted / "original").iterdir() if p.suffix.lower() == ".dng"}
    edited = [p for p in (adopted / "edited").glob("*.jpg") if stem(p) in originals]
    photos = pick(edited, tuned)
    before = {p: b.digest(p) for ed in photos for p in (ed, originals[stem(ed)])}
    out.mkdir(parents=True)
    rows, record = [], []
    for index, ed in enumerate(photos, 1):
        dng = originals[stem(ed)]
        reference = r.load_dng(dng)
        mult = r.shifted_multipliers(dng, cp.WB_LEVELS[fin.chosen("_default")["wb"]], 0.)[0]
        decoded = sk.decode(dng, reference, mult)
        target = r.load_jpeg(ed)  # keep the edit's own framing (some edits are cropped)
        size = (round(reference.shape[1] * SMALL / max(reference.shape[:2])), round(reference.shape[0] * SMALL / max(reference.shape[:2])))
        ev = match_ev(r.resize_linear(decoded, size), e.output_c97(target))
        values = look_render(decoded, ev)
        tag = f"p{index:02d}"
        k.save_srgb(values, out / f"{tag}-look.jpg")
        k.save_srgb(reference, out / f"{tag}-original.jpg")
        k.save_srgb(target, out / f"{tag}-edited.jpg")
        full = {"skin": [0, 0, 1, 1], "costume": [0, 0, 1, 1], "light": [0, 0, 1, 1], "dark": [0, 0, 1, 1]}
        record.append({"tag": tag, "date": capture_date(ed), "base_ev": ev,
                       "look": cp.tone_stats(values), "edited": cp.tone_stats(target),
                       "look_shadow_C": k.measure(values, full)["shadows_all"]["C"],
                       "edited_shadow_C": k.measure(target, full)["shadows_all"]["C"]})
        rows.append(f"<h3>{tag}　<small>{capture_date(ed).replace(':', '-')}　露出 {ev:+.2f}EV</small></h3><div class='row'>"
                    + "".join(f"<figure><img src='{tag}-{kind}.jpg'><figcaption>{label}</figcaption></figure>"
                              for kind, label in (("original", "補正前"), ("look", "第7回の確定ルック"), ("edited", "本人の編集")))
                    + "</div>")
        print(tag, end=" ", flush=True)
    grid([out / f"{x['tag']}-look.jpg" for x in record], out / "grid-look.jpg")
    grid([out / f"{x['tag']}-edited.jpg" for x in record], out / "grid-edited.jpg")
    (out / "index.html").write_text(
        "<!doctype html><html lang='ja'><meta charset='utf-8'><title>汎用性の確認</title><style>"
        "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1500px;padding:20px}"
        ".row{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}figure{margin:0}img{width:100%;display:block}"
        "figcaption{font-size:13px;color:#cdd}</style><h1>汎用性の確認（非公開）</h1>"
        f"<p>第7回の確定ルック（P2・I1・S2・K1・W1、写真ごとの調整なし）を、調整に使っていない{len(record)}枚へ適用した。"
        "撮影日ごとに偏らないよう自動選択。露出は写真ごとに本人の編集の明るさ（中央上位3%）へ合わせ、ルックだけを比べる。</p>"
        "<h2>シリーズとして：確定ルック</h2><img src='grid-look.jpg'><h2>シリーズとして：本人の編集</h2><img src='grid-edited.jpg'>"
        "<h2>写真ごと</h2>" + "".join(rows), encoding="utf-8")
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "recipe": fin.RECIPE, "photos": record},
                                                ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "photos": len(record)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
