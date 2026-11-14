package dev.cinematic.p0.raw

import dev.cinematic.p0.gpu.constants.StageClearCharacterV1
import kotlin.math.*

/** Stages 4/5: current PC vignette and guarded vibrance, without auto-strength. */
internal object StageClearCharacter {
    fun render(image: LinearSrgbImage, strength: Double, execution: PreviewPixelExecution? = null): LinearSrgbImage {
        execution?.checkpoint()
        require(strength.isFinite() && strength in 0.0..1.0) { "Strength must be within 0..1" }
        return vibrance(vignette(image, StageClearCharacterV1.VIGNETTE * strength, execution),
            StageClearCharacterV1.VIBRANCE * strength, execution).also { execution?.checkpoint() }
    }

    internal fun vignette(image: LinearSrgbImage, amount: Double, execution: PreviewPixelExecution? = null): LinearSrgbImage {
        require(amount.isFinite() && amount in 0.0..1.0)
        val aspect = image.width.toDouble() / image.height
        val x = FloatArray(image.width) { ((it.toFloat() + .5f) / image.width * 2f - 1f) * min(aspect, 1.0).toFloat() }
        val y = FloatArray(image.height) { ((it.toFloat() + .5f) / image.height * 2f - 1f) / max(aspect, 1.0).toFloat() }
        val output = FloatArray(image.pixelCount * 3)
        previewPixelRanges(image.height, execution) { start, end ->
            for (row in start until end) { repeat(image.width) { col ->
                previewPixelCheckpoint(row * image.width + col, execution)
                val radius = sqrt(x[col] * x[col] + y[row] * y[row])
                val t = ((radius - .45f) / .9f).coerceIn(0f, 1f)
                val weight = 1f - amount.toFloat() * t * t * (3f - 2f * t)
                val i = (row * image.width + col) * 3
                repeat(3) { output[i + it] = image[i + it] * weight }
            } }
        }
        return LinearSrgbImage.takeOwnership(image.width, image.height, output)
    }

    internal fun vibrance(image: LinearSrgbImage, amount: Double, execution: PreviewPixelExecution? = null): LinearSrgbImage {
        require(amount.isFinite() && amount in 0.0..1.0)
        val output = FloatArray(image.pixelCount * 3)
        previewPixelRanges(image.pixelCount, execution) { start, end ->
            val lab = FloatArray(3)
            for (p in start until end) {
                previewPixelCheckpoint(p, execution)
                StageClearColor.toLab(image, p * 3, lab)
                val lightness = lab[0]
                val chroma = hypot(lab[1], lab[2])
                val hue = ((atan2(lab[2], lab[1]) * (180f / Math.PI.toFloat())) % 360f + 360f) % 360f
                val distance = abs((hue - 50f + 180f) % 360f - 180f)
                val t = (1f - distance / 40f).coerceIn(0f, 1f)
                val band = t * t * (3f - 2f * t)
                val skin = band * unit((chroma - .015f) / .03f) * unit(1f - (chroma - .13f) / .05f)
                val neutralGuard = unit((chroma - .012f) / .03f) * unit(1f - (lightness - .72f) / .2f)
                val gain = 1f + amount.toFloat() * neutralGuard * unit(1f - chroma / .22f) *
                    (1f + .6f * unit(1f - lightness / .6f)) * (1f - .85f * skin)
                lab[1] *= gain
                lab[2] *= gain
                StageClearColor.toRgb(lab, output, p * 3)
            }
        }
        return LinearSrgbImage.takeOwnership(image.width, image.height, output)
    }

    private fun unit(value: Float) = value.coerceIn(0f, 1f)
}
