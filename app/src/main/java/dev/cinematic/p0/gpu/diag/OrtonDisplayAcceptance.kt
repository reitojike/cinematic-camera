package dev.cinematic.p0.gpu.diag

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * DISPLAY8-only Orton boundary rule (#276/#282).
 * The mask uses the Kotlin mixOrton input, never GPU values. Linear-stage gates are unchanged.
 */
internal object OrtonDisplayAcceptance {
    data class MaskedPixel(
        val x: Int, val y: Int,
        val cpuInput: List<Float>, val gpuInput: List<Float>,
        val cpuDisplay: List<Int>, val gpuDisplay: List<Int>, val displayDiff: List<Int>,
    )
    data class Result(
        val pixelCount: Int,
        val maskedPixels: List<MaskedPixel>,
        val maxDiffAll: Int,
        val maxDiffOutsideMask: Int,
        val p999AllRgb: Double,
    ) {
        val maskFraction: Double get() = maskedPixels.size.toDouble() / pixelCount
        val maskWithinLimit: Boolean get() = maskedPixels.size.toLong() * 10000L <= pixelCount.toLong()
        val passed: Boolean get() = maskWithinLimit && maxDiffOutsideMask <= 2 && p999AllRgb <= 1.0

        /** Emit every line for every condition, including the zero-mask summary. */
        fun logLines(): Sequence<String> = sequence {
            yield("OrtonDisplay pixels=$pixelCount masked=${maskedPixels.size} fraction=$maskFraction " +
                "maskWithinLimit=$maskWithinLimit maxAll=$maxDiffAll maxOutside=$maxDiffOutsideMask " +
                "p999AllRgb=$p999AllRgb passed=$passed")
            for (pixel in maskedPixels) yield("OrtonDisplay excludedMaxOnly=$pixel")
        }
        fun assertPassed() {
            if (!passed) throw AssertionError(logLines().joinToString("\n"))
        }
    }

    /** Inputs are RGB-indexed mixOrton image values; displays are packed ARGB, alpha ignored. */
    fun evaluate(
        width: Int, height: Int,
        cpuInput: (Int) -> Float, gpuInput: (Int) -> Float,
        cpuDisplay: IntArray, gpuDisplay: IntArray,
    ): Result {
        require(width > 0 && height > 0)
        val countLong = width.toLong() * height
        require(countLong <= Int.MAX_VALUE / 3)
        val count = countLong.toInt()
        require(cpuDisplay.size == count && gpuDisplay.size == count)
        val histogram = LongArray(256)
        val masked = mutableListOf<MaskedPixel>()
        var maxAll = 0
        var maxOutside = 0
        for (p in 0 until count) {
            var isMasked = false
            for (c in 0..2) {
                val cpu = cpuInput(p * 3 + c)
                val gpu = gpuInput(p * 3 + c)
                require(cpu.isFinite() && gpu.isFinite()) { "Non-finite Orton input pixel=$p channel=$c" }
                if (abs(cpu.toDouble() - 1.0) <= 1e-5) isMasked = true
            }
            fun channels(argb: Int) = List(3) { c -> (argb ushr (16 - c * 8)) and 255 }
            for (c in 0..2) {
                val shift = 16 - c * 8
                val diff = abs(((cpuDisplay[p] ushr shift) and 255) - ((gpuDisplay[p] ushr shift) and 255))
                histogram[diff]++
                maxAll = maxOf(maxAll, diff)
                if (!isMasked) maxOutside = maxOf(maxOutside, diff)
            }
            if (isMasked) {
                val cpu = channels(cpuDisplay[p]); val gpu = channels(gpuDisplay[p])
                masked += MaskedPixel(p % width, p / width,
                    List(3) { cpuInput(p * 3 + it) }, List(3) { gpuInput(p * 3 + it) },
                    cpu, gpu, List(3) { abs(cpu[it] - gpu[it]) })
            }
        }
        // Same interpolated percentile as ImageDiff; masked pixels remain in all RGB samples.
        val rank = (countLong * 3 - 1) * .999
        fun at(index: Long): Int {
            var cumulative = 0L
            for (i in histogram.indices) {
                cumulative += histogram[i]
                if (index < cumulative) return i
            }
            error("Invalid percentile rank")
        }
        val low = at(floor(rank).toLong())
        val high = at(ceil(rank).toLong())
        return Result(count, masked, maxAll, maxOutside, low + (high - low) * (rank - floor(rank)))
    }
}
