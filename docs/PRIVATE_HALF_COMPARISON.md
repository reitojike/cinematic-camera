# Private FULL / half quality comparison (not adopted)

Issue #233. Additive to #455 synthetic-only experiments; not a product setting.

## Scope

Build with `-PcinematicHalfCompare=true`. Separate application ID
`dev.cinematic.p0.halfcompare`; release variants disabled. Normal builds keep their
existing manifest, ID, preview input mode and all save paths. Incompatible with
initial-guide experiments and stable product signing. No new native decoder.

The explicit local picker admits one DNG through a cancellable descriptor open.
Only regular-file descriptors are admitted; pipes are rejected before read.
A regular proxy descriptor can still fetch remotely and stall during read. Open
cancellation is connected, but bounded interruption of every provider read is NOT
verified. Until that is resolved, evaluate only a known device-local DNG, not cloud
providers. A stalled provider can keep the serial worker and private copy alive
until read returns; stale results are still rejected and the screen cleared.
Close failures retain ownership and private copies and block retries. FULL and HALF_CANDIDATE each use
Original24PreviewSession, the same immutable private snapshot, preview size cap,
GPU pipeline, strength 0.5 and selected exposure (0/-1/-2 EV). Both outputs must
have equal dimensions. Small or odd/aspect inputs whose capped FULL/half sizes
differ are rejected before decode; there is no compensating upsample. Switch the displayed result without changing zoom/pan.
The app never writes outputs or publishes files. Original is checked again before
results are shown. Background/close requests cancellation and clears displayed images. Stale generations
cannot publish. Failed cleanup blocks retries instead of erasing unknown files.

The manifest exposes only the launcher, disables backup and removes network
permissions. FLAG_SECURE blocks capture. No persistent URI grant is requested.
Private input/raster copies are removed on success/failure; process-death leftovers
are cleaned on the next admitted comparison using exact owned names. Real images,
URIs, identifiers and raw logs must never enter PRs, reports or external storage.
Do not use tools/preview-half/report.py with real photos: it copies synthetic PNGs.

## Not a speed benchmark

Readback and Bitmap display are for local quality inspection only. There is no
numeric display-speed claim here. #456 measures OPEN through the first successful
normal GPU swap; a subsequent speed A/B entry must use that exact presentation
boundary and same foreground/thermal/cache conditions. Never compare this quality
UI wall time with product first-frame time. Real-device installation/interaction,
distribution and candidate adoption remain separately approved steps.

## Synthetic verification only

The added tests under src/halfCompareAndroidTest retain explicit emulator and
`privateHalfSynthetic=true` guards. They cover immutable original, equal bounded
size, cancellation during GPU work, source changes, cleanup failures, unavailable
source, stale selection, lifecycle recreation and background resource release.
Compile first; use the coordinated shared AVD GPU lane, not a connected phone.
Run only the three `dev.cinematic.p0.halfcompare.PrivateComparison*` classes with
`cinematicHalfCompare=true` and `privateHalfSynthetic=true` instrumentation flag.

This branch starts at #455 head 8f712d8a. Existing #455 emulator guards and its
report script remain unchanged. #456 is neither merged nor duplicated here.
Before integrating, reconcile the build.gradle opt-in blocks and rerun tests on
the final combined head. This document is preparation, not completed validation.
