"""Separate-condition diagnostic data; not input for the three-detector review UI."""
import argparse
import json
from pathlib import Path
from convert_private import _convert
from evaluate import outside_git

BASELINE = "mlkit-16.1.7-ACCURATE-min0.1-no-tracking"
CANDIDATE = "mlkit-16.1.7-ACCURATE-min0.05-no-tracking"
CONDITIONS = {BASELINE: "accurate-min0.1", CANDIDATE: "accurate-min0.05"}

def compare(bundle, baseline, candidate):
    # Roles are explicit, not inferred from argument order or relabelled after conversion.
    for source, expected in ((baseline, BASELINE), (candidate, CANDIDATE)):
        path = outside_git(Path(source))
        if Path(source).is_symlink() or not path.is_file() or path.stat().st_size > 2*1024*1024:
            raise ValueError("Invalid condition file")
        with path.open(encoding="utf-8") as stream:
            row = json.loads(stream.readline())
        if not isinstance(row, dict) or row.get("detector") != expected:
            raise ValueError("Condition role mismatch")
    data = _convert(bundle, [baseline, candidate], CONDITIONS)
    samples = []
    for sample in data["samples"]:
        row = {k: v for k, v in sample.items() if k != "results"}
        row["conditions"] = sample["results"]
        samples.append(row)
    return {"schema": "face-minsize-comparison-v1", "scope": "diagnostic-unscored-only",
            "adoption": "UNDECIDED", "timing_contract": data["timing_contract"],
            "condition_ids": CONDITIONS, "samples": samples}

def write_comparison(bundle, baseline, candidate, output):
    path = outside_git(Path(output))
    report = compare(bundle, baseline, candidate)
    raw = json.dumps(report, ensure_ascii=False)
    if len(raw.encode("utf-8")) > 2*1024*1024:
        raise ValueError("Output too large")
    with path.open("x", encoding="utf-8") as stream:
        stream.write(raw)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--same-windows-host-approved", action="store_true")
    args = parser.parse_args()
    try:
        if not args.same_windows_host_approved:
            raise ValueError("Same-host authorization required")
        write_comparison(args.bundle, args.baseline, args.candidate, args.output)
        print("Separate diagnostic comparison created locally; not scored; do not publish")
    except (ValueError, OSError, KeyError, TypeError, IndexError):
        raise SystemExit("Comparison rejected; inspect locally (paths/data omitted)")