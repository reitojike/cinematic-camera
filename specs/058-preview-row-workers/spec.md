# Feature Specification: Exact preview worker candidate

**Feature Branch**: `perf/issue222-preview-workers`
**Created**: 2026-11-08
**Status**: Draft
**Input**: Issue #222; examine saved-path parallelism for 1024px preview without changing output.

## User Scenarios & Testing
### P1: Evaluate faster exact preview
The developer can compare the existing one-worker stages with a two-worker candidate using synthetic images only. Every output float must match bit-for-bit. Ordinary previews and saved images retain their current behavior.
### P1: Stop safely
Cancellation or a worker failure terminates all workers before returning. A later attempt can succeed without stale workers or partial published output.
### Edge cases
Odd dimensions, one-row images, zero/full strength, dark gradients, HDR values, nonzero skin weights, cancellation and worker failures.

## Requirements
- FR1: Establish whether saved Foundation, Orton and Character workers reach 1024px preview.
- FR2: Provide only an explicitly requested 1/2-worker candidate; preserve default calls and arithmetic order.
- FR3: Retain whole-image blur/global decisions; split only independent pixel ranges with private scratch.
- FR4: Bound worker lifetime and memory; discard failed results and join all tasks.
- FR5: Synthetic raw-bit agreement, no golden edits, real device or personal images.
- FR6: No saved-path change, product connection, UI change or adoption claim.

## Success Criteria
SC1: All synthetic outputs equal the unchanged baseline at every channel bit.
SC2: Cancellation/failure tests leave no live candidate workers or returned partial image.
SC3: Repeatable 1024px reference measurements and device limitations are recorded in Issue #222.

## Assumptions and Limits
Host timings are selection evidence only. Device adoption remains separate. The timing worker owns PreviewSession, PreparedGlow and StageClearLook. Stop on output mismatch or substantial complexity growth. One PR, at most two.
