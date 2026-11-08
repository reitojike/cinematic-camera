"""#234: 同一traceの提示候補を保守的に結ぶ。実機の50ms合否は判定しない。"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
from dataclasses import dataclass
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import sys

PREFIX = "cc.preview."
PATTERNS = {
    "inject": re.compile(r"(0|[1-9][0-9]*):(0|[1-9][0-9]*):(0|[1-9][0-9]*):([012])"),
    "input": re.compile(r"([1-9][0-9]*):(0|[1-9][0-9]*):(MOVE|UP)"),
    "request": re.compile(r"([1-9][0-9]*):([1-9][0-9]*):(DRAFT|EXACT)"),
    "adopt": re.compile(r"([1-9][0-9]*)"),
    "draw": re.compile(r"([1-9][0-9]*)"),
}
MARKER_COLUMNS = (
    "marker_id", "upid", "utid", "is_main_thread", "ts", "dur", "name",
    "do_frame_id", "do_frame_count",
)
FRAME_COLUMNS = (
    "generation", "marker_id", "upid", "do_frame_id", "surface_token",
    "display_token", "surface_frame_id", "display_frame_id", "present_ts", "status",
)
FRAME_IDENTITY = (
    "surface_token", "display_token", "surface_frame_id", "display_frame_id", "present_ts",
)


def integer(value, nullable=False):
    if nullable and value in (None, "", "[NULL]"):
        return None
    if isinstance(value, bool) or not re.fullmatch(r"-?[0-9]+", str(value)):
        raise ValueError("整数列の形式が不正")
    return int(value)


def read_csv(text, columns):
    # INCLUDEなどの結果を持たない文に対してTPが先頭に空行を出す。
    reader = csv.DictReader(io.StringIO(text.lstrip("\ufeff\r\n")), strict=True)
    if reader.fieldnames != list(columns):
        raise ValueError("CSV列が想定したSQLの出力と一致しない")
    rows = list(reader)
    if any(None in row or any(v is None for v in row.values()) for row in rows):
        raise ValueError("CSV行の列数が不正")
    return rows


@dataclass(frozen=True)
class Marker:
    id: int
    upid: int
    utid: int
    main: int
    ts: int
    dur: int
    frame: int | None
    parents: int
    kind: str
    args: tuple

    @property
    def end(self):
        return self.ts + self.dur


def marker_from_row(row, app_upid, injector_upid):
    name = row["name"]
    if not name.startswith(PREFIX) or ":" not in name[len(PREFIX):]:
        raise ValueError("未知の診断marker")
    kind, value = name[len(PREFIX):].split(":", 1)
    match = PATTERNS.get(kind)
    match = match.fullmatch(value) if match else None
    if match is None:
        raise ValueError("診断markerの形式が不正")
    args = tuple(int(v) if v.isdecimal() else v for v in match.groups())
    m = Marker(
        *(integer(row[k]) for k in ("marker_id", "upid", "utid", "is_main_thread", "ts", "dur")),
        integer(row["do_frame_id"], nullable=True), integer(row["do_frame_count"]), kind, args,
    )
    if min(m.id, m.upid, m.utid, m.parents) < 0:
        raise ValueError("診断markerの識別子が不正")
    if m.upid != (injector_upid if kind == "inject" else app_upid):
        raise ValueError("選択したprocessと診断markerの所属が一致しない")
    if kind != "inject" and m.main != 1:
        raise ValueError("appの診断markerがmain threadではない")
    return m


def correlate(marker_rows, frame_rows, app_upid, injector_upid):
    """SQL出力だけを扱う純粋関数。最初のdrawが不明でも後続drawで救済しない。"""
    markers = [marker_from_row(r, app_upid, injector_upid) for r in marker_rows]
    by_id = {m.id: m for m in markers}
    if len(by_id) != len(markers):
        raise ValueError("marker_idが重複")
    kinds = defaultdict(list)
    for m in sorted(markers, key=lambda m: (m.ts, m.id)):
        kinds[m.kind].append(m)

    frames = defaultdict(list)
    for row in frame_rows:
        f = {k: row[k] if k == "status" else integer(row[k], nullable=True)
             for k in FRAME_COLUMNS}
        marker = by_id.get(f["marker_id"])
        if marker is None or marker.kind != "draw":
            raise ValueError("frame候補に同じtraceのdraw markerがない")
        if f["upid"] != marker.upid or f["generation"] != marker.args[0]:
            raise ValueError("frame候補のprocess/世代が一致しない")
        if f["status"] not in ("UNRESOLVED", "CANDIDATE_ONLY"):
            raise ValueError("未知のframe候補status")
        frames[marker.id].append(f)

    inject_by_event, input_by_event, input_by_id = (defaultdict(list) for _ in range(3))
    inject_keys = Counter(m.args[:2] for m in kinds["inject"])
    for m in kinds["inject"]:
        inject_by_event[(m.args[2], {0: "DOWN", 1: "UP", 2: "MOVE"}[m.args[3]])].append(m)
    for m in kinds["input"]:
        input_by_event[m.args[1:]].append(m)
        input_by_id[m.args[0]].append(m)

    injections, inputs, input_links = [], [], {}
    for m in kinds["inject"]:
        key = (m.args[2], {0: "DOWN", 1: "UP", 2: "MOVE"}[m.args[3]])
        observed = input_by_event[key]
        reasons = []
        if m.dur < 0:
            reasons.append("INCOMPLETE_INJECT")
        if inject_keys[m.args[:2]] != 1:
            reasons.append("DUPLICATE_INJECT_ID")
        if len(inject_by_event[key]) != 1 or len(observed) > 1:
            reasons.append("AMBIGUOUS_EVENT")
        if m.args[3] != 0 and not observed:
            reasons.append("INPUT_NOT_OBSERVED")
        injections.append({
            "marker_id": m.id, "run": m.args[0], "index": m.args[1],
            "action": key[1], "event_time_ms": key[0], "call_ns": m.dur if m.dur >= 0 else None,
            "status": "UNRESOLVED" if reasons else ("CONTROL" if m.args[3] == 0 else "LINKED"),
            "reasons": reasons, "input_marker_ids": [i.id for i in observed],
        })

    for m in kinds["input"]:
        matches = inject_by_event[m.args[1:]]
        reasons = []
        if len(input_by_id[m.args[0]]) != 1:
            reasons.append("DUPLICATE_INPUT_ID")
        if len(input_by_event[m.args[1:]]) != 1 or len(matches) > 1:
            reasons.append("AMBIGUOUS_EVENT")
        if not matches:
            reasons.append("INJECT_MISSING")
        if m.dur < 0:
            reasons.append("INCOMPLETE_INPUT")
        if len(matches) == 1:
            injection = matches[0]
            if injection.dur < 0:
                reasons.append("INCOMPLETE_INJECT")
            if inject_keys[injection.args[:2]] != 1:
                reasons.append("DUPLICATE_INJECT_ID")
            if injection.ts > m.ts:
                reasons.append("INPUT_BEFORE_INJECT")
            if not reasons:
                input_links[m.id] = injection
        requests = [r.args[0] for r in kinds["request"] if r.args[1] == m.args[0]]
        inputs.append({
            "marker_id": m.id, "input_id": m.args[0],
            "inject_marker_id": input_links[m.id].id if m.id in input_links else None,
            "generations": requests,
            "status": "UNRESOLVED" if reasons else ("LINKED" if requests else "NO_REQUEST"),
            "reasons": reasons,
        })

    per_generation = defaultdict(lambda: defaultdict(list))
    frame_generations = defaultdict(set)
    for kind in ("request", "adopt", "draw"):
        for m in kinds[kind]:
            per_generation[m.args[0]][kind].append(m)
            # chain欠落・不完全drawも混在世代として数える。
            if kind == "draw" and m.frame is not None:
                frame_generations[m.frame].add(m.args[0])

    generations = []
    for generation, parts in sorted(per_generation.items()):
        reasons = []
        request = parts["request"][0] if len(parts["request"]) == 1 else None
        adopt = parts["adopt"][0] if len(parts["adopt"]) == 1 else None
        if request is None:
            reasons.append("REQUEST_MISSING_OR_DUPLICATE")
        if adopt is None:
            reasons.append("ADOPT_MISSING_OR_DUPLICATE")
        observed = input_by_id[request.args[1]] if request else []
        observed_input = observed[0] if len(observed) == 1 else None
        if observed_input is None:
            reasons.append("INPUT_MISSING_OR_DUPLICATE")
        injection = input_links.get(observed_input.id) if observed_input else None
        if injection is None:
            reasons.append("INJECT_INPUT_NOT_UNIQUE")
        if any(m and m.dur < 0 for m in (request, adopt)):
            reasons.append("INCOMPLETE_CHAIN")

        draws = sorted(parts["draw"], key=lambda m: (m.ts, m.id))
        first = draws[0] if draws else None
        first_frame = []
        if first is None:
            reasons.append("DRAW_MISSING")
        elif first.parents != 1 or first.frame is None:
            reasons.append("FIRST_DRAW_FRAME_UNKNOWN")
        else:
            first_frame = [m for m in draws if m.frame == first.frame]
            if any(m.frame != first.frame for m in draws if m.ts == first.ts):
                reasons.append("FIRST_DRAW_AMBIGUOUS")
            if len(frame_generations[first.frame]) != 1:
                reasons.append("MIXED_GENERATIONS")
            if any(m.dur < 0 or m.parents != 1 for m in first_frame):
                reasons.append("INCOMPLETE_FIRST_DRAW")

        candidates = []
        for m in first_frame:
            options = frames[m.id]
            if len(options) != 1 or options[0]["status"] != "CANDIDATE_ONLY":
                reasons.append("FIRST_FRAME_UNRESOLVED")
                continue
            f = options[0]
            if (f["do_frame_id"] != first.frame or
                    any(f[k] is None or f[k] < 0 for k in FRAME_IDENTITY) or
                    f["surface_token"] == 0 or f["display_token"] == 0 or f["present_ts"] < m.end):
                reasons.append("INVALID_FRAME_CANDIDATE")
                continue
            candidates.append(f)
        identity = {tuple(f[k] for k in FRAME_IDENTITY) for f in candidates}
        if first_frame and len(identity) != 1:
            reasons.append("FIRST_FRAME_NOT_UNIQUE")
        present = candidates[0]["present_ts"] if candidates else None
        if all(m is not None for m in (injection, observed_input, request, adopt, first)):
            if not (injection.ts <= observed_input.ts and observed_input.end <= request.ts
                    and request.end <= adopt.ts and adopt.end <= first.ts):
                reasons.append("CHAIN_OUT_OF_ORDER")
        reasons = sorted(set(reasons))
        result = {
            "generation": generation, "phase": request.args[2] if request else None,
            "input_id": request.args[1] if request else None,
            "inject_marker_id": injection.id if injection else None,
            "first_draw_marker_id": first.id if first else None,
            "do_frame_id": first.frame if first else None,
            "draw_count": len(draws), "status": "UNRESOLVED" if reasons else "CORRELATED_CANDIDATE",
            "reasons": reasons,
        }
        if not reasons:
            result.update(present_ts=present, latency_ns=present - injection.ts,
                          input_latency_ns=present - observed_input.ts,
                          display_frame_id=candidates[0]["display_frame_id"])
        generations.append(result)

    return {
        "schema_version": 1, "status": "CANDIDATE_ONLY",
        "injections": injections, "inputs": inputs, "generations": generations,
        "counts": {
            "injections": len(injections), "inputs": len(inputs), "generations": len(generations),
            "correlated_candidates": sum(g["status"] == "CORRELATED_CANDIDATE" for g in generations),
            "unresolved_generations": sum(g["status"] == "UNRESOLVED" for g in generations),
        },
    }


def render_sql(filename, app_upid, injector_upid, layer):
    if app_upid < 0 or injector_upid < 0 or not layer or "\x00" in layer:
        raise ValueError("process/layerの指定が不正")
    sql = Path(__file__).with_name(filename).read_text(encoding="utf-8-sig")
    return (sql.replace("TARGET_APP_UPID", str(app_upid))
            .replace("TARGET_INJECTOR_UPID", str(injector_upid))
            .replace("'EXACT_IMAGE_WINDOW_LAYER'", "'" + layer.replace("'", "''") + "'"))


def trace_hash(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def analyze_trace(trace, processor, app_upid, injector_upid, layer):
    # 自動download・shell・端末操作はしない。入力は収集済みの同一ファイルだけ。
    def run(*args):
        return subprocess.run([str(processor), *args], check=True, capture_output=True,
                              text=True, encoding="utf-8").stdout

    version = run("--version").strip()
    if not re.search(r"(?<![\w.])v?57\.2(?![\w.])", version.splitlines()[0] if version else ""):
        raise ValueError("Trace Processor v57.2が必要")
    before = trace_hash(trace)
    exports = []
    for filename, columns in (("all_markers.sql", MARKER_COLUMNS), ("draw_frames.sql", FRAME_COLUMNS)):
        sql = render_sql(filename, app_upid, injector_upid, layer)
        exports.append(read_csv(run("query", str(trace), sql), columns))
    if trace_hash(trace) != before:
        raise ValueError("解析中にtraceファイルが変わった")
    result = correlate(*exports, app_upid, injector_upid)
    result["source"] = {
        "trace_sha256": before, "trace_processor_version": version,
        "app_upid": app_upid, "injector_upid": injector_upid, "layer": layer,
    }
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--trace-processor", type=Path, required=True)
    parser.add_argument("--app-upid", type=int, required=True)
    parser.add_argument("--injector-upid", type=int, required=True)
    parser.add_argument("--layer", required=True)
    args = parser.parse_args()
    try:
        result = analyze_trace(args.trace.expanduser().resolve(strict=True),
                               args.trace_processor.expanduser().resolve(strict=True),
                               args.app_upid, args.injector_upid, args.layer)
    except (ValueError, OSError, csv.Error, subprocess.SubprocessError) as error:
        print(f"相関候補を出力できません: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
