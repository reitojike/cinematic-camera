"""Host-only scoring. No image IO; inputs/outputs must stay outside every Git checkout."""
import argparse
import json
import math
from pathlib import Path


def box(value):
    if len(value) != 4 or any(not isinstance(x, (int, float)) or not math.isfinite(x) for x in value):
        raise ValueError("invalid box")
    if value[2] <= 0 or value[3] <= 0:
        raise ValueError("empty box")
    return value


def iou(a, b):
    a, b = box(a), box(b)
    width = max(0, min(a[0]+a[2], b[0]+b[2])-max(a[0], b[0]))
    height = max(0, min(a[1]+a[3], b[1]+b[3])-max(a[1], b[1]))
    intersection = width * height
    return intersection / (a[2]*a[3]+b[2]*b[3]-intersection)


def score(truth, predictions, threshold):
    """Maximum-cardinality one-to-one matching; threshold must be chosen before evaluation."""
    if not 0 < threshold <= 1:
        raise ValueError("IoU threshold must be explicit and in (0, 1]")
    for b in truth + predictions:
        box(b)
    edges = [[j for j, t in enumerate(truth) if iou(p, t) >= threshold] for p in predictions]
    owners = {}

    def augment(index, seen):
        for target in edges[index]:
            if target in seen:
                continue
            seen.add(target)
            if target not in owners or augment(owners[target], seen):
                owners[target] = index
                return True
        return False

    matched = sum(augment(i, set()) for i in range(len(predictions)))
    return {"tp": matched, "fn": len(truth)-matched, "fp": len(predictions)-matched}


def summarize(rows, labels, threshold):
    """One accuracy observation per detector/sample; repeats are timing observations only."""
    if labels.get("authority") != "human" or labels.get("split") not in ("tuning", "held-out"):
        raise ValueError("human labels and explicit split required")
    samples = labels["samples"]
    groups, observed = {}, {}
    for row in rows:
        sample = samples[row["sample"]]
        if row["geometry"] != sample["geometry"] or [row["width"], row["height"]] != sample["size"]:
            raise ValueError("geometry mismatch")
        key = (row["detector"], sample["condition"])
        group = groups.setdefault(key, {"tp": 0, "fp": 0, "fn": 0, "scored_samples": 0, "failed_runs": 0,
                                        "states": {s: 0 for s in ("SUCCESS", "FAILED", "CANCELLED", "NOT_RUN")}, "timings": {}})
        if row["status"] not in group["states"]:
            raise ValueError("invalid state")
        group["states"][row["status"]] += 1
        if row["status"] != "SUCCESS":
            group["failed_runs"] += 1
            continue
        identity = (row["detector"], row["sample"])
        if identity not in observed:
            counts = score(sample["boxes"], row["boxes"], threshold)
            for k, v in counts.items(): group[k] += v
            group["scored_samples"] += 1
            observed[identity] = row["boxes"]
        elif observed[identity] != row["boxes"]:
            raise ValueError("repeat disagreement: inspect privately before scoring")
        if row["phase"] not in ("first-call", "warm"):
            raise ValueError("invalid phase")
        for field in ("sdk_ns", "adapter_ns", "total_ns"):
            value = row[field]
            if not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
                raise ValueError("invalid timing")
            group["timings"].setdefault(row["phase"] + ":" + field, []).append(value)
    for group in groups.values():
        for name, values in group["timings"].items():
            values.sort()
            n = len(values)
            group["timings"][name] = {"n": n, "median_ns": (values[(n-1)//2]+values[n//2])/2,
                                       "p95_ns": values[math.ceil(n*.95)-1]}
    for (detector, condition), group in groups.items():
        expected = [name for name, sample in samples.items() if sample["condition"] == condition]
        group["expected_samples"] = len(expected)
        group["unscored_samples"] = sum((detector, name) not in observed for name in expected)
    return {"adoption": "UNDECIDED", "iou_matching_threshold": threshold, "split": labels["split"],
            "groups": [{"detector": key[0], "condition": key[1], **value} for key, value in groups.items()]}


def outside_git(path):
    resolved = path.resolve()
    if any((parent / ".git").exists() for parent in (resolved, *resolved.parents)):
        raise ValueError("Evaluation data must be outside Git checkouts")
    return resolved


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--iou", type=float, required=True)
    args = parser.parse_args()
    results, labels_path, output = map(outside_git, (args.results, args.labels, args.output))
    if output in (results, labels_path) or output.exists():
        raise ValueError("Output must be a new file")
    report = summarize([json.loads(line) for line in results.read_text(encoding="utf-8").splitlines()],
                       json.loads(labels_path.read_text(encoding="utf-8")), args.iou)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(report, stream, indent=2)
