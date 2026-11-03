"""Private round-9 check on this app's own Pixel 9a DNGs (DngCreator), paired with Google Camera shots.

Pairs are matched by capture order. For each pair: the app's JPEG, the app DNG through the
round-7 look with and without the profile layer, Google's JPEG, and the HDR+ DNG through the
same look. Diagnostic only.
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
import r7_look as k
import r7c_punch as cp
import r9_pixel as px

r.WORK_LONG = 1400


def capture_time(jpeg: Path) -> str:
    with Image.open(jpeg) as image:
        return str(image.getexif().get_ifd(0x8769).get(0x9003) or "")


def dominant_hue(linear: np.ndarray) -> float | None:
    lab = r.oklab(linear.reshape(-1, 3))
    chroma = np.hypot(lab[:, 1], lab[:, 2])
    mask = (chroma > .06) & (lab[:, 0] > .15)
    if mask.sum() < 50:
        return None
    w = chroma[mask]
    return float(np.degrees(np.arctan2((lab[mask, 2] * w).sum(), (lab[mask, 1] * w).sum())) % 360)


def develop(path: Path, look: dict, profile: str) -> np.ndarray:
    choice = dict(fin.chosen("_default"), profile=profile)
    decoded, _ = px.decode_pixel(path, mired_shift=cp.WB_LEVELS[choice["wb"]])
    plain, _ = px.decode_pixel(path)
    return fin.render(decoded, {"skin": [], "base_ev": px.auto_base_ev(plain)}, look, choice)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app", type=Path, required=True, help="folder of this app's DNG+JPEG pairs")
    parser.add_argument("--google", type=Path, required=True, help="folder of Google Camera RAW+JPEG pairs")
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    app, google, r5 = (b.root_path(p) for p in (args.app, args.google, args.r5))
    out = r.resolve_output(args.output, [app, google, r5])
    look = dict(json.loads((r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])

    def shots(folder: Path) -> list[tuple[Path, Path]]:
        pairs = []
        for dng in folder.iterdir():
            if dng.suffix.lower() != ".dng":
                continue
            jpgs = [j for j in folder.glob("*.jpg") if j.stem.split(".")[0] == dng.stem.split(".")[0]]
            if jpgs:
                pairs.append((dng, jpgs[0]))
        return sorted(pairs, key=lambda pair: capture_time(pair[1]))

    app_shots, google_shots = shots(app), shots(google)
    before = {p: b.digest(p) for pair in app_shots + google_shots for p in pair}
    out.mkdir(parents=True)
    rows, record = [], []
    for index, ((adng, ajpg), (gdng, gjpg)) in enumerate(zip(app_shots, google_shots), 1):
        tag = f"s{index:02d}"
        images = {"app-jpeg": r.load_jpeg(ajpg), "app-p0": develop(adng, look, "P0"), "app-p2": develop(adng, look, "P2"),
                  "google-jpeg": r.load_jpeg(gjpg), "google-p2": develop(gdng, look, "P2")}
        hues = {}
        for key, values in images.items():
            k.save_srgb(values, out / f"{tag}-{key}.jpg")
            hues[key] = dominant_hue(k.gamut_compress_luma(values, r.LUMA))
        record.append({"tag": tag, "app_time": capture_time(ajpg)[11:], "google_time": capture_time(gjpg)[11:], "hue": hues})
        labels = (("app-jpeg", "本アプリのJPEG"), ("app-p0", "本アプリDNG＋確定ルック（プロファイル層なし）"),
                  ("app-p2", "本アプリDNG＋確定ルック"), ("google-jpeg", "GoogleカメラのJPEG"), ("google-p2", "HDR+ DNG＋確定ルック"))
        hue_text = " / ".join(f"{label.split('（')[0]} {hues[key]:.0f}°" for key, label in labels if hues[key] is not None)
        rows.append(f"<h2>{tag}　<small>{capture_time(ajpg)[11:]}　主な色相（OKLab）: {hue_text}</small></h2><div class='row'>"
                    + "".join(f"<figure><img src='{tag}-{key}.jpg'><figcaption>{label}</figcaption></figure>" for key, label in labels)
                    + "</div>")
        print(tag, end=" ", flush=True)
    (out / "index.html").write_text(
        "<!doctype html><html lang='ja'><meta charset='utf-8'><title>本アプリのPixel DNGの確認</title><style>"
        "body{background:#16181a;color:#eee;font:15px/1.7 system-ui,sans-serif;margin:0 auto;max-width:1800px;padding:20px}"
        ".row{display:grid;grid-template-columns:repeat(5,1fr);gap:8px}figure{margin:0}img{width:100%;display:block}"
        "figcaption{font-size:13px;color:#cdd}small{color:#bcc}</style><h1>本アプリの Pixel 9a DNG の確認（非公開）</h1>"
        "<p>本アプリ（DngCreator）の DNG と Google カメラ（HDR+）の DNG を、撮影順で組にした。どちらも DNG 仕様どおり"
        "（撮影時WB、ForwardMatrix、LookTable があれば適用、GainMap）に現像し、第7回の確定ルックをかけた。露出は自動。"
        "青紫の光で「確定ルック」が「プロファイル層なし」より青側に寄るかを見る。</p>" + "".join(rows), encoding="utf-8")
    (out / "record.json").write_text(json.dumps({"environment": b.versions(), "pairs": record}, ensure_ascii=False, indent=1),
                                     encoding="utf-8")
    if any(b.digest(p) != h for p, h in before.items()):
        raise SystemExit("ERROR SOURCE_CHANGED")
    print(json.dumps({"status": "ok", "pairs": len(record)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
