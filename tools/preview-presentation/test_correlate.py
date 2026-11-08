"""外部ツールも端末も使わない、SQL出力を模した合成回帰試験。"""
import copy
import csv
import io
from pathlib import Path
import unittest
from unittest.mock import patch

from correlate import (
    FRAME_COLUMNS, MARKER_COLUMNS, analyze_trace, correlate, read_csv, render_sql,
)


APP = 20
INJECTOR = 10


def marker(marker_id, kind, fields, ts, dur=1, frame=None, **changes):
    row = dict(marker_id=marker_id, upid=INJECTOR if kind == "inject" else APP,
               utid=1, is_main_thread=1, ts=ts, dur=dur,
               name="cc.preview." + kind + ":" + fields,
               do_frame_id=frame, do_frame_count=1 if frame is not None else 0)
    row.update(changes)
    return row


def frame(marker_id=5, generation=11, do_frame_id=200, present_ts=160, **changes):
    row = dict(generation=generation, marker_id=marker_id, upid=APP,
               do_frame_id=do_frame_id, surface_token=300, display_token=400,
               surface_frame_id=500, display_frame_id=600,
               present_ts=present_ts, status="CANDIDATE_ONLY")
    row.update(changes)
    return row


def fixture():
    return [
        marker(1, "inject", "7:1:1000:2", 100, dur=20),
        marker(2, "input", "10:1000:MOVE", 110),
        marker(3, "request", "11:10:DRAFT", 120),
        marker(4, "adopt", "11", 130),
        marker(5, "draw", "11", 140, dur=5, frame=200),
    ], [frame()]


def csv_text(rows, columns):
    out = io.StringIO()
    writer = csv.DictWriter(out, fieldnames=columns)
    writer.writeheader()
    for row in rows:
        writer.writerow({k: "[NULL]" if v is None else v for k, v in row.items()})
    return "\n" + out.getvalue()


