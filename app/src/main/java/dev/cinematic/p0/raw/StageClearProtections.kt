package dev.cinematic.p0.raw

import dev.cinematic.p0.gpu.constants.StageClearV1
import kotlin.math.*

/** PC stage-clear-v1 stages 6..8 only. Not a DNG decoder or a complete look renderer. */
internal object StageClearProtections {
    private val luma = StageClearV1.LUMA

    data class Result(
        val image: LinearSrgbImage,
        val faceCutFactor: Double,
        val decisions: StageClearDecisions? = null,
    )

    /**
     * Supply the pure stages 2..5 closure, retaining strength and the original base's skin weight.
     * On a face cut it is called again on scaled GLOWED input, as in stage_clear.render.
     * The closure must not include stage 6 (skin cap), which is applied here on both passes.
     */
    fun finish(
        glowed: LinearSrgbImage,
        faces: FaceMask,
        layerBeforeCap: (LinearSrgbImage) -> LinearSrgbImage,
    ): Result = finish(glowed, faces, null, layerBeforeCap)

    fun finish(
        glowed: LinearSrgbImage,
        faces: FaceMask,
        faceCutFactorOverride: Double?,
        layerBeforeCap: (LinearSrgbImage) -> LinearSrgbImage,
    ): Result {
        faces.requireAligned(glowed)
        require(faceCutFactorOverride == null ||
            faceCutFactorOverride.isFinite() && faceCutFactorOverride > 0.0 && faceCutFactorOverride <= 1.0)
        fun layer(input: LinearSrgbImage): LinearSrgbImage {
            val output = layerBeforeCap(input)
            require(output.width == glowed.width && output.height == glowed.height) {
                "Iconic layer must preserve image dimensions"
            }
            return skinChromaCap(output, faces)
        }
        val first = layer(glowed)
        val factor = faceCutFactorOverride ?: faceCut(first, faces)
        val layered = if (factor < 1.0) layer(glowed.scaled(factor)) else first
        return Result(shoulder(layered), factor)
    }

    fun skinChromaCap(image: LinearSrgbImage, faces: FaceMask): LinearSrgbImage {
        faces.requireAligned(image)
        val out = FloatArray(image.pixelCount * 3)
        val lab = FloatArray(3)
        repeat(image.pixelCount) { p ->
            StageClearColor.toLab(image, p * 3, lab)
            val lightness = lab[0].toDouble()
            val chroma = hypot(lab[1].toDouble(), lab[2].toDouble())
            val hue = Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble()))
            val wrappedHue = (hue + 360.0) % 360.0
            val skin = StageClearColor.skinWeight(lightness, chroma, wrappedHue)
            val red = hueBand(wrappedHue, 10.0, 30.0) * unit((chroma - .015) / .025) *
                unit(1 - (chroma - StageClearV1.SKIN_VIVID) / .06) * unit((lightness - .3) / .15)
            val weight = max(skin, red) * faces[p]
            val capped = if (chroma > StageClearV1.SKIN_KNEE) {
                StageClearV1.SKIN_KNEE + (chroma - StageClearV1.SKIN_KNEE) * StageClearV1.SKIN_SLOPE
            } else chroma
            val newChroma = chroma * (1 + weight * (capped / max(chroma, 1e-6) - 1))
            val newHue = if (hue < StageClearV1.RED_TARGET && hue > -40) {
                hue + StageClearV1.SKIN_HUE_LIFT * weight * (StageClearV1.RED_TARGET - hue)
            } else hue
            val radians = Math.toRadians(newHue)
            lab[1] = (newChroma * cos(radians)).toFloat()
            lab[2] = (newChroma * sin(radians)).toFloat()
            StageClearColor.toRgb(lab, out, p * 3)
        }
        return LinearSrgbImage.takeOwnership(image.width, image.height, out)
    }

    /** NumPy percentile(..., 90), method=linear, after encoding the float32 luminance. */
    fun faceCut(image: LinearSrgbImage, faces: FaceMask): Double {
        faces.requireAligned(image)
        var count = 0
        repeat(image.pixelCount) { if (faces[it] > .5f) count++ }
        if (count < 50) return 1.0
        val display = DoubleArray(count)
        var next = 0
        repeat(image.pixelCount) { p ->
            if (faces[p] > .5f) display[next++] = encode(luminance(image, p).toDouble()).toFloat().toDouble()
        }
        display.sort()
        val rank = (count - 1) * .9
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        val top = display[lo] + (display[hi] - display[lo]) * (rank - lo)
        if (top <= StageClearV1.FACE_LIMIT) return 1.0
        return max(decode(StageClearV1.FACE_LIMIT) / decode(top), 2.0.pow(-StageClearV1.MAX_CUT_EV))
    }

    /** Rolls encoded luminance, returns LINEAR RGB; negative/over-range channels are retained. */
    fun shoulder(image: LinearSrgbImage): LinearSrgbImage {
        val out = FloatArray(image.pixelCount * 3)
        repeat(image.pixelCount) { p ->
            val y = max(luminance(image, p).toDouble(), 1e-6)
            val u = encode(y)
            val start = StageClearV1.SHOULDER
            val span = 1 - start
            val rolled = if (u > start) start + span * tanh((u - start) / span) else u
            val factor = decode(rolled) / y
            repeat(3) { c -> out[p * 3 + c] = (image[p * 3 + c] * factor).toFloat() }
        }
        return LinearSrgbImage.takeOwnership(image.width, image.height, out)
    }

    internal fun encode(linear: Double): Double {
        val y = max(linear, 0.0)
        return if (y <= .0031308) 12.92 * y else 1.055 * y.pow(1 / 2.4) - .055
    }

    internal fun decode(display: Double): Double {
        val u = max(display, 0.0)
        return if (u <= .04045) u / 12.92 else ((u + .055) / 1.055).pow(2.4)
    }

    private fun luminance(image: LinearSrgbImage, pixel: Int): Float {
        val i = pixel * 3
        return image[i] * luma[0] + image[i + 1] * luma[1] + image[i + 2] * luma[2]
    }

    private fun unit(value: Double): Double = value.coerceIn(0.0, 1.0)

    private fun hueBand(hue: Double, centre: Double, half: Double): Double {
        val distance = (hue - centre + 540) % 360 - 180
        val t = unit(1 - abs(distance) / half)
        return t * t * (3 - 2 * t)
    }
}
