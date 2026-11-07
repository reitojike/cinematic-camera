"""Synthetic fixtures only: no photos, report metadata, or local input paths."""
import contextlib
import copy
import io
import json
import unittest
from unittest.mock import patch

import compare_raw4 as c


def fixture(order="AB-ABBAABBA", b=90):
    report = dict(schema=2, scope="private-original24-no-publication", mode="TIMING",
                  comparison="RAW4", strength=0.5, warmth=0, exposureAdjustmentEv=0,
                  ortonWorkers=4, characterWorkers=2, performanceThresholdPercent=10,
                  performanceDecision="REQUIRES_BOTH_ORDERS", complete=True, status="SUCCESS",
                  selectedOriginalUnchanged=True, privateInputRemoved=True, harnessCloseFailed=False,
                  resourcesQuiescent=True, measuredSuccessesA=4, measuredSuccessesB=4, order=order, runs=[])
    env = dict(thermalStatus=0, batteryTemperatureTenthsC=250, batteryStatus=3, charging=False)
    for index, arm in enumerate(c.ORDERS[order]):
        stages = [{"stage": stage, "elapsedNanos": 100, "completed": True}
                  for stage in sorted(c.REQUIRED)]
        stages += [{"stage": stage, "elapsedNanos": value, "completed": True}
                   for stage, value in ([(s, 100) for s in sorted(c.SEPARATE)] if arm == 1
                                        else [("R7_COLOR_FUSED", 3 * b)])]
        for span in stages:
            if arm == 2 and span["stage"] in ("GLOW", "MIST", "R7_TONE_APPLY"):
                span["elapsedNanos"] = b
        run = dict(sequence=index, arm=arm, foundationWorkers=2, glowWorkers=arm,
                   toneWorkers=arm, mistWorkers=arm, fuseR7ColorRows=arm == 2,
                   excludedWarmup=index < 2, status="SUCCESS", jpegMatchesSessionBaseline=True,
                   sourceUnchanged=True, sameJpegReadback=True, environmentBefore=env.copy(),
                   environmentAfter=env.copy(), stages=stages, unattributedResidualNanos=10)
        recount(run)
        report["runs"].append(run)
    return report


def recount(run):
    run["renderAndCloseNanos"] = run["unattributedResidualNanos"] + sum(
        s["elapsedNanos"] for s in run["stages"] if s["stage"] not in c.NESTED)


def pair(a=90, b=90):
    return fixture(b=a), fixture("BA-BAABBAAB", b=b)


