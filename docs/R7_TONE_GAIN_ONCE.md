# R7 tone gain once per pixel

The original-size R7 tone row applied the same tone-field value to all three channels of each pixel but recomputed its `2.0.pow(delta)` gain once per channel. The candidate computes that gain once per pixel and multiplies each channel in the original order. It leaves the global tone field, P50/P92 statistics, row I/O, subsequent stages, and face-cut retry boundaries unchanged. It adds no persistent or full-frame buffer and does not change worker count.

A JVM test compares the previous per-channel expression with the new row function using raw Float bits across widths 1, 48, and 1024, negative and HDR RGB values, and two percentile pairs. A private same-APK API 37 AVD probe also saved the same synthetic 1024×768 (about 0.79MP) DNG at strength 0.5 in old/new/new/old order. All runs completed with identical input and final JPEG SHA-256, source-integrity verification, and same-file JPEG readback. The probe switched only this tone calculation. It used a four-core, 2 GiB AVD and reported thermal status 0; the timing observer and memory sampler were enabled in both modes.

| Run | Tone gain | Tone apply, ms | Whole save, ms | Sampled managed maximum, MiB | Sampled native maximum, MiB | Sampled PSS maximum, MiB |
|---|---|---:|---:|---:|---:|---:|
| A1 | old, 3 calculations/pixel | 547.227 | 17324 | 61.91 | 21.32 | 113.93 |
| B1 | new, 1 calculation/pixel | 343.971 | 17500 | 128.23 | 21.32 | 164.91 |
| B2 | new, 1 calculation/pixel | 376.543 | 18400 | 102.35 | 21.32 | 153.03 |
| A2 | old, 3 calculations/pixel | 518.858 | 16726 | 124.99 | 21.32 | 165.68 |

The two-run means are 533.043 ms old versus 360.257 ms new for tone apply, approximately 32% less time for that stage. Whole-save means are 17025 ms old versus 17950 ms new: the new-mode saves were longer in this four-run set. The remaining elapsed time was not decomposed sufficiently to assign a cause, and this small sample establishes neither a whole-save improvement nor a stable regression. Observed memory maxima also vary with run order and do not establish a reduction. These are AVD measurements and must not be projected to Pixel or 12/24MP. The comparison used a private diagnostic APK with a temporary old-mode switch; that switch and its probe arguments were removed from the final product patch. Final-head CI and review are required before merge, and Pixel/real-photo validation remains outstanding.
