package dev.cinematic.p0.raw

import kotlin.math.max
import kotlin.math.pow

/** Stage Clear v1 stage 1 only: R7 base -> bloom/halation -> diffusion. No exposure or I/O. */
internal object StageClearGlow {
    private val luma = StageClearV1.LUMA
    private val kernels = StageClearGlowV1.BLOOM_KERNELS
    private val tint = StageClearGlowV1.HALATION_TINT

    /** The same blurred colour-based skin protection retained for future stages 2..5. */
    fun skinWeight(base: LinearSrgbImage): FloatPlane = smoothSkin(StageClearColor.skinWeight(base))

    fun render(base: LinearSrgbImage, strength: Double = StageClearV1.DEFAULT_STRENGTH): LinearSrgbImage {
        require(strength.isFinite() && strength in 0.0..1.0) { "Strength must be within 0..1" }
        if (strength == 0.0) return base // stage_clear.render bypasses glow at zero.
        return renderNonzero(base, strength, null)
    }

    /** Same original base's immutable skin plane, shared only within one Look invocation.
     * Direct two-argument callers retain their existing calculation path and zero-strength bypass. */
    internal fun render(base: LinearSrgbImage, strength: Double, originalSkin: FloatPlane): LinearSrgbImage {
        require(strength.isFinite() && strength in 0.0..1.0) { "Strength must be within 0..1" }
        require(originalSkin.width == base.width && originalSkin.height == base.height) {
            "Original skin weight must align with base RGB"
        }
        repeat(base.pixelCount) { require(originalSkin[it] in 0f..1f) { "Skin weight must be within 0..1" } }
        if (strength == 0.0) return base
        return renderNonzero(base, strength, originalSkin)
    }

    private fun renderNonzero(base: LinearSrgbImage, strength: Double, originalSkin: FloatPlane?): LinearSrgbImage {
        // Source, excess, bloom, halation and skin temporaries belong to addLight's frame.
        // Keep only its pixels/protection while the full-size diffusion blur runs.
        val layer = addLight(base, strength, originalSkin)
        val values = layer.pixels
        val protect = layer.protection
        // Snapshot is intentional: diffusion writes to values after reading this image.
        val glowed = LinearSrgbImage(base.width, base.height, values)
        val diffusionAmount = (StageClearGlowV1.DIFFUSION * strength).toFloat()
        if (diffusionAmount == 0f) return glowed
        val soft = StageClearBlur.rgb(glowed, StageClearGlowV1.DIFFUSION_RADIUS)
        for (i in values.indices) {
            val blurred = soft[i].coerceIn(0f, 1f)
            val clipped = values[i].coerceIn(0f, 1f)
            values[i] += diffusionAmount * (blurred - clipped * blurred) * protect[i / 3]
        }
        return LinearSrgbImage.takeOwnership(base.width, base.height, values)
    }

    private data class LightLayer(val pixels: FloatArray, val protection: FloatArray)

    private fun addLight(base: LinearSrgbImage, strength: Double, originalSkin: FloatPlane?): LightLayer {
        val count = base.pixelCount
        val threshold = StageClearGlowV1.THRESHOLD.toFloat()
        val thresholdSpan = (1 - StageClearGlowV1.THRESHOLD).toFloat()
        val excess = FloatArray(count)
        val source = FloatArray(count * 3)
        repeat(count) { p ->
            val i = p * 3
            val y = max(0f, base[i] * luma[0] + base[i + 1] * luma[1] + base[i + 2] * luma[2])
            excess[p] = max(0f, (y - threshold) / thresholdSpan).pow(1.5f)
            val scale = excess[p] / max(y, 1e-6f)
            repeat(3) { c -> source[i + c] = base[i + c] * scale }
        }
        val sourceImage = LinearSrgbImage.takeOwnership(base.width, base.height, source)
        val bloom = FloatArray(source.size)
        for ((radius, weight) in kernels) {
            val blurred = StageClearBlur.rgb(sourceImage, radius)
            for (i in bloom.indices) bloom[i] += weight * blurred[i]
        }
        val halation = StageClearBlur.plane(FloatPlane.takeOwnership(base.width, base.height, excess),
            StageClearGlowV1.HALATION_RADIUS)
        val skin = originalSkin ?: skinWeight(base)
        val protect = FloatArray(count) { 1f - StageClearGlowV1.SKIN_PROTECT.toFloat() * skin[it] }
        val bloomAmount = (StageClearGlowV1.BLOOM * strength).toFloat()
        val halationAmount = (StageClearGlowV1.HALATION * strength).toFloat()
        val values = FloatArray(source.size)
        repeat(count) { p ->
            repeat(3) { c ->
                val i = p * 3 + c
                val warm = halation[p] * tint[c]
                values[i] = base[i] + (bloomAmount * bloom[i] + halationAmount * warm) * protect[p]
            }
        }
        return LightLayer(values, protect)
    }

    private fun smoothSkin(raw: FloatPlane): FloatPlane {
        val blurred = StageClearBlur.plane(raw, StageClearGlowV1.SKIN_RADIUS)
        return FloatPlane.takeOwnership(raw.width, raw.height, FloatArray(raw.width * raw.height) { blurred[it].coerceIn(0f, 1f) })
    }
}
