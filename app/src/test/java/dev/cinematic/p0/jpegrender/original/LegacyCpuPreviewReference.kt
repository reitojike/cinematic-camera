package dev.cinematic.p0.jpegrender.original

import dev.cinematic.p0.raw.*
import dev.cinematic.p0.raw.original.*
import kotlin.math.*

// Host-test reference only; never packaged or callable from the Android product.
/** Meter the unaltered AS_SHOT input so relative warmth is never compensated by automatic metering.
 * User EV is added to that fixed diagnostic EV after warmth, matching original-size rendering. */
internal data class Original24PreviewFoundation(val image: LinearSrgbImage, val externalEv: Double)

internal fun original24PreviewFoundation(input: LinearSrgbImage, adjustments: PhotoAdjustments,
    stop: () -> Boolean): Original24PreviewFoundation = original24PreviewFoundation(input, adjustments, stop, null)

internal fun original24PreviewFoundation(input: LinearSrgbImage, adjustments: PhotoAdjustments,
    stop: () -> Boolean, execution: PreviewPixelExecution?, timing: R7StageTiming? = null): Original24PreviewFoundation {
    val diagnostic = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
        R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList(), timing, stop)
    timing?.enter(R7StageTiming.Stage.WARMTH)
    val warmed = adjustments.applyWarmth(input, stop)
    val externalEv = diagnostic.exposure.externalBaseEv + adjustments.exposureEv
    return Original24PreviewFoundation(R7Base.render(warmed, diagnostic.foundationSkinRoi, externalEv, execution, timing, stop), externalEv)
}

/** Preview-only BOX reduction reuses the existing reducer, without changing any processing kernel. */
internal fun reduceDraftInput(input: LinearSrgbImage, stop: () -> Boolean): LinearSrgbImage {
    val scale = min(1.0, 256.0 / max(input.width, input.height))
    val width = floor(input.width * scale).toInt().coerceAtLeast(1)
    val height = floor(input.height * scale).toInt().coerceAtLeast(1)
    if (width == input.width && height == input.height) return input
    val rows = object : OriginalRaster {
        override val width = input.width
        override val height = input.height
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(input.width * 3)
            repeat(input.height) { y ->
                originalCheckpoint(stop)
                for (x in row.indices) row[x] = input[y * row.size + x]
                consume(y, row)
            }
        }
    }
    return OriginalFields.reduce(rows, width, height, 3, stop) { rgb, output -> rgb.copyInto(output) }.reducedRgb()
}
