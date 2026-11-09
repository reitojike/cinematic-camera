package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test

class IntermediateValidationTest {
    @Test fun rawArrayEntrypointsStillRejectNonfiniteValuesAndInvalidShapes() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            rejects { FloatPlane(1, 1, floatArrayOf(invalid)) }
            rejects { FloatPlane.takeOwnership(1, 1, floatArrayOf(invalid)) }
            rejects { LinearSrgbImage(1, 1, floatArrayOf(0f, invalid, 1f)) }
            rejects { LinearSrgbImage.takeOwnership(1, 1, floatArrayOf(0f, invalid, 1f)) }
        }
        for ((width, height) in listOf(0 to 1, -1 to -1, 2 to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            rejects { FloatPlane(width, height, floatArrayOf(0f)) }
            rejects { FloatPlane.takeOwnership(width, height, floatArrayOf(0f)) }
            rejects { LinearSrgbImage(width, height, FloatArray(3)) }
            rejects { LinearSrgbImage.takeOwnership(width, height, FloatArray(3)) }
        }
        // Computed values can overflow even when the source is finite. Keep those checks.
        val maximum = LinearSrgbImage(1, 1, FloatArray(3) { Float.MAX_VALUE })
        rejects { maximum.scaled(2.0) }
        rejects { maximum.mapChannels { FloatPlane(it.width, it.height, floatArrayOf(Float.NaN)) } }
    }

    @Test fun channelCopiesPreserveAllFiniteBitsAndDoNotExposeBackingStorage() {
        val edges = floatArrayOf(-0f, 0f, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.MAX_VALUE, -Float.MAX_VALUE, -.25f, 1.5f, .25f)
        val image = LinearSrgbImage(3, 1, edges)
        val expected = edges.copyOf()
        edges.fill(Float.NaN)
        val retained = mutableListOf<FloatPlane>()
        val repacked = image.mapChannels { channel ->
            retained += channel
            channel.copyValues().fill(Float.NaN)
            channel
        }
        repacked.copyPixels().fill(Float.NaN)
        assertBits(expected, image.copyPixels())
        assertBits(expected, repacked.copyPixels())
        repeat(3) { channel ->
            assertBits(FloatArray(3) { expected[it * 3 + channel] }, retained[channel].copyValues())
            assertBits(retained[channel].copyValues(), FloatPlane.fromChannel(image, channel).copyValues())
        }
        rejects { FloatPlane.fromChannel(image, -1) }
        rejects { FloatPlane.fromChannel(image, 3) }
        rejects { image.mapChannels { FloatPlane(1, 3, it.copyValues()) } }
    }

    @Test fun blurMatchesPreviousCheckedCompositionBitForBit() {
        val colours = floatArrayOf(-.25f, 0f, -0f, Float.MIN_VALUE, 2f, .1f, .8f, 17f)
        for ((width, height) in listOf(1 to 1, 1 to 17, 19 to 1, 13 to 21, 32 to 24)) {
            val values = FloatArray(width * height * 3) { colours[(it * 7 + it / 3) % colours.size] }
            val image = LinearSrgbImage(width, height, values)
            for (radius in listOf(0.0, .008, .04, .23, 1.0)) {
                // Frozen pre-C3 split/rejoin: every raw-array wrapping performs its old check.
                val expected = FloatArray(values.size)
                repeat(3) { channel ->
                    val plane = FloatPlane.takeOwnership(width, height,
                        FloatArray(width * height) { image[it * 3 + channel] })
                    val blurred = StageClearBlur.plane(plane, radius)
                    repeat(width * height) { expected[it * 3 + channel] = blurred[it] }
                }
                val checked = LinearSrgbImage.takeOwnership(width, height, expected)
                assertBits(checked.copyPixels(), StageClearBlur.rgb(image, radius).copyPixels())
                assertBits(values, image.copyPixels())
            }
        }
    }

    @Test fun cachedSkinValidationKeepsEveryEntryPointAndAlignmentCheck() {
        val image = LinearSrgbImage(2, 1, FloatArray(6) { .4f })
        val entrypoints: List<(FloatPlane) -> Unit> = listOf(
            { StageClearGlow.render(image, 0.0, it) },
            { StageClearMist.render(image, it, 0.0) },
            { StageClearMist.blackMist(image, it) },
            { StageClearOrton.render(StageClearMist.Result(image, 0.0), it) },
            { StageClearOrton.orton(image, it) },
        )
        val valid = FloatPlane(2, 1, floatArrayOf(-0f, 1f))
        val misaligned = FloatPlane(1, 2, floatArrayOf(0f, 1f)).also { it.requireUnitRange() }
        val invalid = listOf(FloatPlane(2, 1, floatArrayOf(-Float.MIN_VALUE, 0f)),
            FloatPlane(2, 1, floatArrayOf(0f, Math.nextUp(1f))), misaligned)
        repeat(3) {
            entrypoints.forEach { enter ->
                enter(valid)
                invalid.forEach { skin -> rejects { enter(skin) } }
            }
        }
    }

    @Test fun cachedRangeBelongsOnlyToItsImmutablePlane() {
        val source = floatArrayOf(0f, .5f, 1f)
        val plane = FloatPlane(3, 1, source)
        plane.requireUnitRange()
        source.fill(-1f)
        plane.copyValues().fill(2f)
        plane.requireUnitRange()
        assertBits(floatArrayOf(0f, .5f, 1f), plane.copyValues())
        val invalid = FloatPlane(3, 1, source)
        repeat(2) { rejects { invalid.requireUnitRange() } }
        // The ownership constructor still validates finite values and range is not assumed.
        rejects { FloatPlane.takeOwnership(1, 1, floatArrayOf(2f)).requireUnitRange() }
    }

    @Test fun concurrentRangeChecksPublishTheSameResult() {
        val valid = FloatPlane(31, 17, FloatArray(31 * 17) { (it % 9) / 8f })
        val invalid = FloatPlane(31, 17, FloatArray(31 * 17) { if (it == 526) 1.01f else 0f })
        val executor = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 16).map {
                executor.submit<Boolean> {
                    repeat(10) {
                        valid.requireUnitRange()
                        rejects { invalid.requireUnitRange() }
                    }
                    true
                }
            }
            results.forEach { assertTrue(it.get()) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun channelTransformCancellationLeavesInputAndRetainedPlanesUnchanged() {
        val values = FloatArray(18) { it / 10f }
        val image = LinearSrgbImage(3, 2, values)
        var calls = 0
        var first: FloatPlane? = null
        assertThrows(CancellationException::class.java) {
            image.mapChannels { plane ->
                if (++calls == 2) throw CancellationException("Stopped")
                plane.also { first = it }
            }
        }
        assertEquals(2, calls)
        assertBits(values, image.copyPixels())
        assertBits(FloatArray(6) { values[it * 3] }, requireNotNull(first).copyValues())
    }

    private fun rejects(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }

    private fun assertBits(expected: FloatArray, actual: FloatArray) {
        assertArrayEquals(IntArray(expected.size) { expected[it].toRawBits() },
            IntArray(actual.size) { actual[it].toRawBits() })
    }
}
