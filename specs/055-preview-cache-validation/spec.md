# Existing preview cache validation
Status: Draft
Issue: #207. Parent: #204. Inventory: #205 / #216.

## Scope
No new cache or rendering algorithm. Add a synthetic Android regression comparing the existing retained Original24PreviewSession with a fresh session for the same strength, temperature and exposure values. Only a new photoroll test file and this specification change.

## Acceptance and limits
- ARGB pixels must match exactly for synthetic input across strength, temperature and exposure changes.
- Existing source-read and field-build assertions show reuse; these are not measured decode/R7 time slices.
- Source fingerprint remains unchanged; all returned Bitmaps and sessions close; retained fields become zero after close.
- Maximum retained float payload at 1024-square is 24 MiB for AS_SHOT + R7 base, plus at most 435692 bytes of Glow fields (about 24.416 MiB combined). This is not total peak heap/PSS and excludes Bitmap cache, display copies, native memory and temporary buffers.
- Existing factory invalidation covers source replacement, editor/activity disposal and memory pressure. Full PhotoRollScreen lifecycle integration and dynamic future look-version changes are not established by this session test.

## Out of scope and stopping
Do not edit pipeline internals, source DNG, production defaults or golden tests. Stop on any output difference and report the maximum difference. Internal decode/R7 tracing is outside caller-only scope and remains explicitly unmeasured. No physical device or personal photos.
