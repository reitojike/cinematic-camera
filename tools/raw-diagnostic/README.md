# RAW4 comparison (standard-library Python)

> #290: この独立診断variantは退役対象です。以下は過去版の再現・既存記録の解釈用に保持する手順で、現行ビルド入口ではありません。通常GPU性能試験とメモリ再実行手順は別に保持します。

Run locally against the two **private**, completed schema-2 TIMING reports:

```sh
python -B tools/raw-diagnostic/compare_raw4.py forward.json reverse.json --same-input-build-confirmed
python -B -m unittest discover -s tools/raw-diagnostic -v
```

The flag attests that the operator verified identical input bytes and build in both
sessions. Reports deliberately contain no source identity/hash/URI, so the tool
cannot establish this itself. Independently check comparable thermal, charging,
and cooldown conditions. Recorded Android thermal status >= MODERATE is rejected;
unknown status is not invented or interpreted as evidence of safe conditions.

The tool requires both exact AB-ABBAABBA / BA-BAABBAAB orders, ten successful runs
per report, two excluded warmups and four measured runs per arm, fixed recipe and
RAW4 profiles, source/JPEG equality and cleanup/resource gates. Every supplied
span must be complete and have a nonnegative integer nanosecond duration. Required
stages cannot be absent. Conditional face stages and repeated layer stages are
allowed; mixed/duplicate R7 color representations are rejected. Report residuals
must match DiagnosticTimings accounting (Orton child spans/CPU are not added twice).

Repeated spans are summed **within each attempt**, then the four attempt totals
produce medians, min/max and all measured values. Color compares the sum of
R7_VIBRANCE + R7_CRISP + R7_SKIN against R7_COLOR_FUSED. Exact rational arithmetic
uses the inclusive 10% threshold without floating-point boundary rounding:

- Glow/Mist: both orders meet 10% -> ADOPT_PERFORMANCE; both below ->
  DEFER_THIS_CONDITION; disagreement -> ADDITIONAL_ORDER_COMPARISON.
- Tone/color: both meet 10% -> ADDITIONAL_R7_COMPARISON, to separate tone-only
  and color-only effects; both below -> DEFER_THIS_CONDITION; disagreement ->
  ADDITIONAL_ORDER_COMPARISON. No R7 follow-up is triggered by a merely positive
  but subthreshold reduction in both orders, or by whole-render timing alone.
- Render/close and residual are context, not vetoes on useful stage improvements.

These labels are performance screening outcomes, not product-adoption claims.
No P95 or statistical-significance claim is made from four measurements. Invalid,
failed, unsafe or incomplete reports produce a rejection (exit 2), never a
performance defer. Valid output exits 0 and contains only fixed labels and numeric
summaries; paths, exception details and arbitrary report metadata are never echoed.
Keep raw reports private. Record the tested condition and result in the candidate
list, including deferred candidates; do not treat a condition-specific defer as a
permanent prohibition. This CLI reads only and never edits reports.
