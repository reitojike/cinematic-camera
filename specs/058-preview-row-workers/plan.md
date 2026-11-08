# Implementation Plan
Issue #222 / spec 058. Kotlin/JVM, existing Android minSdk33; no dependencies.
Use java.util.concurrent bounded executor as an explicitly constructed, scoped execution context. Each invocation partitions independent pixels into at most two ranges. Worker-local scratch avoids shared mutation; output ranges do not overlap. Existing entry calls pass no execution context and retain serial behavior. Orton's global blur is unchanged. Character's full-image coordinates remain intact. Foundation's formula is unchanged. No saved implementation changes.

Constitution check: standard concurrency APIs, synthetic data only, unchanged golden/product/default/save, no personal material. Spec/Issue first. No timing-worker file overlap. Stop on mismatch.

Memory: no additional full-frame result arrays versus existing algorithms; two worker scratch buffers instead of one, at most two futures, plus two JVM thread stacks. Executor is closed after the candidate operation. Whole-image intermediates remain, so this is not a 24MP memory solution.

Validation: raw-bit baseline equivalence; dimensions/strength/skin/HDR boundaries; worker cancellation/failure/join/retry; host1024 repeated measurements; full required Gradle checks; independent and canonical review. Device speed/adoption explicitly unverified.
