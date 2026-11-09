import unittest
from summarize_processing import summarize


def event(ms, kind="DRAFT", outcome="SUCCESS", sequence=1):
    ns = ms * 1000000
    return f"11-09 12:00:00.000 100 200 D RawPreviewTiming: kind={kind} sequence={sequence} outcome={outcome} totalNs={ns} INPUTNs={ns}"


class ProcessingSummaryTest(unittest.TestCase):
    def test_statistics_do_not_claim_presentation(self):
        result = summarize([event(100), event(20), event(30), event(40)], warmup=1)
        self.assertEqual((30, 40, 40), (result["median_ms"], result["p95_ms"], result["max_ms"]))
        self.assertEqual("NOT_MEASURED", result["input_to_present_50ms"])

    def test_exact_is_not_draft(self):
        self.assertEqual(1, summarize([event(20), event(2000, "EXACT")])["sample_count"])

    def test_failed_is_counted_not_timed(self):
        result = summarize([event(20), event(500, outcome="FAILED")])
        self.assertEqual(1, result["failed_records"])
        self.assertEqual(20, result["median_ms"])

    def test_empty_is_not_success(self):
        self.assertEqual("INSUFFICIENT_OR_INVALID", summarize([])["status"])

    def test_corrupt_sum_rejected(self):
        result = summarize([event(10).replace("INPUTNs=10000000", "INPUTNs=1")])
        self.assertEqual(1, result["malformed_records"])
        self.assertIsNone(result["median_ms"])

    def test_duplicate_fields_rejected(self):
        self.assertEqual(1, summarize([event(10) + " totalNs=10000000"])["malformed_records"])

    def test_other_tags_ignored(self):
        self.assertEqual(0, summarize([event(10).replace("RawPreviewTiming", "PreviewTiming")])["sample_count"])

    def test_brief_format_supported(self):
        line = event(10).split("RawPreviewTiming: ")[1]
        self.assertEqual(10, summarize(["D/RawPreviewTiming( 123): " + line])["median_ms"])

    def test_too_much_warmup_is_not_success(self):
        self.assertEqual("INSUFFICIENT_OR_INVALID", summarize([event(10)], warmup=2)["status"])

    def test_diagnostic_workers_are_separated_and_each_render_is_reported(self):
        lines = [event(20) + " workers=1 maxEdge=256", event(10) + " workers=2 maxEdge=256",
                 event(12) + " workers=2 maxEdge=256"]
        self.assertTrue(summarize(lines)["mixed_worker_configurations"])
        self.assertIsNone(summarize(lines)["median_ms"])
        result = summarize(lines, workers=2)
        self.assertEqual([10, 12], result["per_render_ms"])
        self.assertEqual(11, result["median_ms"])
        self.assertEqual("NOT_MEASURED", result["input_to_present_50ms"])

    def test_diagnostic_dimensions_and_workers_are_validated(self):
        for suffix in (" workers=3 maxEdge=256", " workers=2 maxEdge=1024", " workers=1", " maxEdge=256"):
            self.assertEqual(1, summarize([event(10) + suffix])["malformed_records"])
        self.assertEqual(10, summarize([event(10, "EXACT") + " workers=2 maxEdge=1024"], kind="EXACT", workers=2)["median_ms"])

    def test_bad_kind_and_negative_values(self):
        with self.assertRaises(ValueError):
            summarize([], warmup=-1)
        self.assertEqual(1, summarize([event(-1)])["malformed_records"])


if __name__ == "__main__":
    unittest.main()
