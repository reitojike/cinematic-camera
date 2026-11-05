/* Pillow resampling equations; see assets/third_party/Pillow-LICENSE.txt.
 * Copyright (c) 1997-2011 Secret Labs AB; Copyright (c) 1995-2011 Fredrik Lundh;
 * Copyright (c) 2010 Jeffrey 'Alex' Clark and contributors. */
package dev.cinematic.p0.raw.original

import kotlin.math.*

internal enum class OriginalFilter { BOX, LINEAR, CUBIC }
internal typealias OriginalWeights = dev.cinematic.p0.raw.StageClearBlur.Samples

internal fun originalWeights(input: Int, output: Int, filter: OriginalFilter): Array<OriginalWeights> =
    dev.cinematic.p0.raw.StageClearBlur.samples(input, output, when (filter) {
        OriginalFilter.BOX -> dev.cinematic.p0.raw.StageClearBlur.Filter.BOX
        OriginalFilter.LINEAR -> dev.cinematic.p0.raw.StageClearBlur.Filter.BILINEAR
        OriginalFilter.CUBIC -> dev.cinematic.p0.raw.StageClearBlur.Filter.BICUBIC
    })
/** Global-coordinate blur/analysis field, never independently normalized per row/tile. */
internal class OriginalField(
    val width: Int, val height: Int, val channels: Int,
    private val values: FloatArray, private val outputWidth: Int, private val outputHeight: Int,
    filter: OriginalFilter = OriginalFilter.LINEAR,
) {
    private val columns = originalWeights(width, outputWidth, filter)
    private val rows = originalWeights(height, outputHeight, filter)
    private val verticalFirst = height - outputHeight > 0 && height.toLong() - outputHeight > 2L * (width - outputWidth)
    // The reduced field transfers its immutable values to this owner. Cache only separable Float
    // intermediates, never the field or a full output image. Other resize policies keep the old path.
    private val cacheCapacity = if (!verticalFirst && width > 1 && height > 1 &&
        outputWidth >= width && outputHeight >= height && (outputWidth > width || outputHeight > height)) {
        when (filter) { OriginalFilter.LINEAR -> 2; OriginalFilter.CUBIC -> 4; OriginalFilter.BOX -> 0 }
    } else 0
    private var horizontalCache: HorizontalRows? = null
    internal val rowCachePayloadBytes: Long
        @Synchronized get() = horizontalCache?.payloadBytes ?: 0L
    init { require(channels in 1..3 && values.size == width * height * channels) }
    fun reducedRgb(): dev.cinematic.p0.raw.LinearSrgbImage {
        require(channels == 3 && max(width, height) <= 1024)
        return dev.cinematic.p0.raw.LinearSrgbImage(width, height, values)
    }
    @Synchronized fun row(y: Int, output: FloatArray) {
        require(y in 0 until outputHeight && output.size == outputWidth * channels)
        val vertical = rows[y]
        if (cacheCapacity > 0 && vertical.weights.size <= cacheCapacity) {
            val cache = horizontalCache ?: HorizontalRows(cacheCapacity).also { horizontalCache = it }
            cache.row(vertical, output)
            return
        }
        repeat(outputWidth) { x ->
            val horizontal = columns[x]
            repeat(channels) { c ->
                var sum = 0.0
                if (verticalFirst) horizontal.weights.indices.forEach { dx ->
                    var value = 0.0
                    vertical.weights.indices.forEach { dy ->
                        value += values[((vertical.first + dy) * width + horizontal.first + dx) * channels + c] * vertical.weights[dy]
                    }
                    sum += value.toFloat() * horizontal.weights[dx]
                } else vertical.weights.indices.forEach { dy ->
                    var value = 0.0
                    horizontal.weights.indices.forEach { dx ->
                        value += values[((vertical.first + dy) * width + horizontal.first + dx) * channels + c] * horizontal.weights[dx]
                    }
                    // Preserve the Float32 intermediate between the two separable axes.
                    sum += value.toFloat() * vertical.weights[dy]
                }
                output[x * channels + c] = sum.toFloat()
            }
        }
    }

    /** All access occurs under row's instance lock, including non-monotonic and concurrent reads. */
    private inner class HorizontalRows(capacity: Int) {
        private val sourceRows = IntArray(capacity) { -1 }
        private val data = arrayOfNulls<FloatArray>(capacity)
        private val selected = IntArray(capacity)
        val payloadBytes get() = data.sumOf { (it?.size ?: 0).toLong() * Float.SIZE_BYTES }

        fun row(vertical: OriginalWeights, output: FloatArray) {
            val end = vertical.first + vertical.weights.size
            for (dy in vertical.weights.indices) {
                val source = vertical.first + dy
                var slot = sourceRows.indexOf(source)
                if (slot < 0) {
                    // Protect every contributor needed by this destination row, regardless of order.
                    slot = sourceRows.indexOfFirst { it < vertical.first || it >= end }
                    check(slot >= 0)
                    val buffer = data[slot] ?: FloatArray(outputWidth * channels).also { data[slot] = it }
                    horizontal(source, buffer)
                    sourceRows[slot] = source
                }
                selected[dy] = slot
            }
            for (i in output.indices) {
                var sum = 0.0
                for (dy in vertical.weights.indices) sum += requireNotNull(data[selected[dy]])[i] * vertical.weights[dy]
                output[i] = sum.toFloat()
            }
        }

        private fun horizontal(source: Int, output: FloatArray) {
            for (x in columns.indices) {
                val horizontal = columns[x]
                repeat(channels) { c ->
                    var value = 0.0
                    for (dx in horizontal.weights.indices)
                        value += values[(source * width + horizontal.first + dx) * channels + c] * horizontal.weights[dx]
                    // Exactly the old horizontal-first Float rounding, before the dy accumulation.
                    output[x * channels + c] = value.toFloat()
                }
            }
        }
    }
}

