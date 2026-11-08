# Plan
Read #78 optimization inventory and #205 actual-route inventory first. Normal MANUAL_TRIAL already retains reduced input, one R7 base keyed by adjustments and prepared fields. Do not add duplicate caching.
Use the existing dedicated original24Probe variant and synthetic DNG writer on shared ATD, selecting the new test explicitly. Compare every output pixel against a separately created cold session, close both outputs, then close sessions and verify source identity. Test methods run off Main on instrumentation threads; existing resource admission remains authoritative.
Existing small synthetic cache/source-change/close-failure tests passed 3/3 without skips. Those results are recorded in #207, not repeated as repository progress logs.
Remaining evidence gaps (full editor lifecycle and bundled internal timing) must remain visible in the Issue; this PR must not silently claim all original checkboxes are complete.
