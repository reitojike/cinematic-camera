# Preview stage boundary timing

Status: Draft
Issue: #223

## US1: Identify the next preview bottleneck
When explicitly enabled, distinguish exact 1024px and draft 256px session work. Record admission, source verification, input loading, foundation (warmth/exposure/R7), Glow preparation, skin expansion, Glow, Mist decision/composition, Orton, Character, protections, Bitmap and cleanup. Only boundary timers change; pixel arithmetic, allocation ownership and cancellation boundaries remain unchanged.

## Acceptance criteria
- Disabled by default; no save-path instrumentation.
- Timing on/off preserves every synthetic output float bit and decision. Existing golden files stay unchanged.
- Exclusive intervals sum to total session time, including repeated face-cut passes.
- Failed/cancelled work and cleanup failures must not be reported as success. Optional log sink exceptions do not replace rendering results.
- Log only exact/draft kind, sequence, outcome and elapsed times; no photos, URI, edit values or personal identifiers.
- RawPreviewTiming measures session computation, not physical input-to-display latency. Existing PreviewTiming remains request/UI timing. Before-comparison Bitmap generation is outside this session interval.

## Out of scope / stop condition
No pixel loops, approximation, blur, worker defaults, save output, UI behavior or golden changes. No device or personal-photo testing. Stop if output or algorithm changes become necessary.
