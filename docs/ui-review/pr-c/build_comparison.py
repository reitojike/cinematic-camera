#!/usr/bin/env python3
"""Compose captured PNGs only. No UI simulation, cropping, or invented before states."""
import argparse
import hashlib
import json
import re
import subprocess
import shutil
from pathlib import Path

BEFORE = "53e68ffc722579138c0f673dbdc9ac08d398044f"
AFTER = "32fec1f3d9414ba1a2dfd0060acfade722fd5867"
FIXTURE = "c229f3e4"
SOURCE_ROOT = "app/src/main/java/dev/cinematic/p0/photoroll/"
SOURCE_FILES = ["PhotoRollDevelopView.kt", "GpuPhotoRollEditor.kt", "GpuPhotoRollProblemMessage.kt",
                "ForegroundSaveNotice.kt", "DevelopMessages.kt", "LibraryMessages.kt"]
# The strings in before/after are checked against literal Kotlin strings at the fixed commits.
# Notes are editorial descriptions, never presented as source quotations.
CASES = [
    ("01-preparing", "準備中", ["保存準備未確認", "画像を準備中…"], ["保存準備中", "画像を準備しています。"], "重複した準備表示を中央へ整理"),
    ("02-ready", "保存前", ["保存前"], ["未保存"], "短い chip と保存操作を分離"),
    ("03-editing", "調整中", ["調整中 · ×で保存へ"], ["調整中", "調整を閉じると保存できます"], "説明はボタンのアクセシビリティ情報へ"),
    ("04-saving-paused", "保存中・プレビュー停止", ["保存確認中", "保存用に GPU プレビューを終了しました"], ["保存中", "保存中 · プレビューを止めています"], "JPEG進捗バナーと中央の停止表示を共存"),
    ("05-published-paused", "保存済み・再開前", ["公開確認済み · 開く前に照合"], ["保存済み", "保存のためプレビューを止めました"], "画像未表示の理由を中央で説明"),
    ("06-cancelled", "保存取消", ["保存取消済み · 再試行"], ["保存取消"], "FAILUREからWARNINGへ。再試行と再開を区別"),
    ("07-save-failed", "JPEG保存失敗", ["GPU 保存失敗 · 再試行"], ["保存失敗", "JPEGを保存できませんでした"], "問題カードへ整理。保存条件は変更しない"),
    ("08-low-memory", "メモリ不足", ["メモリ不足"], ["処理を続けられません", "原本は変更していません。"], "メモリ不足は本文、原本無変更は補足行"),
    ("09-unconfirmed", "保存未確認", ["保存未確認 · 再照合"], ["保存未確認", "保存結果を確認・保存し直す"], "復旧は既存の明示操作のみ"),
    ("10-hold", "保存保留", ["保存保留"], ["保存保留", "保存結果を確認できません"], "保留を維持。問題カードと既存復旧操作"),
    ("11-rechecking", "保存結果を照合中", ["保存確認中"], ["保存照合中", "保存結果を確認しています。"], "JPEG作成中と結果照合中を区別"),
    ("12-published-feedback", "今回の保存成功", ["公開確認済み · 開く前に照合"], ["保存済み", "JPEGを保存しました。"], "今回の成功だけtoast。復元済み状態では出さない"),
    ("13-recovery-dialog", "重複リスクの確認", ["前回の保存結果を確認できません", "重複の可能性はあるが保存し直す"], ["前回の保存結果を確認できません", "重複の可能性はあるが保存し直す"], "文言の意味・3操作を維持。旧DialogのATD合成再現では最下操作がroot内で切れる。実機再現未確認"),
    ("14-static-info", "原本と現像の情報", ["GPU の診断用プレビューです。通常版の既定は CPU です。原本と保存前後の照合は変更しません。"], ["原本と現像について"], "古いCPU既定/診断用説明と動的通知を外す"),
    ("15-saving-notifications-disabled", "保存中・通知未許可", ["保存通知を許可"], ["通知を許可"], "保存バナーにも通知許可を保持。取消も残す"),
    ("16-cancelled-other-photo-saving", "取消済み・別写真を保存中", ["保存取消済み · 再試行", "プレビューを再開"], ["保存取消", "プレビューを再開"], "FGS取消と表示中写真の再開が両方残る"),
    ("17-generic-failure-hold", "汎用問題カード・保存保留", [], ["現像を続けられません", "一覧に戻る"], "beforeなし。HOLDを維持する正常fallbackの表示"),
    ("18-generic-failure-navigation-disabled", "汎用カード・移動不可", [], ["現像を続けられません", "一覧に戻る"], "beforeなし。設定保存中相当の既存条件で操作無効"),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], encoding="utf-8")


