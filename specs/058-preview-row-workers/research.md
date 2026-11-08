# Research
Decision: share existing pixel formulas through optional internal execution context, rather than duplicate kernels in new files. Rationale: no drift and small review surface; saved row raster has different I/O and cannot directly serve preview. Alternatives: copying whole kernels increases complexity; reusing saved scratch raster adds file I/O; neither selected.
Decision: JDK bounded executor, private scratch and disjoint ranges. Reference: https://developer.android.com/reference/java/util/concurrent/ExecutorService . No new dependency.
Existing history: #170/#180/#183/#198 apply to original row processing, not array preview. PreparedGlow already retains strength-independent fields; no repeated investigation there.
