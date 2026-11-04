/*
 * Pillow-compatible resampling; see assets/third_party/Pillow-LICENSE.txt.
 * Copyright (c) 1997-2011 Secret Labs AB
 * Copyright (c) 1995-2011 Fredrik Lundh and contributors
 * Copyright (c) 2010 Jeffrey 'Alex' Clark and contributors
 */
package dev.cinematic.p0.raw

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** r10_iconic.blur / blur_rgb numerical boundary, not the full Stage Clear look. */
internal object StageClearBlur {
    internal enum class Filter { BOX, BILINEAR, BICUBIC }

    fun plane(input: FloatPlane, radiusFraction: Double): FloatPlane {
        val (width, height) = reducedSize(input.width, input.height, radiusFraction)
        val reduced = resize(input, width, height, Filter.BOX)
        return resize(reduced, input.width, input.height, Filter.BILINEAR)
    }

    fun rgb(input: LinearSrgbImage, radiusFraction: Double): LinearSrgbImage {
        // Validate before allocating any channel buffers.
        reducedSize(input.width, input.height, radiusFraction)
        val pixels = FloatArray(input.pixelCount * 3)
        repeat(3) { channel ->
            val values = FloatArray(input.pixelCount) { input[it * 3 + channel] }
            val blurred = plane(FloatPlane(input.width, input.height, values), radiusFraction)
            repeat(input.pixelCount) { pixels[it * 3 + channel] = blurred[it] }
        }
        return LinearSrgbImage(input.width, input.height, pixels)
    }

    internal fun reducedSize(width: Int, height: Int, radiusFraction: Double): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Blur dimensions must be positive" }
        require(radiusFraction.isFinite() && radiusFraction >= 0) { "Radius must be finite and nonnegative" }
        val cell = max(radiusFraction * max(width, height), 1.0)
        // kotlin.math.round, unlike roundToInt, matches Python's ties-to-even.
        return max(2, round(width / cell).toInt()) to max(2, round(height / cell).toInt())
    }

    /** Float32 planar full-frame resize only; weights/accumulator are double, axes round to float. */
    internal fun resize(input: FloatPlane, width: Int, height: Int, filter: Filter): FloatPlane {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE) {
            "Resized plane must fit a nonempty packed array"
        }
        if (input.width == width && input.height == height) return input
        val heightCut = input.height.toLong() - height
        val widthCut = input.width.toLong() - width
        // Pillow 13 chooses the smaller temporary when the vertical reduction dominates.
        val verticalFirst = heightCut > 0 && heightCut > 2 * widthCut
        return if (verticalFirst) {
            horizontal(vertical(input, height, filter), width, filter)
        } else {
            vertical(horizontal(input, width, filter), height, filter)
        }
    }

    private data class Samples(val first: Int, val weights: DoubleArray)

    private fun samples(inputSize: Int, outputSize: Int, filter: Filter): Array<Samples> {
        // Pillow's full-frame box endpoints are float32 even though coefficients are double.
        val scale = inputSize.toFloat().toDouble() / outputSize
        val footprint = max(scale, 1.0)
        val support = footprint * when (filter) {
            Filter.BOX -> .5
            Filter.BILINEAR -> 1.0
            Filter.BICUBIC -> 2.0
        }
        val inverse = 1.0 / footprint
        return Array(outputSize) { destination ->
            val centre = (destination + .5) * scale
            // C conversion truncates toward zero here; floor differs at the left boundary.
            val first = max(0, (centre - support + .5).toInt())
            val end = minOf(inputSize, (centre + support + .5).toInt())
            val weights = DoubleArray(end - first) { offset ->
                val distance = (first + offset - centre + .5) * inverse
                when (filter) {
                    Filter.BOX -> if (distance > -.5 && distance <= .5) 1.0 else 0.0
                    Filter.BILINEAR -> max(0.0, 1 - abs(distance))
                    // Pillow's cubic convolution, a=-0.5; negative lobes are intentional.
                    Filter.BICUBIC -> abs(distance).let { x ->
                        when {
                            x < 1 -> ((1.5 * x - 2.5) * x) * x + 1
                            x < 2 -> (((-.5 * x + 2.5) * x - 4) * x) + 2
                            else -> 0.0
                        }
                    }
                }
            }
            val total = weights.sum()
            if (total != 0.0) for (i in weights.indices) weights[i] /= total
            Samples(first, weights)
        }
    }

    private fun horizontal(input: FloatPlane, width: Int, filter: Filter): FloatPlane {
        if (width == input.width) return input
        require(width.toLong() * input.height <= Int.MAX_VALUE) { "Horizontal intermediate is too large" }
        val columns = samples(input.width, width, filter)
        val values = FloatArray(width * input.height)
        repeat(input.height) { y ->
            repeat(width) { x ->
                val sample = columns[x]
                var value = 0.0
                for (i in sample.weights.indices) value += input[y * input.width + sample.first + i] * sample.weights[i]
                values[y * width + x] = value.toFloat()
            }
        }
        return FloatPlane(width, input.height, values)
    }

    private fun vertical(input: FloatPlane, height: Int, filter: Filter): FloatPlane {
        if (height == input.height) return input
        require(height.toLong() * input.width <= Int.MAX_VALUE) { "Vertical intermediate is too large" }
        val rows = samples(input.height, height, filter)
        val values = FloatArray(input.width * height)
        repeat(height) { y ->
            val sample = rows[y]
            repeat(input.width) { x ->
                var value = 0.0
                for (i in sample.weights.indices) value += input[(sample.first + i) * input.width + x] * sample.weights[i]
                values[y * input.width + x] = value.toFloat()
            }
        }
        return FloatPlane(input.width, height, values)
    }
}