def literals(repo, ref):
    result = {}
    files = set(git(repo, "ls-tree", "-r", "--name-only", ref).splitlines())
    for name in SOURCE_FILES:
        path = SOURCE_ROOT + name
        if path not in files:
            continue
        source = git(repo, "show", f"{ref}:{path}")
        for match in re.finditer(r'"(?:[^"\\]|\\.)*"', source):
            try:
                value = json.loads(match.group())
            except json.JSONDecodeError:
                continue
            result.setdefault(value, []).append({"path": path, "line": source[:match.start()].count("\n") + 1})
    return result


def source_evidence(repo):
    before, after = literals(repo, BEFORE), literals(repo, AFTER)
    evidence = []
    for name, title, old, new, note in CASES:
        row = {"case": name, "title": title, "before": [], "after": [], "note": note}
        for side, quotes, pool in [("before", old, before), ("after", new, after)]:
            for quote in quotes:
                if quote not in pool:
                    raise ValueError(f"Source quotation not found: {side} {name}: {quote}")
                row[side].append({"text": quote, "locations": pool[quote]})
        evidence.append(row)
    return evidence


def wrap(draw, text, font, width):
    lines, line = [], ""
    for char in text:
        if char == "\n":
            lines.append(line); line = ""; continue
        if line and draw.textlength(line + char, font=font) > width:
            lines.append(line); line = char
        else:
            line += char
    return lines + ([line] if line else [])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-dir", required=True, type=Path)
    parser.add_argument("--after-dir", required=True, type=Path)
    parser.add_argument("--font", required=True, type=Path, help="Japanese TrueType/TTC font, no font substitution")
    parser.add_argument("--before-fixture", required=True, help="Actual commit containing the BEFORE fixture")
    parser.add_argument("--after-fixture", default=FIXTURE, help="Actual commit containing the corrected AFTER fixture")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--confirmed-captures", action="store_true", help="Required: inputs are the re-captured accepted runs, not rejected initial captures")
    args = parser.parse_args()
    if not args.confirmed_captures:
        parser.error("Confirm both input runs first, then pass --confirmed-captures")
    if not args.font.is_file():
        parser.error("A real Japanese font file is required")
    if args.before_dir.resolve() == args.after_dir.resolve():
        parser.error("Before and after must use separate capture directories")
    evidence = source_evidence(args.repo)
    before_fixture = git(args.repo, "rev-parse", args.before_fixture + "^{commit}").strip()
    after_fixture = git(args.repo, "rev-parse", args.after_fixture + "^{commit}").strip()
    from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version
    font = ImageFont.truetype(str(args.font), 30)
    heading_font = ImageFont.truetype(str(args.font), 38)
    title_font = ImageFont.truetype(str(args.font), 46)
    width, margin, gap, shot_h = 1440, 40, 32, 800
    col_w = (width - margin * 2 - gap) // 2
    measure = ImageDraw.Draw(Image.new("RGB", (width, 1)))
    row_data, inputs = [], []
    for row, spec in zip(evidence, CASES):
        name = row["case"]
        shots = []
        for side, root, expected in [("before", args.before_dir, bool(spec[2])), ("after", args.after_dir, True)]:
            filename = name + ".png"
            if side == "before":
                filename = "before" + ("12-published-no-toast" if name == "12-published-feedback" else name) + ".png"
            file = root / filename
            if not expected:
                if file.exists():
                    raise ValueError(f"Unexpected BEFORE for a new-only state: {name}")
                shots.append(None); continue
            if not file.is_file():
                raise ValueError(f"Missing required image: {side}/{file.name}")
            with Image.open(file) as source:
                source.load()
                if source.format != "PNG" or min(source.size) < 100:
                    raise ValueError(f"Not a valid full capture: {side}/{file.name}")
                shot = source.convert("RGB")
            inputs.append({"side": side, "file": name + ".png", "source_file": file.name,
                           "sha256": sha(file), "size": list(shot.size)})
            shot.thumbnail((col_w, shot_h), Image.Resampling.LANCZOS)
            shots.append(shot)
        descriptions = []
        for side in ["before", "after"]:
            text = " / ".join(item["text"] for item in row[side]) or "beforeなし（旧版に該当表示なし）"
            descriptions.append(wrap(measure, text, font, col_w))
        note_lines = wrap(measure, "変更点: " + row["note"], font, width - margin * 2)
        height = 90 + shot_h + 30 + max(map(len, descriptions)) * 42 + 22 + len(note_lines) * 42 + 30
        row_data.append((row, shots, descriptions, note_lines, height))
    header_h = 260
    sheet = Image.new("RGB", (width, header_h + sum(row[-1] for row in row_data) + 90), "#10151c")
    draw = ImageDraw.Draw(sheet)
    draw.text((margin, 25), "PR-C  表示変更の一覧", font=title_font, fill="#ffffff")
    draw.text((margin, 90), f"before {BEFORE[:8]}  →  after {AFTER[:8]}", font=font, fill="#b8c7d8")
    draw.text((margin, 138), "合成入力の疑似再現・実フロー非検証 / 17・18はbeforeなし", font=font, fill="#e4c78a")
    draw.text((margin, 184), "実取得PNGを縮小配置。原寸はREADMEの個別リンクへ。", font=font, fill="#b8c7d8")
    y = header_h
    bounds = []
    for row, shots, descriptions, notes, height in row_data:
        bounds.append({"case": row["case"], "top": y, "height": height})
        draw.rectangle((20, y, width - 20, y + height - 12), fill="#1b2430")
        draw.text((margin, y + 12), row["case"][:2] + "  " + row["title"], font=heading_font, fill="#ffffff")
        for i, (shot, lines) in enumerate(zip(shots, descriptions)):
            x = margin + i * (col_w + gap)
            draw.text((x, y + 62), "BEFORE" if i == 0 else "AFTER", font=font, fill="#b8c7d8" if i == 0 else "#8bd4be")
            top = y + 102
            if shot is not None:
                sheet.paste(shot, (x + (col_w - shot.width) // 2, top + (shot_h - shot.height) // 2))
            else:
                for n, label in enumerate(["beforeなし", "旧版に該当表示なし", "画像は作成していません"]):
                    draw.text((x + 24, top + 300 + n * 48), label, font=font, fill="#b8c7d8")
            for n, line in enumerate(lines):
                draw.text((x, top + shot_h + 24 + n * 42), line, font=font, fill="#edf2f7")
        note_y = y + 102 + shot_h + 24 + max(map(len, descriptions)) * 42 + 20
        for n, line in enumerate(notes):
            draw.text((margin, note_y + n * 42), line, font=font, fill="#e4c78a")
        y += height
    draw.text((margin, y + 12), "保存・取消・照合・通知許可の実操作やGPU画質の合格証拠ではありません。", font=font, fill="#b8c7d8")
    args.output.mkdir(parents=True, exist_ok=True)
    # Retain the original verified captures for README links; never rewrite their pixels.
    for item in inputs:
        root = args.before_dir if item["side"] == "before" else args.after_dir
        source = root / item["source_file"]
        target = args.output / item["side"] / item["file"]
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.resolve() != target.resolve():
            shutil.copyfile(source, target)
    output = args.output / "comparison.png"
    sheet.save(output, format="PNG", optimize=False, compress_level=9)
    manifest = {"before_product": BEFORE, "after_product": AFTER, "before_fixture": before_fixture,
        "after_fixture": after_fixture, "font": {"file": args.font.name, "sha256": sha(args.font)},
        "pillow": pillow_version, "inputs": inputs, "source_quotes": evidence, "rows": bounds,
        "output": {"file": output.name, "sha256": sha(output), "size": list(sheet.size)},
        "fixture_evidence": [{"file": str(path.relative_to(args.output)).replace("\\", "/"), "sha256": sha(path)}
            for path in sorted((args.output / "fixtures").glob("*")) if path.is_file()],
        "capture_evidence": [{"file": str(path.relative_to(args.output)).replace("\\", "/"), "sha256": sha(path)}
            for side in ["before", "after"] for path in sorted((args.output / side).glob("*.txt"))],
        "known_limitations": ["Before case 13: lowest action text clips at the Dialog root edge in ATD synthetic rendering; physical-device reproduction is unverified."],
        "method": "Actual PNGs, proportional downscale and padding only; no crop or simulated UI. No before images for 17/18."}
    (args.output / "comparison-manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Created {output.name}: {sheet.width}x{sheet.height}; {len(inputs)} captured PNG inputs")


if __name__ == "__main__":
    main()
