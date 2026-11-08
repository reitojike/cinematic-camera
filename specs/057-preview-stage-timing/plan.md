# Plan: preview stage timing
Issue: #223

Use System.nanoTime at existing orchestration boundaries. RawPreviewTiming owns a monotonic clock, exclusive stage accumulation and injectable sink. The session creates it only when PreviewTiming DEBUG is enabled. Existing render overloads delegate to timing-aware overloads with null; existing callers and save paths remain unchanged. No pixel-loop timer is added.

Foundation includes warmth, R7 and exposure work; Glow preparation is recorded separately so strength-only reuse can be distinguished from adjustment rebuilds. Repeated protection passes accumulate under the same stage. Timer total excludes pre-session request work and comparison Bitmap construction and must not be described as end-to-end display latency.

Synthetic tests verify exact raw float bits, decisions, checkpoint counts, cancellation outcome, repeated-stage sums and sink failure isolation. Run lintDebug, testDebugUnitTest, assembleDebug and diff checks, then Draft PR, required CI, canonical and independent review. Real-device timing remains unmeasured. This instrumentation changes no product defaults or output.
