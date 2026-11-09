package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import kotlin.math.*

/** Boundary B only. Input must already have the intended profile/WB; W1 is not applied here. */
internal object R7Base {
    /** ROI is the foundation's skin region, not the later detected-face protection mask. */
    fun render(
        input: LinearSrgbImage,
        foundationSkinRoi: FloatPlane,
        externalBaseEv: Double,
        shouldStop: () -> Boolean = { false },
    ): LinearSrgbImage = render(input, foundationSkinRoi, externalBaseEv, null, shouldStop)

    internal fun render(input: LinearSrgbImage, foundationSkinRoi: FloatPlane, externalBaseEv: Double,
        execution: PreviewPixelExecution?, shouldStop: () -> Boolean): LinearSrgbImage =
        render(input, foundationSkinRoi, externalBaseEv, execution, null, shouldStop)

    internal fun render(input: LinearSrgbImage, foundationSkinRoi: FloatPlane, externalBaseEv: Double,
        execution: PreviewPixelExecution?, timing: R7StageTiming?, shouldStop: () -> Boolean): LinearSrgbImage {
        fun checkpoint() {
            if (shouldStop()) throw CancellationException("R7 rendering stopped")
        }
        // Stage boundaries only; a running colour/blur kernel is not interrupted.
        checkpoint()
        timing?.enter(R7StageTiming.Stage.VALIDATE)
        validate(input, foundationSkinRoi, externalBaseEv)
        // Production does not retain every full-frame intermediate as the comparison test does.
        timing?.enter(R7StageTiming.Stage.PROFILE)
        var image = profile(input)
        checkpoint()
        image = localTone(image, timing)
        checkpoint()
        timing?.enter(R7StageTiming.Stage.FOUNDATION)
        image = foundation(image, foundationSkinRoi, externalBaseEv, execution)
        checkpoint()
        timing?.enter(R7StageTiming.Stage.VIBRANCE)
        image = vibrance(image)
        checkpoint()
        timing?.enter(R7StageTiming.Stage.CRISP)
        image = crisp(image)
        checkpoint()
        timing?.enter(R7StageTiming.Stage.SKIN)
        return skin(image).also { checkpoint() }
    }

    internal fun stages(input: LinearSrgbImage, roi: FloatPlane, ev: Double): List<LinearSrgbImage> {
        validate(input, roi, ev)
        val profile = profile(input)
        val tone = localTone(profile)
        val foundation = foundation(tone, roi, ev)
        val vibrance = vibrance(foundation)
        val crisp = crisp(vibrance)
        return listOf(profile, tone, foundation, vibrance, crisp, skin(crisp))
    }

    private fun validate(input: LinearSrgbImage, roi: FloatPlane, ev: Double) {
        require(ev.isFinite()) { "External base EV must be finite" }
        require(input.width == roi.width && input.height == roi.height) { "Foundation ROI must align with RGB" }
        repeat(input.pixelCount) { require(roi[it] in 0f..1f) { "Foundation ROI must be within 0..1" } }
    }

    internal fun profile(input: LinearSrgbImage): LinearSrgbImage = labLayer(input) { lab ->
            val c = hypot(lab[1].toDouble(), lab[2].toDouble())
            val h = hue(lab)
            val angle = Math.toRadians(h - R7BaseV1.PROFILE_SHIFT * band(h, 300.0, 50.0) * unit((c - .03) / .07))
            lab[1] = (c * cos(angle)).toFloat(); lab[2] = (c * sin(angle)).toFloat()
    }

    internal fun skin(input: LinearSrgbImage): LinearSrgbImage = labLayer(input) { lab ->
            val c = hypot(lab[1].toDouble(), lab[2].toDouble())
            val h = hue(lab)
            val weight = StageClearColor.skinWeight(lab[0].toDouble(), c, h)
            val delta = (18 - h + 540) % 360 - 180
            val angle = Math.toRadians(h + .35 * weight * delta)
            val chroma = c * (1 - .35 * weight)
            lab[0] = (lab[0] + .12 * weight * (1 - lab[0])).toFloat()
            lab[1] = (chroma * cos(angle)).toFloat(); lab[2] = (chroma * sin(angle)).toFloat()
    }

