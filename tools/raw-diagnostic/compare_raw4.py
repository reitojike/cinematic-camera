"""Compare private RAW4 schema-2 reports; emit only fixed labels and numeric summaries."""
import argparse
import json
import sys
from fractions import Fraction

ORDERS = {"AB-ABBAABBA": [1, 2, 1, 2, 2, 1, 1, 2, 2, 1],
          "BA-BAABBAAB": [2, 1, 2, 1, 1, 2, 2, 1, 1, 2]}
SEPARATE = {"R7_VIBRANCE", "R7_CRISP", "R7_SKIN"}
NESTED = {"ORTON_FIELD", "ORTON_COMPOSE", "ORTON_PIXEL_THREAD_CPU"}
REQUIRED = set("SOURCE_VERIFY_BEFORE SOURCE_SNAPSHOT NATIVE_DECODE DECODE_CLOSE "
               "ANALYSIS_REDUCE CONTROL_PREPARE R7_PROFILE R7_TONE_ANALYSIS "
               "R7_TONE_APPLY R7_FOUNDATION SKIN_FIELD GLOW NEAR_WHITE MIST "
               "ORTON ORTON_FIELD ORTON_COMPOSE CHARACTER_CAP FACE_COUNT "
               "SHOULDER BITMAP_PACK JPEG_ENCODE JPEG_INSPECT SOURCE_VERIFY_AFTER "
               "RESOURCE_CLOSE SCRATCH_CLEANUP".split())
KNOWN = REQUIRED | SEPARATE | {"R7_COLOR_FUSED", "FACE_PERCENTILE", "FACE_SCALE",
                               "ORTON_PIXEL_THREAD_CPU"}
METRICS = ("glow", "mist", "tone", "color", "renderAndClose", "residual")


class InvalidReport(ValueError):
    """Errors deliberately contain no report data or filesystem information."""


def require(condition):
    if not condition:
        raise InvalidReport("INVALID_REPORT")


def integer(value, minimum=0):
    require(type(value) is int and minimum <= value <= 2**63 - 1)
    return value


def expect(obj, key, value):
    actual = obj.get(key)
    if type(value) is bool:
        require(actual is value)
    elif type(value) is int:
        require(type(actual) is int and actual == value)
    else:
        require(type(actual) is type(value) and actual == value)


def environment(obj):
    require(type(obj) is dict)
    thermal = integer(obj.get("thermalStatus"), -1)
    require(thermal in (-1, 0, 1))  # Same MODERATE stop boundary as the runner.
    integer(obj.get("batteryTemperatureTenthsC"), -1)
    status = integer(obj.get("batteryStatus"), -1)
    require(status in (-1, 1, 2, 3, 4, 5))
    charging = obj.get("charging")
    require("charging" in obj and (charging is None or type(charging) is bool))
    require(charging is ({2: True, 5: True, 3: False, 4: False}.get(status)))


def validate(report):
    require(type(report) is dict)
    for key, value in {"schema": 2, "scope": "private-original24-no-publication",
                       "mode": "TIMING", "comparison": "RAW4", "warmth": 0,
                       "exposureAdjustmentEv": 0, "ortonWorkers": 4,
                       "characterWorkers": 2, "performanceThresholdPercent": 10,
                       "performanceDecision": "REQUIRES_BOTH_ORDERS",
                       "complete": True, "status": "SUCCESS",
                       "selectedOriginalUnchanged": True, "privateInputRemoved": True,
                       "harnessCloseFailed": False, "resourcesQuiescent": True,
                       "measuredSuccessesA": 4, "measuredSuccessesB": 4}.items():
        expect(report, key, value)
    require(type(report.get("strength")) in (int, float) and report["strength"] == 0.5)
    order = report.get("order")
    require(type(order) is str and order in ORDERS)
    runs = report.get("runs")
    require(type(runs) is list and len(runs) == 10)
    values = {metric: {"A": [], "B": []} for metric in METRICS}
    for index, (run, arm) in enumerate(zip(runs, ORDERS[order])):
        require(type(run) is dict)
        for key, value in {"sequence": index, "arm": arm, "foundationWorkers": 2,
                           "glowWorkers": arm, "toneWorkers": arm, "mistWorkers": arm,
                           "fuseR7ColorRows": arm == 2, "excludedWarmup": index < 2,
                           "status": "SUCCESS", "jpegMatchesSessionBaseline": True,
                           "sourceUnchanged": True, "sameJpegReadback": True}.items():
            expect(run, key, value)
        require(not any(key in run for key in ("memoryObserved", "memoryBefore", "memoryAfter")))
        environment(run.get("environmentBefore"))
        environment(run.get("environmentAfter"))
        stages = run.get("stages")
        require(type(stages) is list and bool(stages))
        totals, counts = {}, {}
        for span in stages:
            require(type(span) is dict)
            stage = span.get("stage")
            require(type(stage) is str and stage in KNOWN)
            expect(span, "completed", True)
            totals[stage] = totals.get(stage, 0) + integer(span.get("elapsedNanos"))
            counts[stage] = counts.get(stage, 0) + 1
        require(REQUIRED <= totals.keys())
        if arm == 1:
            require("R7_COLOR_FUSED" not in totals and all(counts.get(s) == 1 for s in SEPARATE))
            color = sum(totals[s] for s in SEPARATE)
        else:
            require(not SEPARATE.intersection(totals) and counts.get("R7_COLOR_FUSED") == 1)
            color = totals["R7_COLOR_FUSED"]
        elapsed = integer(run.get("renderAndCloseNanos"))
        residual = integer(run.get("unattributedResidualNanos"))
        require(elapsed == residual + sum(n for s, n in totals.items() if s not in NESTED))
        per_attempt = dict(zip(METRICS, (totals["GLOW"], totals["MIST"],
                                        totals["R7_TONE_APPLY"], color, elapsed, residual)))
        if index >= 2:
            for metric, value in per_attempt.items():
                values[metric]["AB"[arm - 1]].append(value)
    return order, values