class ComparisonTest(unittest.TestCase):
    def result(self, a=90, b=90):
        return c.compare(*pair(a, b), same_input_build_confirmed=True)

    def test_inclusive_ten_percent_and_r7_followup(self):
        result = self.result()
        for metric in ("glow", "mist"):
            self.assertEqual(result["metrics"][metric]["decision"], "ADOPT_PERFORMANCE")
        for metric in ("tone", "color"):
            self.assertEqual(result["metrics"][metric]["decision"], "ADDITIONAL_R7_COMPARISON")
        self.assertEqual(result["metrics"]["glow"]["orders"]["AB-ABBAABBA"]["improvementPercent"], 10)

    def test_subthreshold_and_regression_defer(self):
        for value in (91, 100, 120):
            for metric in c.METRICS[:4]:
                self.assertEqual(self.result(value, value)["metrics"][metric]["decision"],
                                 "DEFER_THIS_CONDITION")

    def test_disagreement_requires_order_comparison(self):
        for metric in c.METRICS[:4]:
            self.assertEqual(self.result(90, 91)["metrics"][metric]["decision"],
                             "ADDITIONAL_ORDER_COMPARISON")

    def test_repeated_stage_sum_before_median_and_exclude_warmups(self):
        reports = pair()
        for report in reports:
            for run in report["runs"]:
                mist = next(s for s in run["stages"] if s["stage"] == "MIST")
                mist["elapsedNanos"] = 100000 if run["excludedWarmup"] else (100 if run["arm"] == 1 else 90)
                run["stages"].append(copy.deepcopy(mist))
                recount(run)
        result = c.compare(*reports, same_input_build_confirmed=True)
        summary = result["metrics"]["mist"]["orders"]["AB-ABBAABBA"]["A"]
        self.assertEqual(summary, dict(medianNanos=200, minNanos=200, maxNanos=200, measuredNanos=[200]*4))

    def test_fractional_median(self):
        self.assertEqual(c.median([1, 2, 3, 4]), c.Fraction(5, 2))

    def test_whole_elapsed_does_not_veto_stage(self):
        reports = pair()
        for report in reports:
            for run in report["runs"]:
                if run["arm"] == 2:
                    run["unattributedResidualNanos"] = 100000
                    recount(run)
        result = c.compare(*reports, same_input_build_confirmed=True)
        self.assertEqual(result["metrics"]["glow"]["decision"], "ADOPT_PERFORMANCE")
        self.assertEqual(result["metrics"]["renderAndClose"]["decision"], "CONTEXT_ONLY")

    def test_report_quality_config_failures(self):
        for key, value in dict(schema=1, mode="MEMORY_PERIODIC_NOT_TIMING", comparison="TONE_ONLY",
                               strength=0, warmth=1, exposureAdjustmentEv=1, ortonWorkers=1,
                               complete=False, status="FAILED", selectedOriginalUnchanged=False,
                               privateInputRemoved=False, harnessCloseFailed=True,
                               resourcesQuiescent=False, measuredSuccessesA=3, order="AB").items():
            with self.subTest(key=key):
                a, b = pair()
                a[key] = value
                with self.assertRaises(c.InvalidReport):
                    c.compare(a, b, same_input_build_confirmed=True)

    def test_run_quality_config_failures_include_warmups(self):
        for key, value in dict(sequence=True, arm=2, foundationWorkers=1, glowWorkers=2,
                               toneWorkers=2, mistWorkers=2, fuseR7ColorRows=True,
                               excludedWarmup=False, status="FAILED_MEMORY_OBSERVATION",
                               jpegMatchesSessionBaseline=False, sourceUnchanged=False,
                               sameJpegReadback=False, memoryObserved={}).items():
            with self.subTest(key=key):
                a, b = pair()
                a["runs"][0][key] = value
                with self.assertRaises(c.InvalidReport):
                    c.compare(a, b, same_input_build_confirmed=True)

    def test_invalid_spans(self):
        mutations = [lambda s: s.pop(), lambda s: s[0].update(completed=False),
                     lambda s: s.append(dict(stage="UNKNOWN", elapsedNanos=1, completed=True)),
                     lambda s: s.append(dict(stage="R7_COLOR_FUSED", elapsedNanos=1, completed=True)),
                     lambda s: s.append(copy.deepcopy(s[-1]))]
        for value in (-1, True, 0.5, "100", None, float("nan"), float("inf")):
            mutations.append(lambda s, v=value: s[0].update(elapsedNanos=v))
        for mutate in mutations:
            a, b = pair()
            mutate(a["runs"][0]["stages"])
            with self.assertRaises(c.InvalidReport):
                c.compare(a, b, same_input_build_confirmed=True)

    def test_missing_target_stage_is_not_zero(self):
        for stage in ("GLOW", "MIST", "R7_TONE_APPLY", "R7_COLOR_FUSED"):
            a, b = pair()
            run = a["runs"][1]
            run["stages"] = [s for s in run["stages"] if s["stage"] != stage]
            recount(run)
            with self.assertRaises(c.InvalidReport):
                c.compare(a, b, same_input_build_confirmed=True)

    def test_thermal_and_residual_safety(self):
        for mutate in (lambda r: r["environmentAfter"].update(thermalStatus=2),
                       lambda r: r["environmentBefore"].update(charging=True),
                       lambda r: r.update(unattributedResidualNanos=-1),
                       lambda r: r.update(renderAndCloseNanos=0)):
            a, b = pair()
            mutate(a["runs"][0])
            with self.assertRaises(c.InvalidReport):
                c.compare(a, b, same_input_build_confirmed=True)

    def test_order_count_and_attestation(self):
        a, b = pair()
        with self.assertRaises(c.InvalidReport):
            c.compare(a, b)
        with self.assertRaises(c.InvalidReport):
            c.compare(a, a, same_input_build_confirmed=True)
        a["runs"].pop()
        with self.assertRaises(c.InvalidReport):
            c.compare(a, b, same_input_build_confirmed=True)

    def test_public_output_never_echoes_metadata_or_errors(self):
        a, b = pair()
        a["privateMetadata"] = "DO_NOT_ECHO"
        self.assertNotIn("DO_NOT_ECHO", json.dumps(c.compare(a, b, same_input_build_confirmed=True)))
        for argv in (["DO_NOT_ECHO"], ["--unknown=DO_NOT_ECHO"],
                     ["DO_NOT_ECHO", "other", "--same-input-build-confirmed"]):
            output = io.StringIO()
            with patch("builtins.open", side_effect=OSError("DO_NOT_ECHO")), contextlib.redirect_stdout(output):
                self.assertEqual(c.main(argv), 2)
            self.assertEqual(json.loads(output.getvalue()), {"status": "REJECT_INVALID_OR_INCOMPLETE_REPORT"})

    def test_cli_success_and_fused_duplicate_rejection(self):
        a, b = pair()
        streams = [io.StringIO(json.dumps(a)), io.StringIO(json.dumps(b))]
        output = io.StringIO()
        with patch("builtins.open", side_effect=streams), contextlib.redirect_stdout(output):
            self.assertEqual(c.main(["first.json", "second.json", "--same-input-build-confirmed"]), 0)
        self.assertEqual(json.loads(output.getvalue())["status"], "VALID_COMPARISON")
        fused = next(s for s in a["runs"][1]["stages"] if s["stage"] == "R7_COLOR_FUSED")
        a["runs"][1]["stages"].append(copy.deepcopy(fused))
        recount(a["runs"][1])
        with self.assertRaises(c.InvalidReport):
            c.compare(a, b, same_input_build_confirmed=True)

    def test_exact_large_integer_boundary(self):
        reports = pair()
        for report in reports:
            for run in report["runs"]:
                for span in run["stages"]:
                    if span["stage"] == "GLOW":
                        span["elapsedNanos"] = 100000000000000010 if run["arm"] == 1 else 90000000000000009
                recount(run)
        result = c.compare(*reports, same_input_build_confirmed=True)
        self.assertEqual(result["metrics"]["glow"]["decision"], "ADOPT_PERFORMANCE")
        for report in reports:
            for run in report["runs"]:
                if run["arm"] == 2:
                    next(s for s in run["stages"] if s["stage"] == "GLOW")["elapsedNanos"] += 1
                    recount(run)
        result = c.compare(*reports, same_input_build_confirmed=True)
        self.assertEqual(result["metrics"]["glow"]["decision"], "DEFER_THIS_CONDITION")

    def test_duplicate_json_key_rejected(self):
        with self.assertRaises(c.InvalidReport):
            json.loads('{"schema":2,"schema":2}', object_pairs_hook=c.unique_object)


if __name__ == "__main__":
    unittest.main()
