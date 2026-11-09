"""RawPreviewTiming の処理時間だけを集計する。画面提示の判定には使わない。"""
import argparse
import json
import math
import re
import statistics
from pathlib import Path

STAGES = set("ADMISSION VERIFY_BEFORE INPUT FOUNDATION GLOW_PREPARE SKIN_EXPAND GLOW MIST_AUTO MIST ORTON CHARACTER PROTECTIONS BITMAP VERIFY_AFTER CLEANUP".split())
TAG = re.compile(r"(?:\bRawPreviewTiming\s*:|\bRawPreviewTiming\([^)]*\)\s*:)")


def summarize(lines, kind="DRAFT", warmup=0, workers=None):
    if kind not in {"DRAFT", "EXACT"} or warmup < 0 or workers not in {None, 1, 2}:
        raise ValueError("kind または warmup が不正です")
    samples = []
    configurations = set()
    failed = malformed = 0
    for line in lines:
        match = TAG.search(line)
        if not match:
            continue
        pairs = [part.split("=", 1) for part in line[match.end():].split()]
        if any(len(pair) != 2 for pair in pairs):
            malformed += 1
            continue
        fields = dict(pairs)
        if len(fields) != len(pairs):
            malformed += 1
            continue
        if fields.get("kind") not in {"DRAFT", "EXACT"}:
            malformed += 1
            continue
        if fields["kind"] != kind:
            continue
        try:
            if fields["outcome"] not in {"SUCCESS", "FAILED"}:
                raise ValueError()
            if int(fields["sequence"]) <= 0:
                raise ValueError()
            total = int(fields["totalNs"])
            configuration = None
            if "workers" in fields or "maxEdge" in fields:
                configuration = int(fields["workers"])
                if configuration not in {1, 2} or int(fields["maxEdge"]) != (256 if kind == "DRAFT" else 1024):
                    raise ValueError()
            stage_keys = set(fields) - {"kind", "sequence", "outcome", "totalNs", "workers", "maxEdge"}
            if not stage_keys or any(key not in {s + "Ns" for s in STAGES} for key in stage_keys):
                raise ValueError()
            stages = [int(fields[key]) for key in stage_keys]
            if total < 0 or min(stages) < 0 or sum(stages) != total:
                raise ValueError()
        except (KeyError, ValueError):
            malformed += 1
            continue
        if workers is not None and configuration != workers:
            continue
        configurations.add(configuration)
        if fields["outcome"] == "FAILED":
            failed += 1
        else:
            samples.append(total / 1_000_000)
    mixed = len(configurations) > 1
    ordered = samples[warmup:] if not mixed else []
    retained = sorted(ordered)
    valid = bool(retained) and malformed == 0 and not mixed
    return {
        "metric": "render_session_elapsed_ms_NOT_input_to_display",
        "kind": kind,
        "workers_filter": workers,
        "mixed_worker_configurations": mixed,
        "per_render_ms": ordered,
        "successful_records": len(samples),
        "warmup_removed": min(warmup, len(samples)),
        "sample_count": len(retained),
        "failed_records": failed,
        "malformed_records": malformed,
        "status": "PROCESSING_ONLY" if valid else "INSUFFICIENT_OR_INVALID",
        "input_to_present_50ms": "NOT_MEASURED",
        "median_ms": statistics.median(retained) if retained else None,
        "p95_ms": retained[math.ceil(.95 * len(retained)) - 1] if retained else None,
        "max_ms": max(retained) if retained else None,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--kind", choices=("DRAFT", "EXACT"), default="DRAFT")
    parser.add_argument("--warmup", type=int, default=0)
    parser.add_argument("--workers", type=int, choices=(1, 2))
    args = parser.parse_args()
    if args.warmup < 0:
        parser.error("--warmup は0以上")
    # パス・生ログは出力しない。複数セッションを連結せず操作別に入力する。
    try:
        with args.log.open(encoding="utf-8-sig") as log:
            result = summarize(log, args.kind, args.warmup, args.workers)
    except (OSError, UnicodeError):
        print(json.dumps({"status": "LOG_UNREADABLE"}))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "PROCESSING_ONLY" else 2


if __name__ == "__main__":
    raise SystemExit(main())
