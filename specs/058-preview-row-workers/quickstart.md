# Validation
Run PreviewParallelStagesTest with testDebugUnitTest. Expected: exact channel bits, safe cancellation/failure and no leaked workers. Run lintDebug, testDebugUnitTest, assembleDebug and git diff --check before PR. Host benchmark is opt-in and reports reference-only medians, not device adoption. Product calls must still omit execution context.
