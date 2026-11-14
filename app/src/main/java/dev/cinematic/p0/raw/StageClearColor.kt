package dev.cinematic.p0.raw

import dev.cinematic.p0.gpu.constants.StageClearV1
import kotlin.math.*

/** Shared PC float32 OKLab boundary. Signed cube roots retain out-of-gamut RGB. */
internal object StageClearColor {
    private val rgbToLms = StageClearV1.RGB_TO_LMS
    private val lmsToLab = StageClearV1.LMS_TO_LAB
    private val labToLms = StageClearV1.LAB_TO_LMS
    private val lmsToRgb = StageClearV1.LMS_TO_RGB

    /** Orton's display-domain transfer deliberately clips; shoulder's unbounded transfer is separate. */
    fun linearToUnit(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= .0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - .055f
    }

    fun unitToLinear(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= .04045f) v / 12.92f else ((v + .055f) / 1.055f).pow(2.4f)
    }

    fun toLab(image: LinearSrgbImage, i: Int, out: FloatArray) {
        val red = image[i]
        val green = image[i + 1]
        val blue = image[i + 2]
        val l = Math.cbrt(dot(rgbToLms, 0, red, green, blue).toDouble()).toFloat()
        val m = Math.cbrt(dot(rgbToLms, 3, red, green, blue).toDouble()).toFloat()
        val s = Math.cbrt(dot(rgbToLms, 6, red, green, blue).toDouble()).toFloat()
        repeat(3) { out[it] = dot(lmsToLab, it * 3, l, m, s) }
    }

    fun toRgb(lab: FloatArray, out: FloatArray, i: Int) {
        val l = dot(labToLms, 0, lab[0], lab[1], lab[2]).let { it * it * it }
        val m = dot(labToLms, 3, lab[0], lab[1], lab[2]).let { it * it * it }
        val s = dot(labToLms, 6, lab[0], lab[1], lab[2]).let { it * it * it }
        repeat(3) { out[i + it] = dot(lmsToRgb, it * 3, l, m, s) }
    }

    /** Colour-based protection, not a detected-face mask. Mirrors r7b_skin.skin_weight. */
    fun skinWeight(image: LinearSrgbImage): FloatPlane {
        val weights = FloatArray(image.pixelCount)
        val lab = FloatArray(3)
        repeat(image.pixelCount) { p ->
            toLab(image, p * 3, lab)
            val chroma = hypot(lab[1].toDouble(), lab[2].toDouble())
            val hue = (Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble())) + 360) % 360
            weights[p] = skinWeight(lab[0].toDouble(), chroma, hue).toFloat()
        }
        return FloatPlane.takeOwnership(image.width, image.height, weights)
    }

    fun skinWeight(lightness: Double, chroma: Double, hue: Double): Double {
        val distance = (hue - 40 + 540) % 360 - 180
        val t = (1 - abs(distance) / 45).coerceIn(0.0, 1.0)
        val band = t * t * (3 - 2 * t)
        return band * unit((chroma - .015) / .025) * unit(1 - (chroma - .16) / .06) *
            unit((lightness - .25) / .15)
    }

    private fun unit(value: Double): Double = value.coerceIn(0.0, 1.0)

    private fun dot(matrix: FloatArray, row: Int, a: Float, b: Float, c: Float): Float =
        matrix[row] * a + matrix[row + 1] * b + matrix[row + 2] * c
}
