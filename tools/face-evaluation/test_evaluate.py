import unittest
from pathlib import Path
import tempfile
from evaluate import score, summarize
from evaluate import outside_git


class EvaluationTest(unittest.TestCase):
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
