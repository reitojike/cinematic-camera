"""Private round-8 study: meter on detected faces instead of a skin-colour proxy.

Uses OpenCV's bundled Haar cascades (diagnostic environment only, see
requirements-win-py315-diag.txt) to find faces on the fixed baseline decode, then asks the
same question as r8_exposure.py: at which base EV does the round-7 look render the face as
bright as the user's edit, and how well does the input face luminance predict it?
The Android app would take face positions from Camera2/CameraX metadata or ML Kit instead.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

import cv2
import numpy as np
import rawpy

import baseline as b
import r6_diagnose as r
import r7_exposure as e
import r7_final as fin
import r7c_punch as cp
import r8_exposure as x
import r8_generalize as g

DETECT_LONG = 1200
RECENT_FROM = "2026:07"


def detection_image(linear: np.ndarray) -> np.ndarray:
    """8-bit equalised grey, brightened so that dim stage faces are visible to the cascade."""
    y = linear @ r.LUMA
    scaled = np.clip(y / max(float(np.percentile(y, 99)), 1e-4) * .9, 0, 1)
    encoded = (np.where(scaled <= .0031308, 12.92 * scaled, 1.055 * scaled ** (1 / 2.4) - .055) * 255).astype(np.uint8)
    return cv2.equalizeHist(encoded)


def detect_faces(grey: np.ndarray) -> list[tuple[int, int, int, int]]:
    root = cv2.data.haarcascades
    minimum = max(12, int(min(grey.shape) * .025))
    faces = []
    for name, flips in (("haarcascade_frontalface_default.xml", (False,)), ("haarcascade_profileface.xml", (False, True))):
        cascade = cv2.CascadeClassifier(root + name)
        for flip in flips:
            image = cv2.flip(grey, 1) if flip else grey
            for fx, fy, fw, fh in cascade.detectMultiScale(image, scaleFactor=1.1, minNeighbors=4, minSize=(minimum, minimum)):
                faces.append((grey.shape[1] - fx - fw if flip else fx, fy, fw, fh))
    return faces


def face_weight(shape: tuple[int, int], faces: list[tuple[int, int, int, int]], scale: float, keep: int = 3) -> np.ndarray:
    """Soft ellipses over the largest faces (inner 70% of each box), in the metering image's pixels."""
    h, w = shape
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32) + .5
    weight = np.zeros((h, w), dtype=np.float32)
    for fx, fy, fw, fh in sorted(faces, key=lambda f: -f[2] * f[3])[:keep]:
        cx, cy = (fx + fw / 2) * scale, (fy + fh * .55) * scale
        rx, ry = fw * .35 * scale, fh * .4 * scale
        radius = np.sqrt(((xx - cx) / max(rx, 1)) ** 2 + ((yy - cy) / max(ry, 1)) ** 2)
        weight = np.maximum(weight, np.clip(1.5 - radius, 0, 1))
    return weight


def decode_half(path: Path) -> np.ndarray:
    with rawpy.imread(str(path)) as raw:
        rgb = raw.postprocess(half_size=True, use_camera_wb=True, use_auto_wb=False, output_color=rawpy.ColorSpace.sRGB,
                              output_bps=16, gamma=(1, 1), no_auto_bright=True, no_auto_scale=False, bright=1.0,
                              exp_shift=1.0, exp_preserve_highlights=0.0, adjust_maximum_thr=0.0,
                              highlight_mode=rawpy.HighlightMode.Clip,
                              fbdd_noise_reduction=rawpy.FBDDNoiseReductionMode.Off, noise_thr=None, median_filter_passes=0)
    return r.crop_default(rgb.astype(np.float32) / 65535, scale=.5)


def fit(rows: list[dict], metric: str) -> dict | None:
    return x.safe_crossval(rows, metric, None)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adopted", type=Path, required=True)
    parser.add_argument("--r5", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="new JSON path outside inputs")
    parser.add_argument("--progress", type=Path, help="optional text file updated after each pair")
    args = parser.parse_args(argv)
    adopted, r5 = b.root_path(args.adopted), b.root_path(args.r5)
    output = r.resolve_output(args.output, [adopted, r5])
    # The progress file is rewritten during the run: it must be a new file outside every input.
    progress = r.resolve_output(args.progress, [adopted, r5]) if args.progress else None
    look = dict(json.loads((r5 / "reproducer" / "settings.json").read_text(encoding="utf-8"))["looks"]["clear"],
                rgb=[1., 1., 1.])
    originals = {g.stem(p): p for p in (adopted / "original").iterdir() if p.suffix.lower() == ".dng"}
    pairs = [p for p in sorted((adopted / "edited").glob("*.jpg")) if g.stem(p) in originals]
    rows = []
    for index, edited in enumerate(pairs, 1):
        dng = originals[g.stem(edited)]
        full = decode_half(dng)
        target_image = r.load_jpeg(edited)
        if not x.edit_keeps_full_frame(edited) or                 abs(target_image.shape[1] / target_image.shape[0] - full.shape[1] / full.shape[0]) > .01:
            continue  # cropped (or unverifiable) edit
        long = max(full.shape[:2])
        detect = r.resize_linear(full, (round(full.shape[1] * DETECT_LONG / long), round(full.shape[0] * DETECT_LONG / long)))
        faces = detect_faces(detection_image(detect))
        # Drop detections without skin-like colour inside (background shapes, costume patterns).
        skin = x.skin_proxy_weight(detect) / np.maximum(x.skin_proxy_weight(detect).max(), 1e-6)
        faces = [f for f in faces if skin[f[1]:f[1] + f[3], f[0]:f[0] + f[2]].mean() > .08
                 and f[1] + f[3] / 2 < .7 * detect.shape[0]]  # faces sit above legs/skirts on stage
        size = (round(full.shape[1] * x.SIZE / long), round(full.shape[0] * x.SIZE / long))
        reference = r.resize_linear(full, size)
        row = {"date": g.capture_date(edited), "faces": len(faces), "inputs": e.input_metrics(reference)}
        if faces:
            weight = face_weight((size[1], size[0]), faces, x.SIZE / DETECT_LONG)
            target_image = r.resize_linear(target_image, size)
            mult = r.shifted_multipliers(dng, cp.WB_LEVELS[fin.chosen("_default")["wb"]], 0.)[0]
            decoded, _ = r.decode_dng_profile(dng, hue_sat=True, multipliers=mult, half_size=True)
            decoded = r.resize_linear(decoded.astype(np.float32), size)
            decoded *= float(np.median(reference @ r.LUMA) / max(np.median(decoded @ r.LUMA), 1e-6))
            row["inputs"]["face"] = 2 ** x.proxy_log_y(reference, weight)
            row["ev"] = x.solve_face_ev(decoded, weight, x.proxy_log_y(target_image, weight), look)
        rows.append(row)
        if progress:
            progress.write_text(f"{index}/{len(pairs)}\n", encoding="utf-8")
    detected = [row for row in rows if "ev" in row]
    recent = [row for row in detected if row["date"][:7] >= RECENT_FROM]
    result = {"pairs": len(rows), "detected": len(detected), "recent_detected": len(recent),
              "fits": {"all": [f for f in (fit(detected, m) for m in ("face", "c97")) if f],
                       "recent": [f for f in (fit(recent, m) for m in ("face", "c97")) if f]},
              "rows": rows}
    output.write_text(json.dumps(result, indent=1), encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("pairs", "detected", "recent_detected", "fits")}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
