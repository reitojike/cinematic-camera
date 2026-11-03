"""Private round-7c study: crispness x WB on the chosen P2 x I1 x S2 x E2 foundation.

Crispness (display-referred, before the skin layer): a small black point, mid-tone
contrast around 0.18 and extra chroma outside skin hues, to counter the wash-out that
the +0.6 EV brightness brings. WB steps: as-shot, +22 mired (the user's typical
habit) and +45 mired. Diagnostic comparison only; nothing becomes a default.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import numpy as np

import baseline as b
import r6_diagnose as r
import r7_look as k
import r7b_skin as sk

r.WORK_LONG = 1600
CRISP_LEVELS = {"K0": None, "K1": {"black": .004, "contrast": 1.12, "chroma": .10},
                "K2": {"black": .008, "contrast": 1.25, "chroma": .20}}
WB_LEVELS = {"W0": 0., "W1": 22., "W2": 45.}
SKIN, BRIGHTNESS = sk.SKIN_LEVELS["S2"], sk.BRIGHTNESS_LEVELS["E2"]


def crisp(linear: np.ndarray, setting: dict | None) -> np.ndarray:
    if not setting:
        return linear
    y = np.maximum(linear @ r.LUMA, 1e-8)
    # Soft toe instead of a hard black clip: y·y/(y+b) halves values at y=b, leaves bright areas.
    target = y * y / (y + setting["black"])
    # Odds-power contrast pivoting at Y=0.10 (OKLab L≈0.46), just below typical face
    # brightness, so faces get slightly brighter rather than darker.
    p, pivot = setting["contrast"], .10
    t = np.clip(target, 0, 1)
    raised = t ** p
    lower = (1 - t) ** p * (pivot / (1 - pivot)) ** (p - 1)
    target = np.where(target < 1, raised / np.maximum(raised + lower, 1e-8), target)
    x = linear * (target / y)[..., None]
    lab = r.oklab(x)
    skin = sk.skin_weight(lab)
    gain = 1 + setting["chroma"] * (1 - skin)
    lab[..., 1] *= gain
    lab[..., 2] *= gain
    return k.oklab_to_linear(lab).astype(np.float32)


def render(linear, scene, look, immersion, setting):
    base = k.render(linear, scene, look, k.PROFILE_LEVELS["P2"], immersion, scene["base_ev"] + BRIGHTNESS)
    return sk.skin_layer(crisp(base, setting), SKIN)


def tone_stats(values: np.ndarray) -> dict:
    lightness = r.oklab(k.gamut_compress_luma(values, r.LUMA))[..., 0]
    p2, p50, p98 = np.percentile(lightness, [2, 50, 98])
    return {"L_p2": float(p2), "L_p50": float(p50), "L_p98": float(p98), "L_range": float(p98 - p2)}


def page(out: Path, scenes: list[dict], data: dict) -> None:
    parts = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>第7回c ぱきっと感×WB</title><style>"
             "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1600px;padding:20px}"
             ".grid{display:grid;grid-template-columns:110px repeat(3,1fr) 1fr;gap:6px;align-items:center}"
             "img{width:100%;display:block;cursor:zoom-in}td,th{border:1px solid #444;padding:3px 7px;text-align:right}"
             "table{border-collapse:collapse;font-size:13px;margin:8px 0 28px}th{background:#262a2d}"
             "td:first-child{text-align:left}.lbl{font-size:13px;color:#cdd}#zoom{position:fixed;inset:0;background:#000d;"
             "display:none;align-items:center;justify-content:center}#zoom img{max-width:98vw;max-height:98vh;width:auto}</style>",
             "<h1>第7回c ぱきっと感 × WB（非公開）</h1>",
             "<p>土台は P2 × I1（Bのみ I0）× 肌S2 × 明るさE2。行＝ぱきっと感（K0:なし / K1:中 / K2:強。黒点を締め、中間調のコントラストを上げ、"
             "肌以外の彩度を上げる。肌層はその後にかけるので、顔の白さは保つ）。列＝WB（W0:撮影時 / W1:約−550K相当 / W2:約−1100K相当）。"
             "右列は参照。画像をクリックで拡大。</p>"]
    for scene in scenes:
        sid = scene["id"]
        d = data[sid]
        parts.append(f"<h2>{sid.upper()}</h2><div class='grid'><div></div>"
                     + "".join(f"<div class='lbl'>{w}（+{v:.0f}mired）</div>" for w, v in WB_LEVELS.items())
                     + "<div class='lbl'>参照</div>")
        refs = [("edited", "本人の編集"), ("prev", "第7回b S2×E2"), (None, "")]
        for (c, _), (ref, label) in zip(CRISP_LEVELS.items(), refs):
            ref_html = (f"<figure style='margin:0'><img src='{sid}-ref-{ref}.jpg'><div class='lbl'>{label}</div></figure>"
                        if ref else "<div></div>")
            parts.append(f"<div class='lbl'>{c}</div>" + "".join(f"<img src='{sid}-{c}-{w}.jpg'>" for w in WB_LEVELS)
                         + ref_html)
        parts.append("</div><table><tr><th>案</th><th>黒(L 2%)</th><th>中央L</th><th>白(L 98%)</th><th>幅</th>"
                     "<th>肌 L</th><th>肌 C</th><th>肌 色相</th><th>照明 C</th><th>暗部全体 C</th></tr>")
        for key, s in d["stats"].items():
            t = d["tone"][key]
            parts.append(f"<tr><td>{key}</td><td>{t['L_p2']:.2f}</td><td>{t['L_p50']:.2f}</td><td>{t['L_p98']:.2f}</td>"
                         f"<td>{t['L_range']:.2f}</td><td>{r.fmt(s['skin']['L'], 2)}</td><td>{r.fmt(s['skin']['C'], 3)}</td>"
                         f"<td>{r.fmt(s['skin']['hue'], 0)}</td><td>{r.fmt(s['light']['C'], 3)}</td>"
                         f"<td>{r.fmt(s['shadows_all']['C'], 3)}</td></tr>")
        parts.append("</table>")
    parts.append("<div id='zoom' onclick=\"this.style.display='none'\"><img></div><script>"
                 "document.querySelectorAll('.grid img').forEach(i=>i.onclick=()=>{const z=document.getElementById('zoom');"
                 "z.querySelector('img').src=i.src;z.style.display='flex'})</script>")
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
    before = {p: b.digest(p) for s in scenes for p in (args.livepicture / s["source"], args.livepicture / s["edited"])}
    out.mkdir(parents=True)
    data = {}
    for scene in scenes:
        sid = scene["id"]
        dng, edited = args.livepicture / scene["source"], args.livepicture / scene["edited"]
        immersion = k.IMMERSION_LEVELS[sk.CHOSEN["immersion"].get(sid, sk.CHOSEN["default_immersion"])]
        reference = r.load_dng(dng)
        decoded = {}
        for w, mired in WB_LEVELS.items():
            mult = r.shifted_multipliers(dng, mired, 0.)[0] if mired else None
            decoded[w] = sk.decode(dng, reference, mult)
        stats, tone = {}, {}
        for c, setting in CRISP_LEVELS.items():
            for w in WB_LEVELS:
                values = render(decoded[w], scene, look, immersion, setting)
                k.save_srgb(values, out / f"{sid}-{c}-{w}.jpg")
                stats[f"{c}-{w}"], tone[f"{c}-{w}"] = k.measure(values, scene["focus"]), tone_stats(values)
        image = r.load_jpeg(edited)
        image = r.resize_linear(image, (reference.shape[1], reference.shape[0])) if image.shape != reference.shape else image
        k.save_srgb(image, out / f"{sid}-ref-edited.jpg")
        stats["edited"], tone["edited"] = k.measure(image, scene["focus"]), tone_stats(image)
        prev = render(decoded["W1"], scene, look, immersion, None)  # identical to round-7b S2 x E2
        k.save_srgb(prev, out / f"{sid}-ref-prev.jpg")
        data[sid] = {"stats": stats, "tone": tone}
        print(sid, end=" ", flush=True)
    page(out, scenes, data)
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "look": look, "crisp_levels": CRISP_LEVELS,
                                                 "wb_levels_mired": WB_LEVELS, "skin": SKIN, "brightness_ev": BRIGHTNESS,
                                                 "data": data}, ensure_ascii=False, indent=1), encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
