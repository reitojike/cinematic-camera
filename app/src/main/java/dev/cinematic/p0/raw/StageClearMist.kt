package dev.cinematic.p0.raw

import kotlin.math.max
import kotlin.math.pow

/** Stage Clear v1 stage 2 only. Caller retains the ORIGINAL base's blurred colour skin weight. */
internal object StageClearMist {
    private val luma = StageClearV1.LUMA
    private val kernels = StageClearMistV1.KERNELS

    /** Reuse lightAddingStrength for Orton; do not remeasure the stage-2 output. */
    data class Result(val image: LinearSrgbImage, val lightAddingStrength: Double)

    fun render(
        glowed: LinearSrgbImage,
        skinWeight: FloatPlane,
        strength: Double = StageClearV1.DEFAULT_STRENGTH,
    ): Result = render(glowed, skinWeight, strength, null)

    fun render(
        glowed: LinearSrgbImage,
        skinWeight: FloatPlane,
        strength: Double,
        autoStrengthOverride: Double?,
    ): Result {
        require(strength.isFinite() && strength in 0.0..1.0) { "Strength must be within 0..1" }
        validateSkin(glowed, skinWeight)
        require(autoStrengthOverride == null ||
            autoStrengthOverride.isFinite() && autoStrengthOverride in 0.0..1.0)
        val amount = (autoStrengthOverride ?: autoStrength(glowed)) * strength
        if (strength == 0.0) return Result(glowed, amount)
        val mist = applyMist(glowed, skinWeight)
        val mixed = FloatArray(glowed.pixelCount * 3) { i ->
            glowed[i] + amount.toFloat() * (mist[i] - glowed[i])
        }
        return Result(LinearSrgbImage.takeOwnership(glowed.width, glowed.height, mixed), amount)
    }

    internal fun blackMist(glowed: LinearSrgbImage, skinWeight: FloatPlane): LinearSrgbImage {
        validateSkin(glowed, skinWeight)
        return applyMist(glowed, skinWeight)
    }

    /** PC v1 compares LINEAR luminance, despite the informal "near-white" name. */
    internal fun nearWhiteFraction(glowed: LinearSrgbImage): Double {
        var count = 0
        repeat(glowed.pixelCount) { p ->
            if (luminance(glowed, p) > StageClearMistV1.NEAR_WHITE.toFloat()) count++
        }
        return count.toDouble() / glowed.pixelCount
    }

    internal fun autoStrength(glowed: LinearSrgbImage): Double {
        val ratio = ((nearWhiteFraction(glowed) - StageClearMistV1.AUTO_START) /
            StageClearMistV1.AUTO_SPAN).coerceIn(0.0, 1.0)
        return 1 - StageClearMistV1.AUTO_REDUCTION * ratio
    }

    private fun applyMist(glowed: LinearSrgbImage, skinWeight: FloatPlane): LinearSrgbImage {
        val source = FloatArray(glowed.pixelCount * 3)
        repeat(glowed.pixelCount) { p ->
            val y = max(luminance(glowed, p), 0f)
            val weight = ((y - StageClearMistV1.SOURCE_START.toFloat()) /
                StageClearMistV1.SOURCE_SPAN.toFloat()).coerceIn(0f, 1f)
                .pow(StageClearMistV1.SOURCE_POWER.toFloat())
            repeat(3) { c -> source[p * 3 + c] = glowed[p * 3 + c] * weight }
        }
        val sourceImage = LinearSrgbImage.takeOwnership(glowed.width, glowed.height, source)
        val halo = FloatArray(source.size)
        for ((radius, weight) in kernels) {
            val blurred = StageClearBlur.rgb(sourceImage, radius)
            for (i in halo.indices) halo[i] += weight * blurred[i]
        }
        val output = FloatArray(source.size) { i ->
            val protect = 1f - StageClearMistV1.SKIN_PROTECT.toFloat() * skinWeight[i / 3]
            glowed[i] + StageClearMistV1.MIST_AMOUNT.toFloat() * halo[i] * protect
        }
        return LinearSrgbImage.takeOwnership(glowed.width, glowed.height, output)
    }

    private fun validateSkin(glowed: LinearSrgbImage, skinWeight: FloatPlane) {
        require(glowed.width == skinWeight.width && glowed.height == skinWeight.height) {
            "Colour skin weight must align with glowed RGB"
        }
        skinWeight.requireUnitRange()
    }

    private fun luminance(image: LinearSrgbImage, p: Int): Float {
        val i = p * 3
        return image[i] * luma[0] + image[i + 1] * luma[1] + image[i + 2] * luma[2]
    }
}
