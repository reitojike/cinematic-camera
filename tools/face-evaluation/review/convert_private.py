"""Same-Windows private-runner to local HTML adapter. No adb/network/image copies."""
import argparse
import json
import math
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from private_manifest import validate, GEOMETRY
from evaluate import outside_git

DETECTORS = {
    "mlkit-16.1.7-FAST-min0.1-no-tracking": "mlkit-fast",
    "mlkit-16.1.7-ACCURATE-min0.1-no-tracking": "mlkit-accurate",
    "yunet-2023mar-8f2383e4-opencv4.13.0-pc-multi-v1": "yunet",
}

def convert(bundle, results):
    verified = validate(bundle)
    manifest = json.loads((Path(bundle) / "manifest.json").read_text(encoding="utf-8"))
    samples = [{"id": s["id"], "sha256": s["sha256"], "width": s["width"],
                "height": s["height"], "results": {}} for s in manifest["samples"]]
    if len(results) != 3:
        raise ValueError("Exactly three completed repeats=1 runs required")
    seen_detectors = set()
    for source in results:
        path = outside_git(Path(source))
        if Path(source).is_symlink() or not path.is_file() or path.stat().st_size > 2*1024*1024:
            raise ValueError("Invalid results file")
        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        if any(not isinstance(row, dict) for row in rows):
            raise ValueError("Result row must be an object")
        if len(rows) != len(samples):
            raise ValueError("Incomplete or repeated run")
        detector = rows[0].get("detector")
        if detector not in DETECTORS or detector in seen_detectors:
            raise ValueError("Unexpected or repeated detector")
        seen_detectors.add(detector)
        for index, row in enumerate(rows):
            s = samples[index]
            phase = "first-call" if index == 0 else "warm"
            if (type(row.get("schema")) is not int or row.get("schema") != 1 or row.get("sample") != s["id"]
                or row.get("detector") != detector or type(row.get("iteration")) is not int or row.get("iteration") != 0
                or row.get("phase") != phase or row.get("geometry") != GEOMETRY
                or row.get("input_sha256") != s["sha256"]
                or row.get("manifest_sha256") != verified["manifest_sha256"]
                or row.get("revision") != manifest["revision"]):
                raise ValueError("Result provenance/order mismatch")
            status = row.get("status")
            if status not in ("SUCCESS", "FAILED", "CANCELLED", "NOT_RUN"):
                raise ValueError("Invalid status")
            result = {"status": status, "phase": phase}
            if status == "SUCCESS":
                if type(row.get("width")) is not int or type(row.get("height")) is not int or row.get("width") != s["width"] or row.get("height") != s["height"]:
                    raise ValueError("Geometry mismatch")
                boxes = row.get("boxes")
                if not isinstance(boxes, list) or len(boxes) > 1000:
                    raise ValueError("Invalid boxes")
                for b in boxes:
                    if (not isinstance(b, list) or len(b) != 4
                        or any(type(x) not in (int, float) or not math.isfinite(x) for x in b)
                        or b[2] <= 0 or b[3] <= 0
                        ):
                        raise ValueError("Invalid box geometry")
                for field in ("sdk_ns", "adapter_ns"):
                    v = row.get(field)
                    if type(v) is not int or not 0 <= v <= 2**53-1:
                        raise ValueError("Invalid timing")
                result.update(boxes=boxes, ms=row["sdk_ns"]/1e6, adapter_ms=row["adapter_ns"]/1e6)
            s["results"][DETECTORS[detector]] = result
    return {"schema": 1, "timing_contract": "phase-separated-sdk-adapter-v1", "samples": samples}

if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--bundle", type=Path, required=True)
    ap.add_argument("--results", type=Path, action="append", required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--same-windows-host-approved", action="store_true")
    args = ap.parse_args()
    try:
        if not args.same_windows_host_approved:
            raise ValueError("Same-host authorization required")
        output = outside_git(args.output)
        report = convert(args.bundle, args.results)
        raw = json.dumps(report, ensure_ascii=False)
        if len(raw.encode("utf-8")) > 2*1024*1024:
            raise ValueError("Output too large")
        with output.open("x", encoding="utf-8") as stream:
            stream.write(raw)
        print("Local review dataset created; do not publish this file")
    except (ValueError, OSError, KeyError, TypeError, IndexError):
        raise SystemExit("Conversion rejected; inspect locally (paths/data omitted)")
