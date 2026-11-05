package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import kotlin.math.pow

/** Versioned initial relative control, not a measured illuminant or Kelvin estimate.
 * Warmth is -100 (cool) to +100 (warm); zero preserves the decoded as-shot balance. */
internal data class PhotoAdjustments(val warmth: Double = 0.0, val exposureEv: Double = 0.0) {
    init {
        require(warmth.isFinite() && warmth in -100.0..100.0)
        require(exposureEv.isFinite() && exposureEv in -2.0..2.0)
    }
    val neutral get() = warmth == 0.0 && exposureEv == 0.0

    /** Relative red/blue gain, with neutral-grey luminance normalization. No clipping or gamma pass.
     * Initial comparison policy: endpoint gains differ by one stop before normalization.
     * It does not claim physical chromatic adaptation or Lightroom-equivalent white balance. */
    fun applyWarmth(input: LinearSrgbImage, shouldStop: () -> Boolean = { false }): LinearSrgbImage {
        fun checkpoint() { if (shouldStop()) throw CancellationException("Photo adjustment stopped") }
        checkpoint()
        if (warmth == 0.0) return input
        val red = 2.0.pow(warmth / 200.0)
        val blue = 2.0.pow(-warmth / 200.0)
        val norm = .2126 * red + .7152 + .0722 * blue
        val gains = doubleArrayOf(red / norm, 1.0 / norm, blue / norm)
        val output = FloatArray(input.pixelCount * 3)
        for (y in 0 until input.height) {
            checkpoint()
            for (x in 0 until input.width) {
                val offset = (y * input.width + x) * 3
                repeat(3) { c -> output[offset + c] = (input[offset + c] * gains[c]).toFloat() }
            }
        }
        checkpoint()
        return LinearSrgbImage.takeOwnership(input.width, input.height, output)
    }

    companion object {
        const val VERSION = "relative-rb-half-stop-r7-ev-v1"
        val NONE = PhotoAdjustments()
    }
}
