import unittest
from pathlib import Path
import tempfile
from evaluate import score, summarize
from evaluate import outside_git


class EvaluationTest(unittest.TestCase):
    @staticmethod
    def coverage_labels():
        return {"authority": "human", "split": "held-out", "samples": {
            "synthetic-front": {"geometry": "test", "size": [640, 480], "condition": "front", "boxes": []},
            "synthetic-dark": {"geometry": "test", "size": [640, 480], "condition": "low-light", "boxes": [[0, 0, 10, 10]]}}}

    @staticmethod
    def coverage_row(detector="test-a", sample="synthetic-front", status="SUCCESS"):
        return {"detector": detector, "sample": sample, "geometry": "test", "width": 640, "height": 480,
                "status": status, "boxes": [], "phase": "warm", "sdk_ns": 1, "adapter_ns": 1, "total_ns": 2}

    def test_missing_condition_remains_unscored(self):
        groups = summarize([self.coverage_row()], self.coverage_labels(), .5)["groups"]
        self.assertEqual(len(groups), 2)
        dark = next(g for g in groups if g["condition"] == "low-light")
        self.assertEqual((dark["expected_samples"], dark["scored_samples"], dark["unscored_samples"]), (1, 0, 1))
        self.assertEqual((dark["tp"], dark["fp"], dark["fn"]), (0, 0, 0))
        self.assertEqual(sum(dark["states"].values()), 0)
        self.assertEqual(dark["timings"], {})

    def test_empty_results_are_rejected_without_a_detector_roster(self):
        with self.assertRaisesRegex(ValueError, "at least one result row"):
            summarize([], self.coverage_labels(), .5)

    def test_empty_labels_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "at least one labeled sample"):
            summarize([self.coverage_row()], {**self.coverage_labels(), "samples": {}}, .5)

    def test_all_failed_runs_never_become_scored_or_timing_observations(self):
        rows = [self.coverage_row(status=state) for state in ("FAILED", "CANCELLED", "NOT_RUN")]
        groups = summarize(rows, self.coverage_labels(), .5)["groups"]
        self.assertEqual(len(groups), 2)
        for group in groups:
            self.assertEqual((group["scored_samples"], group["unscored_samples"]), (0, 1))
            self.assertEqual((group["tp"], group["fp"], group["fn"]), (0, 0, 0))
            self.assertEqual(group["timings"], {})
        front = next(g for g in groups if g["condition"] == "front")
        self.assertEqual(front["failed_runs"], 3)
        self.assertEqual(front["states"], {"SUCCESS": 0, "FAILED": 1, "CANCELLED": 1, "NOT_RUN": 1})

    def test_condition_coverage_is_independent_for_each_detector(self):
        rows = [self.coverage_row(), self.coverage_row(sample="synthetic-dark"), self.coverage_row(detector="test-b")]
        groups = {(g["detector"], g["condition"]): g for g in summarize(rows, self.coverage_labels(), .5)["groups"]}
        self.assertEqual(set(groups), {(d, c) for d in ("test-a", "test-b") for c in ("front", "low-light")})
        self.assertEqual(groups["test-a", "low-light"]["fn"], 1)
        self.assertEqual(groups["test-a", "low-light"]["unscored_samples"], 0)
        self.assertEqual(groups["test-b", "low-light"]["fn"], 0)
        self.assertEqual(groups["test-b", "low-light"]["unscored_samples"], 1)

    def test_duplicate_is_false_positive(self):
        self.assertEqual(score([[0, 0, 10, 10]], [[0, 0, 10, 10]]*2, .5), {"tp": 1, "fn": 0, "fp": 1})

    def test_empty_and_missed(self):
        self.assertEqual(score([], [], .5), {"tp": 0, "fn": 0, "fp": 0})
        self.assertEqual(score([[0, 0, 10, 10]], [], .5)["fn"], 1)

    def test_augmenting_path_avoids_greedy_miss(self):
        self.assertEqual(score([[0, 0, 10, 10], [5, 0, 10, 10]], [[2, 0, 10, 10], [0, 0, 5, 10]], .4)["tp"], 2)

    def test_reject_invalid(self):
        for threshold in (0, 2, float("nan")):
            with self.assertRaises(ValueError): score([], [], threshold)
        with self.assertRaises(ValueError): score([[0, 0, -1, 1]], [], .5)

    def test_private_outputs_reject_checkout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git").write_text("gitdir: external-metadata", encoding="utf-8")
            with self.assertRaises(ValueError): outside_git(root / "nested" / "results.json")

    def test_disjoint_box_iou_does_not_match(self):
        self.assertEqual(score([[0, 0, 1, 1]], [[5, 5, 1, 1]], .5), {"tp": 0, "fp": 1, "fn": 1})

    def test_failure_not_face_absence_and_repeats_not_accuracy_samples(self):
        labels = {"authority": "human", "split": "held-out", "samples": {"synthetic-black": {
            "geometry": "test", "size": [640, 480], "condition": "empty", "boxes": []}}}
        row = {"detector": "test", "sample": "synthetic-black", "geometry": "test", "width": 640, "height": 480,
               "status": "SUCCESS", "boxes": [], "phase": "warm", "sdk_ns": 1, "adapter_ns": 1, "total_ns": 2}
        group = summarize([row, row, {**row, "status": "FAILED"}], labels, .5)["groups"][0]
        self.assertEqual(group["scored_samples"], 1)
        self.assertEqual(group["failed_runs"], 1)
        self.assertEqual(group["timings"]["warm:total_ns"]["n"], 2)
        with self.assertRaises(ValueError): summarize([row], {**labels, "authority": "yunet"}, .5)


if __name__ == "__main__": unittest.main()
