"""Private round-7 result: render the look the user chose, next to round 5 and the edit.

Chosen: profile P2, immersion I1 (scene b: I0), skin S2, brightness E2, crispness K1,
WB W1 (+22 mired; scene b: as-shot). Writes sRGB and Display P3 JPEGs plus a recipe.
Diagnostic record of a choice; not an Android default.
"""
from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import sys

from PIL import Image, ImageCms

import baseline as b
import r6_diagnose as r
import r7_look as k
import r7b_skin as sk
import r7c_punch as cp

r.WORK_LONG = 2400
RECIPE = {"profile": "P2", "immersion": "I1", "skin": "S2", "brightness": "E2", "crisp": "K1", "wb": "W1",
          "per_scene": {"b": {"immersion": "I0", "wb": "W0"}}}


def chosen(sid: str) -> dict:
    return dict({key: value for key, value in RECIPE.items() if key != "per_scene"}, **RECIPE["per_scene"].get(sid, {}))


def render(decoded, scene, look, choice):
    base = k.render(decoded, scene, look, k.PROFILE_LEVELS[choice["profile"]], k.IMMERSION_LEVELS[choice["immersion"]],
                    scene["base_ev"] + sk.BRIGHTNESS_LEVELS[choice["brightness"]])
    return sk.skin_layer(cp.crisp(base, cp.CRISP_LEVELS[choice["crisp"]]), sk.SKIN_LEVELS[choice["skin"]])


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
    before = {p: b.digest(p) for s in scenes for p in (args.livepicture / s["source"], args.livepicture / s["edited"])}
    out.mkdir(parents=True)
    rows, record = [], {}
    for scene in scenes:
        sid = scene["id"]
        choice = chosen(sid)
        dng, edited = args.livepicture / scene["source"], args.livepicture / scene["edited"]
        reference = r.load_dng(dng)
        # Uncorrected original: the fixed baseline decode (camera WB, no exposure lift, no look).
        k.save_srgb(reference, out / f"{sid}-original.jpg")
        mired = cp.WB_LEVELS[choice["wb"]]
        decoded = sk.decode(dng, reference, r.shifted_multipliers(dng, mired, 0.)[0] if mired else None)
        values = render(decoded, scene, look, choice)
        k.save_srgb(values, out / f"{sid}-chosen.jpg")
        with Image.open(edited) as image:
            icc = image.info.get("icc_profile")
        has_p3 = bool(icc) and "P3" in ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc)))
        if has_p3:
            k.save_p3(values, out / f"{sid}-chosen-p3.jpg", icc)
        for ref, path in (("r5", args.r5 / "assets" / f"{sid}-clear.jpg"), ("edited", edited)):
            image = r.load_jpeg(path, crop=ref == "r5")
            image = r.resize_linear(image, (values.shape[1], values.shape[0])) if image.shape[:2] != values.shape[:2] else image
            k.save_srgb(image, out / f"{sid}-{ref}.jpg")
        record[sid] = {"choice": choice, "stats": k.measure(values, scene["focus"]), "tone": cp.tone_stats(values)}
        cells = [("original", "補正前（RAWそのまま）"), ("r5", "第5回①"), ("chosen", "第7回の確定版（sRGB）")] + ([("chosen-p3", "確定版（Display P3）")] if has_p3 else []) \
            + [("edited", "本人の編集")]
        rows.append(f"<h2>{sid.upper()}　<small>{' / '.join(f'{k_}:{v}' for k_, v in choice.items())}</small></h2>"
                    f"<div class='row' style='grid-template-columns:repeat({len(cells)},1fr)'>"
                    + "".join(f"<figure><img src='{sid}-{c}.jpg'><figcaption>{label}</figcaption></figure>" for c, label in cells)
                    + "</div>")
        print(sid, end=" ", flush=True)
    (out / "index.html").write_text(
        "<!doctype html><html lang='ja'><meta charset='utf-8'><title>第7回 確定版</title><style>"
        "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1800px;padding:20px}"
        ".row{display:grid;gap:8px}figure{margin:0}img{width:100%;display:block}figcaption{font-size:13px;color:#cdd}"
        "</style><h1>第7回 確定版（非公開）</h1><p>P2・I1・S2・E2・K1・W1（BのみI0・W0）。補正前の元画像（固定基準の現像、露出の持ち上げなし）、第5回①、本人の編集を並べる。"
        "Display P3版はP3対応の画面とブラウザで色の差が出る。</p>" + "".join(rows), encoding="utf-8")
    (out / "recipe.json").write_text(json.dumps({"environment": b.versions(), "recipe": RECIPE, "look": look,
                                                 "levels": {"profile": k.PROFILE_LEVELS, "immersion": k.IMMERSION_LEVELS,
                                                            "skin": sk.SKIN_LEVELS, "brightness": sk.BRIGHTNESS_LEVELS,
                                                            "crisp": cp.CRISP_LEVELS, "wb_mired": cp.WB_LEVELS},
                                                 "scenes": record}, ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