class CorrelationTest(unittest.TestCase):
    def setUp(self):
        self.markers, self.frames = fixture()

    def report(self):
        return correlate(self.markers, self.frames, APP, INJECTOR)

    def unresolved(self, reason):
        result = self.report()
        self.assertEqual(result["counts"]["correlated_candidates"], 0)
        self.assertIn(reason, result["generations"][0]["reasons"])
        self.assertNotIn("latency_ns", result["generations"][0])
        return result

    def test_complete_chain_allows_input_before_injection_returns(self):
        report = self.report()
        result = report["generations"][0]
        self.assertEqual(result["status"], "CORRELATED_CANDIDATE")
        self.assertEqual(result["latency_ns"], 60)
        self.assertEqual(result["input_latency_ns"], 50)
        self.assertEqual(report["status"], "CANDIDATE_ONLY")
        self.assertNotIn("PASS", str(report))

    def test_nanoseconds_do_not_round_to_milliseconds(self):
        self.frames[0]["present_ts"] = 50_000_101
        self.assertEqual(self.report()["generations"][0]["latency_ns"], 50_000_001)

    def test_duplicate_event_millisecond_is_not_disambiguated_by_time(self):
        self.markers.append(marker(6, "inject", "7:2:1000:2", 105))
        self.unresolved("INJECT_INPUT_NOT_UNIQUE")

    def test_duplicate_input_event_is_ambiguous(self):
        self.markers.append(marker(6, "input", "12:1000:MOVE", 112))
        self.unresolved("INJECT_INPUT_NOT_UNIQUE")

    def test_duplicate_input_id_at_different_event_is_rejected(self):
        self.markers.append(marker(6, "input", "10:1001:MOVE", 112))
        self.unresolved("INPUT_MISSING_OR_DUPLICATE")

    def test_duplicate_run_and_index_is_rejected(self):
        self.markers.append(marker(6, "inject", "7:1:1001:2", 105))
        self.unresolved("INJECT_INPUT_NOT_UNIQUE")

    def test_different_action_does_not_match_same_timestamp(self):
        self.markers[0]["name"] = "cc.preview.inject:7:1:1000:1"
        self.unresolved("INJECT_INPUT_NOT_UNIQUE")

    def test_missing_each_chain_stage_is_unresolved(self):
        expected = ("INJECT_INPUT_NOT_UNIQUE", "INPUT_MISSING_OR_DUPLICATE",
                    "REQUEST_MISSING_OR_DUPLICATE", "ADOPT_MISSING_OR_DUPLICATE", "DRAW_MISSING")
        for index, reason in enumerate(expected):
            with self.subTest(stage=index):
                self.markers, self.frames = fixture()
                self.markers.pop(index)
                if index == 4:
                    self.frames = []
                self.unresolved(reason)

    def test_duplicate_request_or_adopt_is_rejected(self):
        for index, reason in ((2, "REQUEST_MISSING_OR_DUPLICATE"), (3, "ADOPT_MISSING_OR_DUPLICATE")):
            with self.subTest(stage=index):
                self.markers, self.frames = fixture()
                duplicate = dict(self.markers[index], marker_id=6)
                self.markers.append(duplicate)
                self.unresolved(reason)

    def test_incomplete_chain_stages_never_correlate(self):
        for index in range(5):
            with self.subTest(stage=index):
                self.markers, self.frames = fixture()
                self.markers[index]["dur"] = -1
                report = self.report()
                self.assertEqual(report["counts"]["correlated_candidates"], 0)

    def test_reversed_stage_order_is_rejected(self):
        for index in (1, 2, 3, 4):
            with self.subTest(stage=index):
                self.markers, self.frames = fixture()
                self.markers[index]["ts"] = 99
                report = self.report()
                self.assertEqual(report["counts"]["correlated_candidates"], 0)

    def test_mixed_frame_with_orphan_unresolved_generation_is_rejected(self):
        self.markers.append(marker(6, "draw", "12", 142, frame=200))
        self.frames.append(frame(6, 12, status="UNRESOLVED", do_frame_id=None, present_ts=None))
        self.unresolved("MIXED_GENERATIONS")

    def test_mixed_frame_with_incomplete_orphan_generation_is_rejected(self):
        self.markers.append(marker(6, "draw", "12", 142, dur=-1, frame=200))
        self.unresolved("MIXED_GENERATIONS")

    def test_earliest_unresolved_frame_cannot_be_rescued_by_redraw(self):
        self.frames[0].update(status="UNRESOLVED", do_frame_id=None, present_ts=None)
        self.markers.append(marker(6, "draw", "11", 150, frame=201))
        self.frames.append(frame(6, do_frame_id=201, present_ts=170))
        self.unresolved("FIRST_FRAME_UNRESOLVED")

    def test_earliest_unknown_draw_cannot_be_rescued_by_later_frame(self):
        self.markers[-1].update(do_frame_id=None, do_frame_count=0)
        self.frames = [frame(status="UNRESOLVED", do_frame_id=None, present_ts=None)]
        self.markers.append(marker(6, "draw", "11", 150, frame=201))
        self.frames.append(frame(6, do_frame_id=201, present_ts=170))
        self.unresolved("FIRST_DRAW_FRAME_UNKNOWN")

    def test_empty_trace_does_not_report_a_candidate(self):
        self.markers, self.frames = [], []
        report = self.report()
        self.assertEqual(report["counts"]["correlated_candidates"], 0)
        self.assertEqual(report["status"], "CANDIDATE_ONLY")

    def test_same_frame_draws_and_later_redraw_count_once(self):
        self.markers += [marker(6, "draw", "11", 146, frame=200),
                         marker(7, "draw", "11", 170, frame=201)]
        self.frames += [frame(6), frame(7, do_frame_id=201, present_ts=190)]
        report = self.report()
        self.assertEqual(report["counts"]["correlated_candidates"], 1)
        self.assertEqual(report["generations"][0]["draw_count"], 3)
        self.assertEqual(report["generations"][0]["latency_ns"], 60)

    def test_different_surface_identity_in_same_frame_is_rejected(self):
        self.markers.append(marker(6, "draw", "11", 146, frame=200))
        self.frames.append(frame(6, surface_frame_id=501))
        self.unresolved("FIRST_FRAME_NOT_UNIQUE")

    def test_unknown_or_multiple_draw_ancestors_are_rejected(self):
        for parent, count in ((None, 0), (200, 2)):
            with self.subTest(count=count):
                self.markers, self.frames = fixture()
                self.markers[-1].update(do_frame_id=parent, do_frame_count=count)
                self.unresolved("FIRST_DRAW_FRAME_UNKNOWN")

    def test_equal_first_timestamp_different_frames_is_ambiguous(self):
        self.markers.append(marker(6, "draw", "11", 140, frame=201))
        self.frames.append(frame(6, do_frame_id=201))
        self.unresolved("FIRST_DRAW_AMBIGUOUS")

    def test_missing_or_duplicate_frame_row_does_not_correlate(self):
        for rows in ([], [frame(), frame()]):
            with self.subTest(rows=len(rows)):
                self.frames = rows
                self.unresolved("FIRST_FRAME_UNRESOLVED")

    def test_invalid_frame_fields_are_rejected(self):
        for field, value in (("do_frame_id", 201), ("display_token", 0),
                             ("surface_token", None), ("present_ts", 144)):
            with self.subTest(field=field):
                self.markers, self.frames = fixture()
                self.frames[0][field] = value
                self.unresolved("INVALID_FRAME_CANDIDATE")

    def test_new_request_does_not_inherit_old_image_draw(self):
        self.markers.append(marker(6, "request", "12:10:EXACT", 150))
        report = self.report()
        self.assertEqual(report["generations"][0]["status"], "CORRELATED_CANDIDATE")
        self.assertEqual(report["generations"][1]["status"], "UNRESOLVED")
        self.assertIn("DRAW_MISSING", report["generations"][1]["reasons"])

    def test_draft_and_exact_from_one_input_remain_separate(self):
        self.markers += [marker(6, "request", "12:10:EXACT", 121),
                         marker(7, "adopt", "12", 170),
                         marker(8, "draw", "12", 180, frame=201)]
        self.frames.append(frame(8, 12, do_frame_id=201, present_ts=200))
        report = self.report()
        self.assertEqual([g["phase"] for g in report["generations"]], ["DRAFT", "EXACT"])
        self.assertEqual([g["latency_ns"] for g in report["generations"]], [60, 100])
        self.assertEqual(report["counts"]["correlated_candidates"], 2)

    def test_no_request_and_unobserved_input_remain_in_output(self):
        self.markers = self.markers[:2] + [
            marker(6, "inject", "7:2:1001:2", 150),
            marker(7, "inject", "7:0:999:0", 80),
        ]
        self.frames = []
        report = self.report()
        self.assertEqual(report["counts"], dict(injections=3, inputs=1, generations=0,
                                                correlated_candidates=0, unresolved_generations=0))
        self.assertEqual(report["inputs"][0]["status"], "NO_REQUEST")
        self.assertEqual(report["injections"][0]["status"], "CONTROL")
        self.assertIn("INPUT_NOT_OBSERVED", report["injections"][-1]["reasons"])

    def test_foreign_process_cannot_supply_reused_id(self):
        self.markers[1]["upid"] = 21
        with self.assertRaises(ValueError):
            self.report()

    def test_non_main_app_marker_is_rejected(self):
        self.markers[-1]["is_main_thread"] = 0
        with self.assertRaises(ValueError):
            self.report()

    def test_same_process_instrumentation_is_supported(self):
        self.markers[0]["upid"] = APP
        report = correlate(self.markers, self.frames, APP, APP)
        self.assertEqual(report["counts"]["correlated_candidates"], 1)

    def test_malformed_marker_or_numeric_column_stops_analysis(self):
        for field, value in (("name", "cc.preview.draw:11:extra"), ("ts", 1.5),
                             ("marker_id", True), ("name", "cc.preview.unknown:11")):
            with self.subTest(field=field, value=value):
                self.markers, self.frames = fixture()
                self.markers[-1][field] = value
                with self.assertRaises(ValueError):
                    self.report()

    def test_duplicate_marker_id_stops_analysis(self):
        self.markers.append(copy.deepcopy(self.markers[0]))
        with self.assertRaises(ValueError):
            self.report()

    def test_mismatched_frame_export_stops_analysis(self):
        for field, value in (("marker_id", 99), ("generation", 12), ("upid", 21), ("status", "PASS")):
            with self.subTest(field=field):
                self.markers, self.frames = fixture()
                self.frames[0][field] = value
                with self.assertRaises(ValueError):
                    self.report()


