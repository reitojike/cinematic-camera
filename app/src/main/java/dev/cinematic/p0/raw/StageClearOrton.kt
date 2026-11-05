package dev.cinematic.p0.raw

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Stage 3 only. Current PC path is a migration reference, not the final visual/performance contract. */
internal object StageClearOrton {
    private val luma = StageClearV1.LUMA

    /** Consume stage 2's paired amount: never recompute auto-strength from its output. */
    fun render(mist: StageClearMist.Result, skinWeight: FloatPlane): LinearSrgbImage {
        val amount = mist.lightAddingStrength
        require(amount.isFinite() && amount in 0.0..1.0) { "Light-adding strength must be within 0..1" }
        validateSkin(mist.image, skinWeight)
        if (amount == 0.0) return mist.image
        val orton = applyOrton(mist.image, skinWeight)
        val output = FloatArray(mist.image.pixelCount * 3) { i ->
            mist.image[i] + amount.toFloat() * (orton[i] - mist.image[i])
        }
        return LinearSrgbImage.takeOwnership(mist.image.width, mist.image.height, output)
    }

    internal fun orton(image: LinearSrgbImage, skinWeight: FloatPlane): LinearSrgbImage {
        validateSkin(image, skinWeight)
        return applyOrton(image, skinWeight)
    }

    private fun applyOrton(image: LinearSrgbImage, skinWeight: FloatPlane): LinearSrgbImage {
        // The encoded/bright/soft arrays are no longer needed by chroma protection.
        val candidate = mixOrton(image)
        val output = FloatArray(image.pixelCount * 3)
        val before = FloatArray(3)
        val after = FloatArray(3)
        repeat(image.pixelCount) { p ->
            StageClearColor.toLab(image, p * 3, before)
            StageClearColor.toLab(candidate, p * 3, after)
            val c0 = hypot(before[1], before[2])
            val c1 = hypot(after[1], after[2])
            val keep = skinWeight[p]
            val scale = (1f - keep) + keep * min(1f, c0 / max(c1, StageClearOrtonV1.CHROMA_FLOOR.toFloat()))
            after[1] *= scale
            after[2] *= scale
            StageClearColor.toRgb(after, output, p * 3)
        }
        return LinearSrgbImage.takeOwnership(image.width, image.height, output)
    }

    private fun mixOrton(image: LinearSrgbImage): LinearSrgbImage {
        val encoded = FloatArray(image.pixelCount * 3) { StageClearColor.linearToUnit(image[it]) }
        val bright = FloatArray(encoded.size) { i ->
            val inverse = 1f - encoded[i]
            1f - inverse * inverse
        }
        val soft = StageClearBlur.rgb(LinearSrgbImage.takeOwnership(image.width, image.height, bright), StageClearOrtonV1.RADIUS)
        val mixed = FloatArray(encoded.size)
        repeat(image.pixelCount) { p ->
            val i = p * 3
            val y = encoded[i] * luma[0] + encoded[i + 1] * luma[1] + encoded[i + 2] * luma[2]
            val fade = highlightFade(y)
            repeat(3) { c ->
                val j = i + c
                val display = encoded[j] + StageClearOrtonV1.AMOUNT.toFloat() * fade *
                    (bright[j] * soft[j].coerceIn(0f, 1f) - encoded[j])
                val value = StageClearColor.unitToLinear(display)
                // PC restores >1 per channel BEFORE OKLab skin protection, with no final clamp.
                mixed[j] = if (image[j] > 1f) max(image[j], value) else value
            }
        }
        return LinearSrgbImage.takeOwnership(image.width, image.height, mixed)
    }

    /** Unlike stage 2, this ramp compares encoded display luminance. */
    internal fun highlightFade(encodedLuminance: Float): Float = 1f -
        ((encodedLuminance - StageClearOrtonV1.HIGHLIGHT_START.toFloat()) /
            StageClearOrtonV1.HIGHLIGHT_SPAN.toFloat()).coerceIn(0f, 1f)

    private fun validateSkin(image: LinearSrgbImage, skinWeight: FloatPlane) {
        require(image.width == skinWeight.width && image.height == skinWeight.height) {
            "Original base's colour skin weight must align with RGB"
        }
        repeat(image.pixelCount) { require(skinWeight[it] in 0f..1f) { "Skin weight must be within 0..1" } }
    }
}