def median(values):
    require(len(values) == 4)
    ordered = sorted(values)
    return Fraction(ordered[1] + ordered[2], 2)


def number(value):
    return value.numerator if value.denominator == 1 else float(value)


def summary(values):
    return {"medianNanos": number(median(values)), "minNanos": min(values),
            "maxNanos": max(values), "measuredNanos": values}


def compare(first, second, same_input_build_confirmed=False):
    require(same_input_build_confirmed is True)
    validated = [validate(first), validate(second)]
    require({order for order, _ in validated} == set(ORDERS))
    reports = dict(validated)
    output = {"status": "VALID_COMPARISON", "thresholdPercent": 10,
              "sameInputBuildOperatorConfirmed": True,
              "environmentConsistency": "OPERATOR_CHECK_THERMAL_CHARGE_COOLDOWN",
              "metrics": {}}
    for metric in METRICS:
        orders, passes = {}, []
        for order in ORDERS:
            arms = reports[order][metric]
            a, b = median(arms["A"]), median(arms["B"])
            # Integer/rational arithmetic makes the inclusive 10% boundary exact.
            if metric in METRICS[:4]:
                require(a > 0)
            passed = a > 0 and b * 10 <= a * 9
            passes.append(passed)
            orders[order] = {"A": summary(arms["A"]), "B": summary(arms["B"]),
                             "improvementPercent": number((a - b) * 100 / a) if a else None}
        if metric in METRICS[4:]:
            decision = "CONTEXT_ONLY"
        elif passes[0] != passes[1]:
            decision = "ADDITIONAL_ORDER_COMPARISON"
        elif not passes[0]:
            decision = "DEFER_THIS_CONDITION"
        elif metric in ("tone", "color"):
            decision = "ADDITIONAL_R7_COMPARISON"
        else:
            decision = "ADOPT_PERFORMANCE"
        output["metrics"][metric] = {"orders": orders, "decision": decision}
    return output


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


class PrivateParser(argparse.ArgumentParser):
    def error(self, message):
        raise InvalidReport("INVALID_ARGUMENTS")


def main(argv=None):
    parser = PrivateParser(prog="compare_raw4", description=__doc__)
    parser.add_argument("first_report")
    parser.add_argument("second_report")
    parser.add_argument("--same-input-build-confirmed", action="store_true", required=True,
                        help="Operator verified the same source bytes and build in both sessions.")
    try:
        args = parser.parse_args(argv)
        reports = []
        for path in (args.first_report, args.second_report):
            with open(path, encoding="utf-8") as stream:
                reports.append(json.load(stream, object_pairs_hook=unique_object))
        result = compare(*reports, same_input_build_confirmed=args.same_input_build_confirmed)
    except (InvalidReport, OSError, ValueError, TypeError, OverflowError, RecursionError):
        print(json.dumps({"status": "REJECT_INVALID_OR_INCOMPLETE_REPORT"}))
        return 2
    print(json.dumps(result, indent=2, allow_nan=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
