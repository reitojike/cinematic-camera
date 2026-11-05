package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.StageClearBlur
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class OriginalFieldRowCacheTest {
    private data class Shape(val width: Int, val height: Int, val outWidth: Int, val outHeight: Int)
    private val shapes = listOf(
        Shape(25, 17, 61, 43), Shape(17, 25, 43, 61), Shape(2, 2, 13, 11), Shape(3, 2, 12, 9),
        Shape(2, 3, 11, 14), Shape(4, 5, 4, 17), Shape(5, 4, 19, 4), Shape(5, 7, 5, 7),
        Shape(1, 1, 9, 7), Shape(1, 5, 11, 17), Shape(5, 1, 17, 9), Shape(9, 7, 3, 2),
        Shape(11, 5, 7, 3), Shape(5, 13, 3, 4), Shape(7, 4, 17, 2), Shape(4, 7, 2, 17),
        Shape(2, 2, 2, 9), Shape(17, 25, 17, 61),
    )

    @Test fun eighteenShapesMatchCanonicalResizeBitwiseForBothFiltersAndOneOrThreeChannels() {
        for (shape in shapes) for (filter in listOf(OriginalFilter.LINEAR, OriginalFilter.CUBIC)) for (channels in listOf(1, 3)) {
            val input = values(shape.width, shape.height, channels)
            val field = OriginalField(shape.width, shape.height, channels, input, shape.outWidth, shape.outHeight, filter)
            val expected = canonical(shape, channels, input, filter)
            val output = FloatArray(shape.outWidth * channels)
            for (y in 0 until shape.outHeight) {
                field.row(y, output)
                assertRowBits(shape, channels, expected, y, output)
            }
        }
    }

    @Test fun rewindsShuffledRowsAndRepeatedWalksKeepCanonicalBits() {
        val shape = Shape(7, 5, 37, 29)
        for (filter in listOf(OriginalFilter.LINEAR, OriginalFilter.CUBIC)) {
            val input = values(shape.width, shape.height, 3)
            val field = OriginalField(shape.width, shape.height, 3, input, shape.outWidth, shape.outHeight, filter)
            val expected = canonical(shape, 3, input, filter)
            val order = (0 until shape.outHeight).toList()
            val visits = order + order.reversed() + order.shuffled(Random(164)) + listOf(0, 28, 0, 14, 2, 27) + order
            val row = FloatArray(shape.outWidth * 3)
            for (y in visits) { field.row(y, row); assertRowBits(shape, 3, expected, y, row) }
        }
    }

    @Test fun concurrentReadersUseIndependentOutputAndOneBoundedCache() {
        val shape = Shape(13, 9, 47, 31)
        val input = values(shape.width, shape.height, 3)
        val field = OriginalField(shape.width, shape.height, 3, input, shape.outWidth, shape.outHeight, OriginalFilter.CUBIC)
        val expected = canonical(shape, 3, input, OriginalFilter.CUBIC)
        val threads = Executors.newFixedThreadPool(4)
        try {
            val results = (0 until 4).map { worker -> threads.submit(Callable {
                val row = FloatArray(shape.outWidth * 3)
                repeat(150) { visit ->
                    val y = (visit * 17 + worker * 11) % shape.outHeight
                    field.row(y, row)
                    assertRowBits(shape, 3, expected, y, row)
                }
            }) }
            results.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(4L * shape.outWidth * 3 * Float.SIZE_BYTES, field.rowCachePayloadBytes)
        } finally { threads.shutdownNow() }
    }

    @Test fun originalToneGridAllocatesOnlyFourLazyHorizontalFloatRows() {
        val input = values(25, 17, 1)
        val field = OriginalField(25, 17, 1, input, 6000, 4000, OriginalFilter.CUBIC)
        val originalInput = input.clone()
        val row = FloatArray(6000)
        assertEquals(0L, field.rowCachePayloadBytes)
        field.row(0, row)
        assertEquals(2L * 6000 * Float.SIZE_BYTES, field.rowCachePayloadBytes)
        for (y in listOf(2000, 3999, 1, 1800, 0, 3999)) {
            field.row(y, row)
            assertTrue(field.rowCachePayloadBytes <= 96_000L)
        }
        assertEquals(96_000L, field.rowCachePayloadBytes)
        assertTrue(originalInput.indices.all { input[it].toRawBits() == originalInput[it].toRawBits() })
        val linear = OriginalField(25, 17, 1, input, 6000, 4000, OriginalFilter.LINEAR)
        linear.row(2000, row)
        assertEquals(48_000L, linear.rowCachePayloadBytes)
    }

    @Test fun onePixelAxesReductionVerticalFirstAndBoxKeepUncachedFallback() {
        val fallback = listOf(Shape(1, 5, 11, 17), Shape(5, 1, 17, 9), Shape(9, 7, 3, 2),
            Shape(5, 13, 3, 4), Shape(4, 7, 2, 17), Shape(5, 7, 5, 7))
        for (shape in fallback) for (filter in listOf(OriginalFilter.LINEAR, OriginalFilter.CUBIC)) {
            val field = OriginalField(shape.width, shape.height, 1, values(shape.width, shape.height, 1), shape.outWidth, shape.outHeight, filter)
            repeat(shape.outHeight) { field.row(it, FloatArray(shape.outWidth)) }
            assertEquals(0L, field.rowCachePayloadBytes)
        }
        val box = OriginalField(3, 2, 1, values(3, 2, 1), 17, 11, OriginalFilter.BOX)
        box.row(5, FloatArray(17))
        assertEquals(0L, box.rowCachePayloadBytes)
    }

    /** Nonuniform HDR, negative lobes and distinct frame edges; no full original-sized image. */
    private fun values(width: Int, height: Int, channels: Int) = FloatArray(width * height * channels) { i ->
        when {
            i == 0 -> -3.25f
            i == width * height * channels - 1 -> 32.75f
            else -> (((i * 73 + i / 7 * 19) % 257) - 41) / 19f
        }
    }

    private fun canonical(shape: Shape, channels: Int, input: FloatArray, filter: OriginalFilter): Array<FloatArray> =
        Array(channels) { channel ->
            val plane = FloatPlane(shape.width, shape.height, FloatArray(shape.width * shape.height) { input[it * channels + channel] })
            StageClearBlur.resize(plane, shape.outWidth, shape.outHeight, when (filter) {
                OriginalFilter.LINEAR -> StageClearBlur.Filter.BILINEAR
                OriginalFilter.CUBIC -> StageClearBlur.Filter.BICUBIC
                OriginalFilter.BOX -> StageClearBlur.Filter.BOX
            }).copyValues()
        }

    private fun assertRowBits(shape: Shape, channels: Int, expected: Array<FloatArray>, y: Int, row: FloatArray) {
        for (x in 0 until shape.outWidth) for (c in 0 until channels) {
            val wanted = expected[c][y * shape.outWidth + x].toRawBits()
            val actual = row[x * channels + c].toRawBits()
            if (wanted != actual) fail("$shape channel=$c row=$y x=$x bits: expected=$wanted actual=$actual")
        }
    }
}
