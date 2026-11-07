package dev.cinematic.p0.raw

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Opt-in candidate only. Retains the after-Lab round trip, including its rounding. */
internal object OrtonZeroKeepCandidate {
    fun pixel(before: LinearSrgbImage, after: LinearSrgbImage, offset: Int, keep: Float,
        beforeLab: FloatArray, afterLab: FloatArray, out: FloatArray) {
        if (keep == 0f) {
            StageClearColor.toLab(after, offset, afterLab)
            StageClearColor.toRgb(afterLab, out, offset)
            return
        }
        StageClearColor.toLab(before, offset, beforeLab)
        StageClearColor.toLab(after, offset, afterLab)
        val scale = (1f - keep) + keep * min(1f, hypot(beforeLab[1], beforeLab[2]) /
            max(hypot(afterLab[1], afterLab[2]), StageClearOrtonV1.CHROMA_FLOOR.toFloat()))
        afterLab[1] *= scale
        afterLab[2] *= scale
        StageClearColor.toRgb(afterLab, out, offset)
    }
}
