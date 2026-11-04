"""Private round-7b study: skin layer x brightness on the chosen P2 x I1 foundation.

Skin layer (display-referred, after the foundation): within skin-like hues only, lower
chroma, lift lightness and pull yellow/red toward a neutral pink, aiming at whiter,
clearer skin. Brightness adds a fixed EV to the round-5 exposure. WB defaults to the
user's typical cooler habit (+22 mired); as-shot WB is kept as a reference.
Diagnostic comparison only; nothing becomes a default.
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
import r7_look as k

r.WORK_LONG = 1600
SKIN_LEVELS = {"S0": 0., "S1": .5, "S2": 1.}
BRIGHTNESS_LEVELS = {"E0": 0., "E1": .3, "E2": .6}
SKIN_TARGET_HUE = 18.  # OKLab hue of a neutral pink skin (the edits sit at ~358-31°)
COOLER_MIRED = 22.
CHOSEN = {"profile": "P2", "immersion": {"b": "I0"}, "default_immersion": "I1"}


def skin_weight(lab: np.ndarray) -> np.ndarray:
    lightness = lab[..., 0]
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1])) % 360
    band = k.hue_band(hue, 40., 45.)  # ~355° to ~85°: red-pink through yellow-orange
    gate = np.clip((chroma - .015) / .025, 0, 1) * np.clip(1 - (chroma - .16) / .06, 0, 1)
    return band * gate * np.clip((lightness - .25) / .15, 0, 1)


def skin_layer(linear: np.ndarray, strength: float) -> np.ndarray:
    """Whiter, clearer skin: chroma x(1-0.35s), lightness toward white by 12%·s, hue toward 18° by 35%·s."""
    if not strength:
        return linear
    lab = r.oklab(linear)
    weight = skin_weight(lab) * strength
    lightness = lab[..., 0]
    chroma = np.hypot(lab[..., 1], lab[..., 2])
    hue = np.degrees(np.arctan2(lab[..., 2], lab[..., 1]))
    delta = (SKIN_TARGET_HUE - hue + 180) % 360 - 180
    hue = np.radians(hue + .35 * weight * delta)
    chroma = chroma * (1 - .35 * weight)
    lab[..., 0] = lightness + .12 * weight * (1 - lightness)
    lab[..., 1], lab[..., 2] = chroma * np.cos(hue), chroma * np.sin(hue)
    return k.oklab_to_linear(lab).astype(np.float32)


def render(linear, scene, look, immersion, base_ev, skin):
    return skin_layer(k.render(linear, scene, look, k.PROFILE_LEVELS["P2"], immersion, base_ev), skin)


def decode(dng: Path, reference: np.ndarray, multipliers=None) -> np.ndarray:
    profiled, _ = r.decode_dng_profile(dng, hue_sat=True, multipliers=multipliers)
    if profiled.shape != reference.shape:
        profiled = r.resize_linear(profiled, (reference.shape[1], reference.shape[0]))
    return profiled * float(np.median(reference @ r.LUMA) / max(np.median(profiled @ r.LUMA), 1e-6))


def page(out: Path, scenes: list[dict], data: dict) -> None:
    parts = ["<!doctype html><html lang='ja'><meta charset='utf-8'><title>第7回b 肌×明るさ</title><style>"
             "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1600px;padding:20px}"
             ".grid{display:grid;grid-template-columns:110px repeat(3,1fr) 1fr;gap:6px;align-items:center}"
             "img{width:100%;display:block;cursor:zoom-in}td,th{border:1px solid #444;padding:3px 7px;text-align:right}"
             "table{border-collapse:collapse;font-size:13px;margin:8px 0 28px}th{background:#262a2d}"
             "td:first-child{text-align:left}.lbl{font-size:13px;color:#cdd}#zoom{position:fixed;inset:0;background:#000d;"
             "display:none;align-items:center;justify-content:center}#zoom img{max-width:98vw;max-height:98vh;width:auto}</style>",
             "<h1>第7回b 肌層 × 明るさ（非公開）</h1>",
             "<p>土台は第7回で選ばれた P2 × I1（Bのみ I0）。WBは本人編集の傾向に合わせて約550K寒色寄り（+22mired）を既定にした。"
             "行＝肌層（S0:なし / S1:中 / S2:強。肌の色相だけ彩度を下げ、明るくし、黄み・赤みを中立のピンクへ寄せる）。"
             "列＝明るさ（E0:第5回の露出 / E1:+0.3EV / E2:+0.6EV）。右列は参照。画像をクリックで拡大。</p>"]
    for scene in scenes:
        sid = scene["id"]
        d = data[sid]
        parts.append(f"<h2>{sid.upper()}（浸る層 {d['immersion']}）</h2><div class='grid'><div></div>"
                     + "".join(f"<div class='lbl'>{e}（+{v:.1f}EV）</div>" for e, v in BRIGHTNESS_LEVELS.items())
                     + "<div class='lbl'>参照</div>")
        refs = [("edited", "本人の編集"), ("prev", "第7回の選択（撮影時WB）"), ("asshot", "S1×E1・撮影時WB")]
        for (s, value), (ref, label) in zip(SKIN_LEVELS.items(), refs):
            parts.append(f"<div class='lbl'>{s}（{value:.1f}）</div>" + "".join(
                f"<img src='{sid}-{s}-{e}.jpg'>" for e in BRIGHTNESS_LEVELS)
                + f"<figure style='margin:0'><img src='{sid}-ref-{ref}.jpg'><div class='lbl'>{label}</div></figure>")
        parts.append("</div><table><tr><th>案</th><th>肌 L</th><th>肌 C</th><th>肌 色相</th><th>照明 色相</th>"
                     "<th>暗部全体 C</th></tr>")
        for key, s in d["stats"].items():
            parts.append(f"<tr><td>{key}</td><td>{r.fmt(s['skin']['L'], 2)}</td><td>{r.fmt(s['skin']['C'], 3)}</td>"
                         f"<td>{r.fmt(s['skin']['hue'], 0)}</td><td>{r.fmt(s['light']['hue'], 0)}</td>"
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
        immersion_key = CHOSEN["immersion"].get(sid, CHOSEN["default_immersion"])
        immersion = k.IMMERSION_LEVELS[immersion_key]
        reference = r.load_dng(dng)
        mult, wb_info = r.shifted_multipliers(dng, COOLER_MIRED, 0.)
        cooler = decode(dng, reference, mult)
        as_shot = decode(dng, reference)
        stats = {}
        for s, strength in SKIN_LEVELS.items():
            for e, ev in BRIGHTNESS_LEVELS.items():
                values = render(cooler, scene, look, immersion, scene["base_ev"] + ev, strength)
                k.save_srgb(values, out / f"{sid}-{s}-{e}.jpg")
                stats[f"{s}-{e}"] = k.measure(values, scene["focus"])
        for ref, values in (("prev", render(as_shot, scene, look, immersion, scene["base_ev"], 0.)),
                            ("asshot", render(as_shot, scene, look, immersion, scene["base_ev"] + .3, .5))):
            k.save_srgb(values, out / f"{sid}-ref-{ref}.jpg")
            stats[f"ref-{ref}"] = k.measure(values, scene["focus"])
        image = r.load_jpeg(edited)
        image = r.resize_linear(image, (cooler.shape[1], cooler.shape[0])) if image.shape != cooler.shape else image
        k.save_srgb(image, out / f"{sid}-ref-edited.jpg")
        stats["edited"] = k.measure(image, scene["focus"])
        data[sid] = {"stats": stats, "immersion": immersion_key, "wb": wb_info}
        print(sid, end=" ", flush=True)
    page(out, scenes, data)
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "look": look, "skin_levels": SKIN_LEVELS,
                                                 "brightness_levels": BRIGHTNESS_LEVELS, "cooler_mired": COOLER_MIRED,
                                                 "chosen": CHOSEN, "data": data}, ensure_ascii=False, indent=1, default=str),
                                     encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