internal object OriginalFields {
    fun blur(input: OriginalRaster, radius: Double, channels: Int, stop: () -> Boolean,
        project: (FloatArray, FloatArray) -> Unit): OriginalField {
        val (width, height) = blurSize(input, radius)
        return reduce(input, width, height, channels, stop, OriginalFilter.LINEAR, project)
    }

    /** One immutable projection per input row, at most three strictly reduced horizontal-first grids.
     * Unsupported geometry/budget falls back BEFORE IO; failures after admission never replay input. */
    fun blurMany(input: OriginalRaster, radii: List<Double>, channels: Int, stop: () -> Boolean,
        project: (FloatArray, FloatArray) -> Unit): List<OriginalField> {
        require(radii.size in 1..3)
        val sizes = radii.map { blurSize(input, it) }
        sizes.forEach { (width, height) -> validateGrid(input, width, height, channels) }
        val eligible = input.width > 1 && input.height > 1 && sizes.all { (width, height) ->
            width < input.width && height < input.height && !isVerticalFirst(input, width, height)
        }
        val elements = sizes.map { (width, height) -> width.toLong() * height * channels }
        if (!eligible || !batchAccumulatorsFit(elements)) return radii.map { blur(input, it, channels, stop, project) }
        return collect(input, sizes.map { (width, height) -> PreparedGrid(input, width, height, channels, OriginalFilter.LINEAR) },
            channels, stop, project)
    }

    /** Double payload only: coefficients/Contributor metadata remain bounded by at most three grids. */
    internal fun batchAccumulatorsFit(elements: List<Long>): Boolean {
        if (elements.size !in 1..3 || elements.any { it !in 1..16_000_000L }) return false
        val total = elements.sum()
        return total <= 16_000_000L && (total - elements.max()) * Double.SIZE_BYTES <= 512L * 1024
    }

    private fun blurSize(input: OriginalRaster, radius: Double): Pair<Int, Int> {
        require(radius.isFinite() && radius > 0)
        require(input.width > 0 && input.height > 0)
        val cell = max(radius * max(input.width, input.height), 1.0)
        return max(2, round(input.width / cell).toInt()) to max(2, round(input.height / cell).toInt())
    }

    private fun isVerticalFirst(input: OriginalRaster, width: Int, height: Int) =
        input.height - height > 0 && input.height.toLong() - height > 2L * (input.width - width)

    private fun validateGrid(input: OriginalRaster, width: Int, height: Int, channels: Int) {
        require(input.width > 0 && input.height > 0 && input.width.toLong() * 3 <= Int.MAX_VALUE)
        require(width > 0 && height > 0 && channels in 1..3 && width.toLong() * height * channels <= 3L * 1024 * 1024)
        val intermediateWidth = if (isVerticalFirst(input, width, height)) input.width else width
        require(intermediateWidth.toLong() * height * channels <= 16_000_000) { "Analysis field exceeds memory budget" }
    }

    /** BOX reduction holds only a bounded global grid and a row; no full planar intermediate. */
    fun reduce(input: OriginalRaster, width: Int, height: Int, channels: Int, stop: () -> Boolean,
        interpolation: OriginalFilter = OriginalFilter.LINEAR,
        project: (FloatArray, FloatArray) -> Unit): OriginalField {
        validateGrid(input, width, height, channels)
        return collect(input, listOf(PreparedGrid(input, width, height, channels, interpolation)), channels, stop, project).single()
    }

    private fun collect(input: OriginalRaster, grids: List<PreparedGrid>, channels: Int, stop: () -> Boolean,
        project: (FloatArray, FloatArray) -> Unit): List<OriginalField> {
        val projected = FloatArray(input.width * channels)
        var next = 0
        originalCheckpoint(stop)
        input.rows(stop) { y, rgb ->
            require(y == next && y < input.height && rgb.size == input.width * 3) { "Invalid original raster row" }
            project(rgb, projected)
            require(projected.all { it.isFinite() }) { "Non-finite original field projection" }
            grids.forEach { originalCheckpoint(stop); it.accept(y, projected) }
            next++
        }
        require(next == input.height) { "Incomplete original raster" }
        // rows() returning includes the reader's close acknowledgement. No fields escape earlier.
        return grids.map { originalCheckpoint(stop); it.finish() }
    }