    internal fun smoothLogLuma(input: LinearSrgbImage, timing: R7StageTiming? = null): FloatPlane {
        timing?.enter(R7StageTiming.Stage.LOCAL_LOG_LUMA)
        val cell = .04 * max(input.width, input.height)
        val width = max(4, round(input.width / cell).toInt())
        val height = max(4, round(input.height / cell).toInt())
        val log = FloatPlane.takeOwnership(input.width, input.height, FloatArray(input.pixelCount) { p ->
            log2(max(luma(input, p).toDouble(), 1e-5)).toFloat()
        })
        timing?.enter(R7StageTiming.Stage.LOCAL_BOX)
        val down = StageClearBlur.resize(log, width, height, StageClearBlur.Filter.BOX)
        timing?.enter(R7StageTiming.Stage.LOCAL_BICUBIC)
        return StageClearBlur.resize(down, input.width, input.height, StageClearBlur.Filter.BICUBIC)
    }

    private fun localTone(input: LinearSrgbImage, timing: R7StageTiming? = null): LinearSrgbImage {
        val base = smoothLogLuma(input, timing)
        timing?.enter(R7StageTiming.Stage.LOCAL_QUANTILES)
        val sorted = base.copyValues().also { it.sort() }
        val high = percentile(sorted, .92)
        val low = percentile(sorted, .5)
        timing?.enter(R7StageTiming.Stage.LOCAL_APPLY)
        return pixels(input) { p, out ->
            val value = base[p].toDouble()
            val delta = -R7BaseV1.HIGHLIGHTS * max(value - high, 0.0) - R7BaseV1.SHADOWS * min(value - low, 0.0)
            val gain = 2.0.pow(delta)
            repeat(3) { c -> out[p * 3 + c] = (input[p * 3 + c] * gain).toFloat() }
        }
    }

    internal fun foundation(input: LinearSrgbImage, roi: FloatPlane, ev: Double, execution: PreviewPixelExecution? = null): LinearSrgbImage {
        execution?.checkpoint()
        val gain = 2.0.pow(ev + R7BaseV1.BRIGHTNESS_EV + R7BaseV1.FOUNDATION_EV)
        require(gain.isFinite() && gain > 0) { "Exposure gain must be finite and positive" }
        val tint = doubleArrayOf(R7BaseV1.YELLOW_RED, R7BaseV1.YELLOW_GREEN, R7BaseV1.YELLOW_BLUE)
        val out = FloatArray(input.pixelCount * 3)
        previewPixelRanges(input.pixelCount, execution) { start, end ->
            val values = DoubleArray(3)
            for (p in start until end) {
                previewPixelCheckpoint(p, execution)
                val y = luma(input, p).toDouble()
                val local = roi[p] * unit(y / .025)
                val exposed = y * gain / (1 + (gain - 1) * y)
                val power = 1 + (R7BaseV1.CONTRAST - 1) * (1 - .75 * local)
                val raised = unit(exposed).pow(power)
                val lower = unit(1 - exposed).pow(power) * (.18 / .82).pow(power - 1)
                val target = raised / max(raised + lower, 1e-8)
                // r7_final forces the common global RGB tint to unity.
                repeat(3) { values[it] = input[p * 3 + it] * target / max(y, 1e-8) }
                // Preserve the PC's luma renormalization even with the unity tint.
                val norm = target / max(dot(values), 1e-8)
                repeat(3) { values[it] *= norm }
                val yellow = unit(3 * (min(values[0], values[1]) - values[2]) / max(target, 1e-8))
                repeat(3) { values[it] *= 1 + (tint[it] - 1) * local * yellow }
                val yellowNorm = target / max(dot(values), 1e-8)
                val saturation = R7BaseV1.SATURATION * (1 - local) + R7BaseV1.SKIN_SATURATION * local
                repeat(3) { out[p * 3 + it] = (target + (values[it] * yellowNorm - target) * saturation).toFloat() }
            }
        }
        return LinearSrgbImage.takeOwnership(input.width, input.height, out).also { execution?.checkpoint() }
    }

