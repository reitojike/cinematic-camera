package dev.cinematic.p0.gpu.reference

import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.LinearSrgbImage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** RGB のみ (alpha は対象外)。差が同点なら走査順で最初の場所を報告する。 */
internal object ImageDiff {
    data class Location(val x: Int, val y: Int, val channel: Int, val reference: Double, val actual: Double)
    data class Display(
        val stage: String,
        val maxDiff: Int,
        val maxDiffByChannel: List<Int>,
        val p999: Double,
        val meanDiff: Double,
        val changedPixels: Int,
        val changedFraction: Double,
        val worst: Location,
    ) {
        fun assertDisplayWithin(
            maxDiff: Int = GpuTolerances.DISPLAY_MAX_DIFF,
            p999: Double = GpuTolerances.DISPLAY_P999_DIFF,
        ) {
            require(maxDiff >= 0 && p999.isFinite() && p999 >= 0)
            if (this.maxDiff > maxDiff || this.p999 > p999) throw AssertionError(
                "$stage: max=${this.maxDiff} (limit=$maxDiff), p99.9=${this.p999} (limit=$p999), " +
                    "worst=$worst, mean=$meanDiff, changed=$changedPixels ($changedFraction)",
            )
        }
    }
    data class Linear(val stage: String, val maxAbsolute: Double, val maxRelative: Double,
        val worstAbsolute: Location, val worstRelative: Location)

    fun display(reference: LinearSrgbImage, actual: LinearSrgbImage, stage: String): Display {
        requireAligned(reference.width, reference.height, actual.width, actual.height)
        return display(encodeDisplay(reference), encodeDisplay(actual), reference.width, reference.height, stage)
    }

    /** TrialDisplayPixels と同じ float→Double / sRGB / roundToInt の式。大判比較にも使える。 */
    fun encodeDisplay(image: LinearSrgbImage): IntArray = IntArray(image.pixelCount) { p ->
        fun channel(c: Int): Int {
            val linear = image[p * 3 + c].toDouble().coerceIn(0.0, 1.0)
            val encoded = if (linear <= .0031308) 12.92 * linear else 1.055 * linear.pow(1.0 / 2.4) - .055
            return (encoded * 255).roundToInt().coerceIn(0, 255)
        }
        (0xff shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    fun display(reference: IntArray, actual: IntArray, width: Int, height: Int, stage: String): Display {
        require(width > 0 && height > 0 && width.toLong() * height == reference.size.toLong())
        require(actual.size == reference.size)
        val histogram = LongArray(256)
        val channels = IntArray(3)
        var sum = 0L
        var changed = 0
        var maximum = -1
        var worst = Location(0, 0, 0, 0.0, 0.0)
        reference.indices.forEach { p ->
            var differs = false
            repeat(3) { c ->
                val shift = 16 - c * 8
                val ref = (reference[p] ushr shift) and 255
                val value = (actual[p] ushr shift) and 255
                val delta = abs(ref - value)
                histogram[delta]++
                sum += delta
                channels[c] = max(channels[c], delta)
                if (delta > maximum) { maximum = delta; worst = Location(p % width, p / width, c, ref.toDouble(), value.toDouble()) }
                if (delta != 0) differs = true
            }
            if (differs) changed++
        }
        val count = reference.size.toLong() * 3
        // NumPy method=linear: (n-1)*.999 の両端を補間。並べ替えず histogram から取得。
        val rank = (count - 1) * .999
        fun at(index: Long): Int {
            var cumulative = 0L
            for (i in histogram.indices) { cumulative += histogram[i]; if (index < cumulative) return i }
            error("Invalid percentile rank")
        }
        val low = at(floor(rank).toLong())
        val high = at(ceil(rank).toLong())
        return Display(stage, maximum, channels.toList(), low + (high - low) * (rank - floor(rank)),
            sum.toDouble() / count, changed, changed.toDouble() / reference.size, worst)
    }

    fun linear(reference: LinearSrgbImage, actual: LinearSrgbImage, stage: String): Linear {
        requireAligned(reference.width, reference.height, actual.width, actual.height)
        return linear(reference.width, reference.pixelCount * 3, 3, stage, { reference[it] }, { actual[it] })
    }
    fun linear(reference: FloatPlane, actual: FloatPlane, stage: String): Linear {
        requireAligned(reference.width, reference.height, actual.width, actual.height)
        return linear(reference.width, reference.width * reference.height, 1, stage, { reference[it] }, { actual[it] })
    }

    private fun linear(width: Int, count: Int, channels: Int, stage: String,
        reference: (Int) -> Float, actual: (Int) -> Float): Linear {
        var maxAbs = -1.0
        var maxRel = -1.0
        var worstAbs = Location(0, 0, 0, 0.0, 0.0)
        var worstRel = worstAbs
        repeat(count) { i ->
            val ref = reference(i).toDouble()
            val value = actual(i).toDouble()
            require(ref.isFinite() && value.isFinite()) { "$stage: non-finite channel at $i" }
            val delta = abs(ref - value)
            val relative = delta / max(abs(ref), GpuTolerances.RELATIVE_DENOMINATOR_FLOOR)
            val location = Location(i / channels % width, i / channels / width, i % channels, ref, value)
            if (delta > maxAbs) { maxAbs = delta; worstAbs = location }
            if (relative > maxRel) { maxRel = relative; worstRel = location }
        }
        return Linear(stage, maxAbs, maxRel, worstAbs, worstRel)
    }

    fun scalar(reference: Double, actual: Double): Double {
        require(reference.isFinite() && actual.isFinite())
        return abs(reference - actual)
    }

    fun assertScalarWithin(reference: Double, actual: Double, tolerance: Double, stage: String) {
        require(tolerance.isFinite() && tolerance >= 0)
        if (!reference.isFinite() || !actual.isFinite() || abs(reference - actual) > tolerance) {
            throw AssertionError("$stage: ref=$reference, actual=$actual, abs=${abs(reference - actual)}, limit=$tolerance")
        }
    }

    private fun requireAligned(rw: Int, rh: Int, aw: Int, ah: Int) {
        require(rw == aw && rh == ah) { "Image dimensions differ: ${rw}x$rh != ${aw}x$ah" }
    }
}
