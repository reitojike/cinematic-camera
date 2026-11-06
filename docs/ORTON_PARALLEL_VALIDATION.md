# Orton row workers on main `57bc2cf` (local candidate, 2026-11-07)

Scope: independent worktree, synthetic inputs only, original24Probe APK on a dedicated API 37 x86_64 AVD with four configured/online vCPUs, 2 GiB AVD RAM and SwiftShader. No Pixel, private photos, publication, or real-photo quality judgment. The candidate ports the previous ordered 2/4-worker Orton row composition to current main, removes the abandoned row-array reuse option, and adds cooperative stop/interruption checks at 64-pixel intervals within parallel Orton rows. Source reading and output writing stay ordered, input row copies and per-worker field rows are private, and at most 2×worker-count rows are in flight. The production `Original24Attempt` now selects four workers; `OriginalStageClear24` itself keeps a one-worker default for reference tests.

The prior 24MP 1→2→4→4→2→1 AVD runs on main `36c5655` showed identical final JPEG SHA but inconsistent whole-save times; see the earlier `orton-bench/ORTON_WORKERS_REPEATED_AND_SAFETY.md`. Those runs predate the cancellation checks and are not timings for this candidate.

## Current-build evidence

- `:app:assembleOriginal24Probe :app:assembleOriginal24ProbeAndroidTest` passed on current main; `:app:assembleOriginal24ProbeAndroidTest` passed after adding the concurrent and 24MP cancellation tests.
- Small synthetic safety suite: `OK (8 tests)` on the dedicated AVD, with the opt-in full-24MP case skipped in this invocation. It covers cancellation at row start and mid-row, worker exception propagation and sibling termination, face-cut rerender exact raw-raster SHA, retries, private-workspace cleanup, original protection, a 24MP-*dimension* streaming partial-write cancellation, an actual 64×48 DNG attempt cancellation, and two simultaneous private-source sessions. An initial test attempted two concurrent readers of the *same* `OriginalRasterFile` and was rejected by its pre-existing single-reader ownership guard. Real attempts decode private rasters, so the corrected test uses two independent source files and workspaces; this ownership boundary must be preserved.
- One opt-in synthetic **24MP DNG** attempt was cancelled at Orton row 128: `OK (1 test)`, elapsed 253.090 s to cancellation. Its 9,216,000-byte partial Float32 raster stayed inside private scratch; all resource owners and row threads were closed by attempt return/close; input fingerprint was unchanged and JPEG output absent. The private failed scratch remains for the existing owner to resolve. This test does not exercise the publication coordinator or process death. Log: `orton-current-24mp-cancel.log`.
- Hardened same-APK 12MP A/B, order 4→1, one run each, identical synthetic input and final JPEG SHA-256 `80dcf181ccd8067c006145bca814093886bf50415843c695ffcc48b77188aecc`:

| Workers | Orton field wall | Orton row compose wall | Summed worker pixel CPU | Whole attempt | Sampled managed max | Sampled native max | Sampled PSS max |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 6.287 s | 10.326 s | 28.141 s | 195.194 s | 40.4 MiB | 212.2 MiB | 263.2 MiB |
| 1 | 6.447 s | 28.256 s | 23.950 s | 220.244 s | 39.8 MiB | 212.2 MiB | 256.7 MiB |

`ORTON_COMPOSE` includes field interpolation, source/output row file I/O, worker setup, synchronization and per-pixel math. `ORTON_PIXEL_THREAD_CPU` is summed CPU time *inside* the pixel math loop, not wall time; four workers spend more total CPU while reducing elapsed row time. `ORTON_FIELD` is a separate completed blur-field creation span. The 17.93 s row-composition reduction is the direct target effect in this pair. Whole attempt was 25.05 s shorter, but one ordered pair cannot establish a stable whole-export benefit because JIT/host drift affects unchanged stages. The sampled process memory maxima are not Orton-only peaks and cannot be extrapolated to Pixel. Raw reports: `orton-current-12mp-{4,1}.json` and `.log`.

## Remaining limits

No fixed number of rows or deadline to stop is guaranteed: ordered futures can delay detection of a later worker failure, and the executor has a 10-second termination wait. A 24MP full successful save after the new checks, Pixel time/memory, simultaneous 24MP saves, process death, disk-full/close failures, and publication-owner cleanup on failure are not yet tested. No approximate colour/blur change was made. Glow has a required diffusion-field boundary and shared row arrays; Character uses a shared face-row/provider and an earlier two-worker trial improved whole save only ~2.8%. Neither has been expanded here.