class ExportTest(unittest.TestCase):
    def test_csv_roundtrip_accepts_perfetto_null_and_leading_blank_lines(self):
        markers, frames = fixture()
        parsed = [read_csv(csv_text(rows, columns), columns)
                  for rows, columns in ((markers, MARKER_COLUMNS), (frames, FRAME_COLUMNS))]
        self.assertEqual(correlate(*parsed, APP, INJECTOR)["counts"]["correlated_candidates"], 1)

    def test_csv_wrong_headers_and_row_width_are_rejected(self):
        for text in ("wrong\n1\n", ",".join(MARKER_COLUMNS) + "\n1,2\n"):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    read_csv(text, MARKER_COLUMNS)

    def test_sql_uses_exact_processes_and_escapes_layer_literal(self):
        sql = render_sql("draw_frames.sql", APP, INJECTOR, "Image's layer")
        self.assertIn("upid = 20", sql)
        self.assertIn("'Image''s layer'", sql)
        self.assertNotIn("TARGET_APP_UPID", sql)
        markers = render_sql("all_markers.sql", APP, INJECTOR, "layer")
        self.assertIn("upid IN (20, 10)", markers)
        self.assertNotIn("dur >= 0", markers)

    @patch("correlate.trace_hash", return_value="synthetic_hash")
    @patch("correlate.subprocess.run")
    def test_runner_reads_same_trace_for_both_exports(self, run, trace_hash):
        markers, frames = fixture()
        run.side_effect = [
            type("Result", (), {"stdout": text})()
            for text in ("Perfetto v57.2\n", csv_text(markers, MARKER_COLUMNS),
                         csv_text(frames, FRAME_COLUMNS))
        ]
        result = analyze_trace(Path("synthetic.trace"), Path("processor"), APP, INJECTOR, "layer")
        self.assertEqual(result["counts"]["correlated_candidates"], 1)
        calls = [call.args[0] for call in run.call_args_list[1:]]
        self.assertTrue(all(command[:3] == ["processor", "query", "synthetic.trace"] for command in calls))
        self.assertEqual(result["source"]["trace_sha256"], "synthetic_hash")

    @patch("correlate.trace_hash", side_effect=["before", "after"])
    @patch("correlate.subprocess.run")
    def test_changing_trace_is_rejected(self, run, trace_hash):
        markers, frames = fixture()
        run.side_effect = [
            type("Result", (), {"stdout": text})()
            for text in ("Perfetto v57.2\n", csv_text(markers, MARKER_COLUMNS),
                         csv_text(frames, FRAME_COLUMNS))
        ]
        with self.assertRaisesRegex(ValueError, "traceファイルが変わった"):
            analyze_trace(Path("synthetic.trace"), Path("processor"), APP, INJECTOR, "layer")

    @patch("correlate.subprocess.run")
    def test_different_processor_version_is_rejected_before_query(self, run):
        run.return_value.stdout = "Perfetto v57.20\n"
        with self.assertRaisesRegex(ValueError, "v57.2"):
            analyze_trace(Path("synthetic.trace"), Path("processor"), APP, INJECTOR, "layer")
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