    private class PreparedGrid(private val input: OriginalRaster, private val width: Int, private val height: Int,
        private val channels: Int, private val interpolation: OriginalFilter) {
        private val columns = originalWeights(input.width, width, OriginalFilter.BOX)
        private val vertical = originalWeights(input.height, height, OriginalFilter.BOX)
        private val contributors = Array(input.height) { mutableListOf<Pair<Int, Double>>() }.also { contributors ->
            vertical.forEachIndexed { y, weights -> weights.weights.forEachIndexed { dy, w ->
                if (w != 0.0) contributors[weights.first + dy].add(y to w)
            } }
        }
        private val verticalFirst = isVerticalFirst(input, width, height)
        private val intermediateWidth = if (verticalFirst) input.width else width
        private val accum = DoubleArray(intermediateWidth * height * channels)
        private val horizontal = FloatArray(width * channels)

        fun accept(y: Int, projected: FloatArray) {
            if (!verticalFirst) repeat(width) { x -> repeat(channels) { c ->
                val weights = columns[x]
                var value = 0.0
                weights.weights.indices.forEach { dx -> value += projected[(weights.first + dx) * channels + c] * weights.weights[dx] }
                horizontal[x * channels + c] = value.toFloat()
            } }
            val row = if (verticalFirst) projected else horizontal
            contributors[y].forEach { (dy, weight) ->
                row.indices.forEach { i -> accum[dy * row.size + i] += row[i] * weight }
            }
        }

        fun finish(): OriginalField {
            val values = if (!verticalFirst) FloatArray(accum.size) { accum[it].toFloat() } else {
                FloatArray(width * height * channels).also { out ->
                    repeat(height) { y -> repeat(width) { x -> repeat(channels) { c ->
                        val weights = columns[x]
                        var value = 0.0
                        weights.weights.indices.forEach { dx ->
                            value += accum[(y * intermediateWidth + weights.first + dx) * channels + c].toFloat() * weights.weights[dx]
                        }
                        out[(y * width + x) * channels + c] = value.toFloat()
                    } } }
                }
            }
            return OriginalField(width, height, channels, values,
                input.width, input.height, interpolation)
        }
    }
}

internal fun interface OriginalFloatConsumer { fun accept(value: Float) }
internal fun interface OriginalSamples { fun visit(consumer: OriginalFloatConsumer) }

/** Exact linear quantiles of finite Float32 samples. Two streaming passes, <=5 histogram planes. */
internal object OriginalQuantiles {
    fun evaluate(samples: OriginalSamples, stop: () -> Boolean, vararg quantiles: Double): DoubleArray {
        require(quantiles.size in 1..2 && quantiles.all { it in 0.0..1.0 })
        fun key(value: Float): Long {
            require(value.isFinite())
            val bits = value.toRawBits()
            return (bits xor if (bits < 0) -1 else Int.MIN_VALUE).toLong() and 0xffffffffL
        }
        val high = IntArray(65536)
        var count = 0
        samples.visit { value ->
            if ((count and 65535) == 0) originalCheckpoint(stop)
            require(count < 24_000_000)
            high[(key(value) ushr 16).toInt()]++; count++
        }
        require(count > 0)
        val ranks = quantiles.flatMap { q -> val rank = (count - 1) * q; listOf(floor(rank).toInt(), ceil(rank).toInt()) }
        fun bucket(rank: Int): Pair<Int, Int> {
            var remaining = rank
            for (i in high.indices) { if (remaining < high[i]) return i to remaining; remaining -= high[i] }
            error("Quantile rank out of range")
        }
        val targets = ranks.map(::bucket)
        val low = targets.map { it.first }.distinct().associateWith { IntArray(65536) }
        var secondCount = 0
        samples.visit { value ->
            if ((secondCount and 65535) == 0) originalCheckpoint(stop)
            val k = key(value)
            low[(k ushr 16).toInt()]?.let { it[(k and 65535).toInt()]++ }
            secondCount++
        }
        check(secondCount == count)
        val values = targets.map { (hi, rank) ->
            var remaining = rank
            val histogram = requireNotNull(low[hi])
            val lo = histogram.indices.first { i -> if (remaining < histogram[i]) true else { remaining -= histogram[i]; false } }
            val k = (hi shl 16) or lo
            Float.fromBits(k xor if (k < 0) Int.MIN_VALUE else -1).toDouble()
        }
        return DoubleArray(quantiles.size) { i ->
            val rank = (count - 1) * quantiles[i]
            values[i * 2] + (values[i * 2 + 1] - values[i * 2]) * (rank - floor(rank))
        }
    }
}