    internal fun vibrance(input: LinearSrgbImage): LinearSrgbImage = labLayer(input) { lab ->
        val l = lab[0].toDouble(); val c = hypot(lab[1].toDouble(), lab[2].toDouble()); val h = hue(lab)
        val skin = band(h, 50.0, 40.0) * unit((c - .015) / .03) * unit(1 - (c - .13) / .05)
        val neutral = unit((c - .012) / .03) * unit(1 - (l - .72) / .2)
        val gain = 1 + R7BaseV1.VIBRANCE * neutral * unit(1 - c / .22) * (1 + .6 * unit(1 - l / .6)) * (1 - .85 * skin)
        lab[1] = (lab[1] * gain).toFloat(); lab[2] = (lab[2] * gain).toFloat()
    }

    internal fun crisp(input: LinearSrgbImage): LinearSrgbImage {
        val tone = pixels(input) { p, out ->
            val y = max(luma(input, p).toDouble(), 1e-8)
            var target = y * y / (y + R7BaseV1.BLACK)
            val t = unit(target); val power = R7BaseV1.CRISP_CONTRAST
            val raised = t.pow(power)
            val lower = (1 - t).pow(power) * (.10 / .90).pow(power - 1)
            if (target < 1) target = raised / max(raised + lower, 1e-8)
            repeat(3) { out[p * 3 + it] = (input[p * 3 + it] * target / y).toFloat() }
        }
        return labLayer(tone) { lab ->
            val c = hypot(lab[1].toDouble(), lab[2].toDouble())
            val skin = StageClearColor.skinWeight(lab[0].toDouble(), c, hue(lab))
            val gain = 1 + R7BaseV1.CRISP_CHROMA * (1 - skin)
            lab[1] = (lab[1] * gain).toFloat(); lab[2] = (lab[2] * gain).toFloat()
        }
    }

    private inline fun labLayer(input: LinearSrgbImage, transform: (FloatArray) -> Unit): LinearSrgbImage {
        val out = FloatArray(input.pixelCount * 3); val lab = FloatArray(3)
        repeat(input.pixelCount) { p ->
            StageClearColor.toLab(input, p * 3, lab); transform(lab); StageClearColor.toRgb(lab, out, p * 3)
        }
        return LinearSrgbImage.takeOwnership(input.width, input.height, out)
    }

    private inline fun pixels(input: LinearSrgbImage, transform: (Int, FloatArray) -> Unit): LinearSrgbImage {
        val out = FloatArray(input.pixelCount * 3)
        repeat(input.pixelCount) { transform(it, out) }
        return LinearSrgbImage.takeOwnership(input.width, input.height, out)
    }

    private fun luma(input: LinearSrgbImage, p: Int): Float =
        input[p * 3] * .2126f + input[p * 3 + 1] * .7152f + input[p * 3 + 2] * .0722f
    private fun dot(v: DoubleArray) = v[0] * .2126 + v[1] * .7152 + v[2] * .0722
    private fun hue(lab: FloatArray) = (Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble())) + 360) % 360
    private fun unit(x: Double) = x.coerceIn(0.0, 1.0)
    private fun band(h: Double, centre: Double, half: Double): Double {
        val t = unit(1 - abs((h - centre + 540) % 360 - 180) / half)
        return t * t * (3 - 2 * t)
    }
    private fun percentile(sorted: FloatArray, q: Double): Double {
        val rank = (sorted.size - 1) * q; val lo = floor(rank).toInt(); val hi = ceil(rank).toInt()
        return sorted[lo].toDouble() + (sorted[hi].toDouble() - sorted[lo]) * (rank - lo)
    }
}
