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
        val sourceImage = LinearSrgbImage(base.width, base.height, source)
        val bloom = FloatArray(source.size)
        for ((radius, weight) in kernels) {
            val blurred = StageClearBlur.rgb(sourceImage, radius)
            for (i in bloom.indices) bloom[i] += weight * blurred[i]
        }
        val halation = StageClearBlur.plane(FloatPlane(base.width, base.height, excess),
            StageClearGlowV1.HALATION_RADIUS)
        val skin = skinWeight(base)
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
        val glowed = LinearSrgbImage(base.width, base.height, values)
        val diffusionAmount = (StageClearGlowV1.DIFFUSION * strength).toFloat()
        if (diffusionAmount == 0f) return glowed
        val soft = StageClearBlur.rgb(glowed, StageClearGlowV1.DIFFUSION_RADIUS)
        for (i in values.indices) {
            val blurred = soft[i].coerceIn(0f, 1f)
            val clipped = values[i].coerceIn(0f, 1f)
            values[i] += diffusionAmount * (blurred - clipped * blurred) * protect[i / 3]
        }
        return LinearSrgbImage(base.width, base.height, values)
    }

    private fun smoothSkin(raw: FloatPlane): FloatPlane {
        val blurred = StageClearBlur.plane(raw, StageClearGlowV1.SKIN_RADIUS)
        return FloatPlane(raw.width, raw.height, FloatArray(raw.width * raw.height) { blurred[it].coerceIn(0f, 1f) })
    }
}
