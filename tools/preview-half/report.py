"""Build a shareable synthetic-only report from a complete, opt-in 90-case capture."""
import argparse
import csv
import json
import re
import shutil
from pathlib import Path


def report(source: Path, destination: Path, head: str) -> None:
    if not re.fullmatch(r"[0-9a-f]{8,40}", head):
        raise ValueError("A recorded source commit is required")
    data = json.loads((source / "comparison.json").read_text(encoding="utf-8"))
    if data.get("schema") != 1 or data.get("matrixVersion") != "stage-clear-90-v1-raw-input-experiment":
        raise ValueError("Unexpected capture schema or matrix")
    cases = data.get("cases", [])
    if data.get("status") != "complete" or len(cases) != 90:
        raise ValueError("Only complete 90-case captures may be reported")
    if {c["id"] for c in cases} != {f"c{i:03d}" for i in range(1, 91)}:
        raise ValueError("Case IDs are missing or duplicated")
    for case in cases:
        for mode in ("full", "half"):
            name = case[mode]
            if name != f'{case["id"]}-{mode}.png' or not (source / name).is_file():
                raise ValueError("Missing or unsafe image reference")
    # Do not modify an existing review or copy raw instrumentation/host logs.
    destination.mkdir(parents=True, exist_ok=False)
    order = sorted(cases, key=lambda c: (-c["metrics"]["maeRgb8"], c["id"]))
    selected = order[:3]
    worst_max = max(cases, key=lambda c: c["metrics"]["maxRgb8"])
    if worst_max["id"] not in {c["id"] for c in selected}:
        selected.append(worst_max)
    other = next(c for c in order if c["kind"] != order[0]["kind"])
    if other["id"] not in {c["id"] for c in selected}:
        selected.append(other)
    fields = ["id", "kind", "strength", "warmth", "ev", "rawWidth", "rawHeight",
              "comparisonWidth", "comparisonHeight"]
    metrics = ["maeRgb8", "maxRgb8", "p99Rgb8", "rmseRgb8", "psnrDb", "changedPixelFraction"]
    with (destination / "metrics.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields + metrics)
        writer.writeheader()
        for case in cases:
            writer.writerow({**{f: case[f] for f in fields}, **{m: case["metrics"][m] for m in metrics}})
    lines = ["# FULL AHD / half 入力の GPU 後比較（合成・未採用）", "",
             f"比較コード: `{head}`。90 条件完走。写真・実機データは含まない。", "",
             "RAW は比較寸法の 2 倍（最大約 3.15MP）の合成 Bayer。12/24MP 実写の画質・速度の結論ではない。",
             "既存 RGB golden のケース ID/編集値を共有する追加実験で、元 fixture の色や near-white 占有率そのものの再現ではない。",
             "保存は原寸 AHD のまま、half 製品採用は未決定。数値は 8bit RGB 出力間の差。",
             "PSNR が空欄の場合は完全一致。全条件は [metrics.csv](metrics.csv)。", "",
             "MAE の大きい 3 条件、最大差の最大条件、最悪パターンと別種で最も差の大きい条件を掲載する（重複は省く）。", ""]
    for case in selected:
        m = case["metrics"]
        lines += [f'## {case["id"]}: {case["kind"]}', "",
                  f'RAW {case["rawWidth"]}×{case["rawHeight"]} → 比較 {case["comparisonWidth"]}×{case["comparisonHeight"]}。',
                  f'MAE {m["maeRgb8"]:.4f} / 最大 {m["maxRgb8"]} / p99 {m["p99Rgb8"]}。', "",
                  "| FULL AHD | half |", "|---|---|",
                  f'| ![FULL]({case["full"]}) | ![half]({case["half"]}) |', ""]
        for mode in ("full", "half"):
            shutil.copyfile(source / case[mode], destination / case[mode])
    (destination / "README.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    report(args.source, args.destination, args.head)