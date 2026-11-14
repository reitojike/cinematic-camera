package dev.cinematic.p0.raw

import dev.cinematic.p0.gpu.constants.R7BaseV1
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class PreviewFloatSelectionTest {
    @Test fun everyRankMatchesSortIncludingSignedZeroDuplicatesAndFiniteBoundaries() {
        val random = Random(294)
        val boundaries = floatArrayOf(-Float.MAX_VALUE, -java.lang.Float.MIN_NORMAL, -Float.MIN_VALUE,
            -0f, 0f, Float.MIN_VALUE, java.lang.Float.MIN_NORMAL, Math.nextDown(1f), 1f,
            Math.nextUp(1f), Float.MAX_VALUE)
        val cases = mutableListOf(floatArrayOf(-0f), floatArrayOf(0f), boundaries,
            boundaries.reversedArray(), FloatArray(129) { -0f }, FloatArray(129) { .2f },
            FloatArray(257) { if (it % 2 == 0) -0f else 0f },
            FloatArray(257) { it.toFloat() }, FloatArray(257) { (257 - it).toFloat() },
            FloatArray(257) { min(it, 256 - it).toFloat() })
        repeat(40) { iteration ->
            cases += FloatArray(iteration * 3 + 1) {
                if (iteration % 2 == 0) boundaries[random.nextInt(boundaries.size)]
                else Float.fromBits(random.nextInt()).let { if (it.isFinite()) it else 0f }
            }
        }
        for (input in cases) {
            val expected = input.sortedArray()
            val reused = input.copyOf()
            for (rank in input.indices.reversed()) {
                assertEquals(expected[rank].toRawBits(), PreviewFloatSelection.select(input.copyOf(), rank).toRawBits())
                assertEquals(expected[rank].toRawBits(), PreviewFloatSelection.select(reused, rank).toRawBits())
            }
        }
    }

    @Test fun largeDuplicateAndOrderedInputsMatchAllQuantileEndpoints() {
        val random = Random(97)
        for (input in listOf(FloatArray(262144) { random.nextFloat() },
            FloatArray(262144) { (it % 17).toFloat() }, FloatArray(262144) { it.toFloat() },
            FloatArray(262144) { (262144 - it).toFloat() })) {
            val sorted = input.sortedArray()
            for (q in listOf(0.0, .5, .92, .97, 1.0)) {
                val rank = input.lastIndex * q
                for (k in listOf(floor(rank).toInt(), ceil(rank).toInt())) {
                    assertEquals(sorted[k].toRawBits(), PreviewFloatSelection.select(input, k).toRawBits())
                }
            }
        }
    }

    @Test fun rejectsInvalidRanksAndChecksCancellationWithinPartition() {
        for ((input, rank) in listOf(FloatArray(0) to 0, floatArrayOf(1f) to -1, floatArrayOf(1f) to 1)) {
            assertThrows(IllegalArgumentException::class.java) { PreviewFloatSelection.select(input, rank) }
        }
        val input = FloatArray(16384) { (it % 31).toFloat() }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PreviewFloatSelection.select(input, 8192) {
                if (++checks == 3) throw CancellationException("inside first partition")
            }
        }
        assertEquals(3, checks)
        assertEquals(input.sortedArray()[8192].toRawBits(), PreviewFloatSelection.select(input, 8192).toRawBits())
        assertThrows(CancellationException::class.java) {
            PreviewFloatSelection.select(floatArrayOf(0f), 0) { throw CancellationException("singleton") }
        }
    }

    @Test fun previewMeteringMatchesSortedFloat32InterpolationAndEvRawBits() {
        val random = Random(997)
        // These central sample counts exercise integral ranks and both float32 interpolation branches.
        for (width in 2..35) for (height in listOf(2, 8, 202)) {
            for (mode in 0..3) {
                val rgb = FloatArray(width * height * 3) {
                    when (mode) { 0 -> 0f; 1 -> -0f; 2 -> .2f; else -> random.nextFloat() * 8f - .1f }
                }
                val image = LinearSrgbImage(width, height, rgb)
                val samples = mutableListOf<Float>()
                for (y in height / 4 until 3 * height / 4) for (x in width / 4 until 3 * width / 4) {
                    val i = (y * width + x) * 3
                    samples += rgb[i] * .2126f + rgb[i + 1] * .7152f + rgb[i + 2] * .0722f
                }
                val sorted = samples.toFloatArray().also { it.sort() }
                val rank = sorted.lastIndex * .97
                val lo = floor(rank).toInt(); val hi = ceil(rank).toInt()
                val fraction = (rank - lo).toFloat()
                val delta = sorted[hi] - sorted[lo]
                val p97 = if (fraction >= .5f) sorted[hi] - delta * (1f - fraction)
                    else sorted[lo] + delta * fraction
                val ev = (-.13 - .83 * log2(max(p97.toDouble(), 1e-5))).coerceIn(-3.0, 6.0) - R7BaseV1.BRIGHTNESS_EV
                val result = R7PreviewInputs.prepare(asShot(image), emptyList())
                assertEquals(p97.toRawBits(), result.exposure.centralP97.toRawBits())
                assertEquals(ev.toRawBits(), result.exposure.externalBaseEv.toRawBits())
                assertBits(rgb, image.copyPixels())
            }
        }
    }

    @Test fun completeR7MatchesSortedToneBaselineRawBitsWithoutMutatingInput() {
        val random = Random(50)
        for ((width, height) in listOf(1 to 1, 7 to 5, 32 to 24, 65 to 49)) for (mode in 0..3) {
            val rgb = FloatArray(width * height * 3) {
                when (mode) { 0 -> 0f; 1 -> .2f; 2 -> (it % 19) / 10f; else -> random.nextFloat() * 4f - .1f }
            }
            val input = LinearSrgbImage(width, height, rgb)
            val roi = FloatPlane(width, height, FloatArray(width * height) { (it % 5) / 4f })
            val profile = R7Base.profile(input)
            val base = R7Base.smoothLogLuma(profile)
            val sorted = base.copyValues().also { it.sort() }
            fun percentile(q: Double): Double {
                val rank = (sorted.size - 1) * q; val lo = floor(rank).toInt(); val hi = ceil(rank).toInt()
                return sorted[lo].toDouble() + (sorted[hi].toDouble() - sorted[lo]) * (rank - lo)
            }
            val high = percentile(.92); val low = percentile(.5)
            val tone = profile.copyPixels()
            repeat(input.pixelCount) { p ->
                val value = base[p].toDouble()
                val delta = -R7BaseV1.HIGHLIGHTS * max(value - high, 0.0) - R7BaseV1.SHADOWS * min(value - low, 0.0)
                val gain = 2.0.pow(delta)
                repeat(3) { c -> tone[p * 3 + c] = (profile[p * 3 + c] * gain).toFloat() }
            }
            for (ev in listOf(-2.0, 0.0, 2.0)) {
                val expected = R7Base.skin(R7Base.crisp(R7Base.vibrance(
                    R7Base.foundation(LinearSrgbImage(width, height, tone), roi, ev))))
                assertBits(expected.copyPixels(), R7Base.render(input, roi, ev).copyPixels())
            }
            assertBits(rgb, input.copyPixels())
        }
    }

    @Test fun selectionCancellationPropagatesThroughBothCallersWithoutChangingSource() {
        val rgb = FloatArray(16 * 16 * 3) { (it % 17) / 16f }
        val image = LinearSrgbImage(16, 16, rgb)
        val roi = FloatPlane(16, 16, FloatArray(256))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            R7Base.render(image, roi, 0.0) { ++checks == 4 }
        }
        assertEquals(4, checks) // render entry, after profile, partition entry, partition scan
        assertBits(rgb, image.copyPixels())
        checks = 0
        assertThrows(CancellationException::class.java) {
            R7PreviewInputs.prepare(asShot(image), emptyList()) { ++checks == 12 }
        }
        assertEquals(12, checks) // entry + 8 rows + before selection + partition entry + scan
        assertBits(rgb, image.copyPixels())
        assertTrue(R7PreviewInputs.prepare(asShot(image), emptyList()).exposure.centralP97.isFinite())
        assertTrue(R7Base.render(image, roi, 0.0)[0].isFinite())
    }

    private fun asShot(image: LinearSrgbImage) =
        R7PreviewInputs.AsShotInput(image, R7PreviewInputs.Source.SYNTHETIC_AS_SHOT)

    private fun assertBits(expected: FloatArray, actual: FloatArray) {
        assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
    }
}
