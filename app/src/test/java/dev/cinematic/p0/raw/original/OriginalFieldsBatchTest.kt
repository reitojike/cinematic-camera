package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.StageClearBlur
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class OriginalFieldsBatchTest {
    private val radii = listOf(.035, .11, .27)

    @Test fun eachFieldAndRestoredRowsMatchSeparateBlurAndCanonicalBitsForHdrEdgesAndRatios() {
        for ((width, height) in listOf(48 to 32, 77 to 51, 51 to 77, 37 to 29, 29 to 37, 64 to 64, 128 to 67, 67 to 128)) {
            for (channels in listOf(1, 3)) compare(width, height, radii, channels, expectedWalks = 1)
        }
    }

    @Test fun onePixelSmallIdentityAndVerticalFirstFallBackBeforeWalking() {
        for ((width, height) in listOf(1 to 17, 17 to 1, 2 to 2, 8 to 6))
            compare(width, height, listOf(.006, .02, .06), 3, expectedWalks = 3)
        compare(32, 129, listOf(.05, .13, .31), 3, expectedWalks = 3)
        // One unreduced grid rejects the whole batch before any read, rather than mixing strategies.
        compare(48, 32, listOf(.001, .11, .27), 1, expectedWalks = 3)
    }

    @Test fun singleReduceRetainsBothAxisOrdersAndInterpolationFilters() {
        for ((width, height) in listOf(47 to 31, 29 to 97, 1 to 17, 17 to 1)) for (channels in listOf(1, 3)) {
            for (filter in listOf(OriginalFilter.LINEAR, OriginalFilter.CUBIC)) {
                val source = Raster(width, height)
                val field = OriginalFields.reduce(source, 7, 11, channels, { false }, filter, projection(channels))
                val (_, restored) = canonical(width, height, 7, 11, channels, filter)
                assertRows(field, restored, width, height, channels)
                assertEquals(1, source.walks)
                assertEquals(1, source.closes)
            }
        }
    }

    @Test fun oneTwoAndThreeGridsShareOneWalkAndProjectionWithoutExposingPartialResults() {
        for (count in 1..3) {
            val input = Raster(48, 32)
            var projects = 0
            val project = projection(3)
            val fields = OriginalFields.blurMany(input, radii.take(count), 3, { false }) { rgb, out ->
                projects++; project(rgb, out)
            }
            assertEquals(count, fields.size)
            assertEquals(1, input.walks)
            assertEquals(input.height, projects)
            assertEquals(1, input.closes)
            assertEquals(0, input.open)
        }
    }

    @Test fun budgetChecksAreInclusiveAndCannotOverflowOrIncreaseTheExistingAggregateLimit() {
        assertTrue(OriginalFields.batchAccumulatorsFit(listOf(1_000_000L, 65_536L)))
        assertFalse(OriginalFields.batchAccumulatorsFit(listOf(1_000_000L, 65_537L)))
        assertTrue(OriginalFields.batchAccumulatorsFit(listOf(15_999_999L, 1L)))
        assertFalse(OriginalFields.batchAccumulatorsFit(listOf(16_000_000L, 1L)))
        for (invalid in listOf(emptyList(), listOf(0L), listOf(-1L), listOf(Long.MAX_VALUE), listOf(1L, 1L, 1L, 1L)))
            assertFalse(OriginalFields.batchAccumulatorsFit(invalid))
        val bloom = listOf(167L * 111 * 3, 50L * 33 * 3, 17L * 11 * 3)
        assertEquals(44_088L, (bloom.sum() - bloom.max()) * Double.SIZE_BYTES)
        assertTrue(OriginalFields.batchAccumulatorsFit(bloom))
    }

    @Test fun realGridAt512KiBExtraPayloadBatchesAndTheNextGridSizeFallsBack() {
        for ((gridWidth, walks) in listOf(256 to 1, 257 to 3)) {
            val source = Raster(600, 300)
            var projects = 0
            val project = projection(1)
            val fields = OriginalFields.blurMany(source, List(3) { 1.0 / gridWidth }, 1, { false }) { rgb, out ->
                projects++; project(rgb, out)
            }
            assertEquals(3, fields.size)
            assertEquals(gridWidth, fields.first().width)
            assertEquals(walks, source.walks)
            assertEquals(walks * source.height, projects)
            assertEquals(walks, source.closes)
        }
    }

    @Test fun cancellationBeforeIoAfterProjectBetweenGridsAndBeforeFinishDoesNotReplay() {
        val preStopped = Raster(48, 32)
        assertThrows(CancellationException::class.java) {
            OriginalFields.blurMany(preStopped, radii, 3, { true }, projection(3))
        }
        assertEquals(0, preStopped.walks)
        for (mode in listOf("project", "between", "finish")) {
            val source = Raster(48, 32)
            var canceled = false
            var checks = 0
            var projects = 0
            if (mode == "finish") source.afterRows = { canceled = true }
            val project = projection(3)
            assertThrows(CancellationException::class.java) {
                OriginalFields.blurMany(source, radii, 3, {
                    checks++
                    canceled || (mode == "between" && checks == 4)
                }) { rgb, out ->
                    projects++; project(rgb, out)
                    if (mode == "project") canceled = true
                }
            }
            assertEquals(1, source.walks)
            assertEquals(1, source.closes)
            assertEquals(0, source.open)
            assertEquals(if (mode == "finish") source.height else 1, projects)
        }
    }

    @Test fun projectAndReadFailuresPropagateWithoutFallbackOrAnotherWalk() {
        for (failProject in listOf(true, false)) {
            val source = Raster(48, 32)
            if (!failProject) source.readFailureAt = 2
            val expected = IOException("synthetic projection failure")
            val project = projection(3)
            val error = assertThrows(IOException::class.java) {
                OriginalFields.blurMany(source, radii, 3, { false }) { rgb, out ->
                    if (failProject) throw expected
                    project(rgb, out)
                }
            }
            if (failProject) assertSame(expected, error)
            assertEquals(1, source.walks)
            assertEquals(1, source.closes)
            assertEquals(0, source.open)
        }
    }

    @Test fun closeFailureAfterEveryRowNeverReturnsFieldsOrFallsBack() {
        val source = Raster(48, 32).also { it.failClose = true }
        var projects = 0
        var returned = false
        val project = projection(3)
        assertThrows(IOException::class.java) {
            OriginalFields.blurMany(source, radii, 3, { false }) { rgb, out -> projects++; project(rgb, out) }
            returned = true
        }
        assertFalse(returned)
        assertEquals(1, source.walks)
        assertEquals(source.height, projects)
        assertEquals(1, source.closes)
    }

    @Test fun invalidArgumentsAndAllGridValidationRejectBeforeAnyIo() {
        for (invalid in listOf(emptyList(), List(4) { .1 }, listOf(.1, Double.NaN), listOf(.1, 0.0),
            listOf(-.1), listOf(Double.POSITIVE_INFINITY))) {
            val source = Raster(48, 32)
            assertThrows(IllegalArgumentException::class.java) { OriginalFields.blurMany(source, invalid, 3, { false }, projection(3)) }
            assertEquals(0, source.walks)
        }
        for (channels in listOf(0, 4)) {
            val source = Raster(48, 32)
            assertThrows(IllegalArgumentException::class.java) { OriginalFields.blurMany(source, radii, channels, { false }) { _, _ -> } }
            assertEquals(0, source.walks)
        }
        // The last grid would exceed the existing reduced-field limit; do not walk earlier grids.
        val tooLarge = Raster(2000, 2000)
        assertThrows(IllegalArgumentException::class.java) {
            OriginalFields.blurMany(tooLarge, listOf(.1, .0009), 3, { false }, projection(3))
        }
        assertEquals(0, tooLarge.walks)
        for ((width, height) in listOf(0 to 17, 17 to 0, -1 to 17, Int.MAX_VALUE to 1)) {
            val source = Raster(width, height)
            assertThrows(IllegalArgumentException::class.java) { OriginalFields.blurMany(source, radii, 3, { false }, projection(3)) }
            assertEquals(0, source.walks)
        }
    }

    @Test fun malformedRasterRowsAndIncompleteWalkFailForSingleAndBatch() {
        for (mode in listOf("short", "extra", "skip", "repeat", "negative", "length")) for (batch in listOf(false, true)) {
            val source = Raster(48, 32).also { it.mode = mode }
            assertThrows(IllegalArgumentException::class.java) {
                if (batch) OriginalFields.blurMany(source, radii, 3, { false }, projection(3))
                else OriginalFields.blur(source, radii.first(), 3, { false }, projection(3))
            }
            assertEquals(1, source.walks)
            assertEquals(1, source.closes)
            assertEquals(0, source.open)
        }
    }

    @Test fun batchRejectsNonFiniteInputProjectionAndProjectOutputBeforeReturningFields() {
        for (sourceNan in listOf(true, false)) {
            val source = Raster(48, 32).also { if (sourceNan) it.mode = "nan" }
            val project = projection(3)
            val invalidProject: (FloatArray, FloatArray) -> Unit = { rgb, out ->
                project(rgb, out)
                if (!sourceNan) out[0] = Float.POSITIVE_INFINITY
            }
            assertThrows(IllegalArgumentException::class.java) {
                OriginalFields.blurMany(source, radii, 3, { false }, invalidProject)
            }
            assertEquals(1, source.walks)
            assertEquals(1, source.closes)
            assertEquals(0, source.open)
        }
    }

    @Test fun singleGridStillRejectsNanAndShortPhysicalRasterAtTheExistingReadBoundary() {
        for (nan in listOf(true, false)) {
            val root = Files.createTempDirectory("single-field-source-failure").toFile()
            val file = File(root, "source.rgb")
            val bytes = ByteBuffer.allocate(4 * 4 * 3 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .apply { repeat(4 * 4 * 3) { putFloat(if (nan && it == 0) Float.NaN else .5f) } }.array()
            file.writeBytes(if (nan) bytes else bytes.copyOf(bytes.size - Float.SIZE_BYTES))
            val source = OriginalRasterFile(file, 4, 4)
            var projects = 0
            try {
                assertThrows(IOException::class.java) {
                    OriginalFields.reduce(source, 2, 2, 3, { false }) { rgb, out -> projects++; rgb.copyInto(out) }
                }
                assertEquals(0, projects)
                assertFalse(source.hasPendingOwners)
            } finally {
                source.closePending()
                assertTrue(file.delete())
                assertTrue(root.delete())
            }
        }
    }

    @Test fun singleGridInvalidProjectionIsRejectedByLinearImageConsumerWithoutChangingProducerBoundary() {
        val source = Raster(48, 32)
        var projects = 0
        val field = OriginalFields.reduce(source, 2, 2, 3, { false }) { _, out ->
            projects++; out.fill(Float.NaN)
        }
        // Single-grid fields were never a completion proof. Keep their original validation boundary
        // at the image consumer rather than introducing an extra full input scan in the producer.
        assertEquals(source.height, projects)
        assertEquals(1, source.closes)
        assertThrows(IllegalArgumentException::class.java) { field.reducedRgb() }
    }

    @Test fun singleGridInvalidProjectionCannotBecomeACompletedMappedRaster() {
        val root = Files.createTempDirectory("single-field-consumer-failure").toFile()
        val work = OriginalRasterWorkspace(File(root, "attempt"))
        val source = Raster(48, 32)
        try {
            val field = OriginalFields.reduce(source, 2, 2, 1, { false }) { _, out -> out.fill(Float.POSITIVE_INFINITY) }
            val fieldRow = FloatArray(source.width)
            assertThrows(IllegalArgumentException::class.java) {
                work.map(source, { false }) { y, rgb ->
                    field.row(y, fieldRow)
                    FloatArray(rgb.size) { fieldRow[it / 3] }
                }
            }
            assertFalse(work.hasPendingOwners)
            assertEquals(2, source.walks)
            assertEquals(2, source.closes)
        } finally {
            work.disposeConfirmed()
            assertTrue(root.delete())
        }
    }

    private fun compare(width: Int, height: Int, radii: List<Double>, channels: Int, expectedWalks: Int) {
        val separate = Raster(width, height)
        val baseline = radii.map { OriginalFields.blur(separate, it, channels, { false }, projection(channels)) }
        val source = Raster(width, height)
        var projects = 0
        val project = projection(channels)
        val fields = OriginalFields.blurMany(source, radii, channels, { false }) { rgb, out -> projects++; project(rgb, out) }
        assertEquals(radii.size, fields.size)
        assertEquals(radii.size, separate.walks)
        assertEquals(expectedWalks, source.walks)
        assertEquals(expectedWalks * height, projects)
        for (i in fields.indices) {
            val field = fields[i]
            assertEquals(baseline[i].width, field.width)
            assertEquals(baseline[i].height, field.height)
            val (reduced, restored) = canonical(width, height, field.width, field.height, channels, OriginalFilter.LINEAR)
            if (channels == 3) {
                val actual = field.reducedRgb().copyPixels()
                for (p in 0 until field.width * field.height) for (c in 0..2)
                    assertEquals(reduced[c][p].toRawBits(), actual[p * 3 + c].toRawBits())
            }
            assertRows(field, restored, width, height, channels)
            val oldRow = FloatArray(width * channels); val newRow = FloatArray(oldRow.size)
            for (y in 0 until height) {
                baseline[i].row(y, oldRow); field.row(y, newRow)
                assertTrue(oldRow.indices.all { oldRow[it].toRawBits() == newRow[it].toRawBits() })
            }
        }
    }

    private fun canonical(width: Int, height: Int, gridWidth: Int, gridHeight: Int, channels: Int,
        interpolation: OriginalFilter): Pair<Array<FloatArray>, Array<FloatArray>> {
        val projected = FloatArray(width * height * channels)
        val rgb = FloatArray(width * 3); val row = FloatArray(width * channels)
        val project = projection(channels)
        repeat(height) { y -> fill(y, rgb); project(rgb, row); row.copyInto(projected, y * row.size) }
        val reduced = Array(channels) { c ->
            StageClearBlur.resize(FloatPlane(width, height, FloatArray(width * height) { projected[it * channels + c] }),
                gridWidth, gridHeight, StageClearBlur.Filter.BOX).copyValues()
        }
        val restored = Array(channels) { c -> StageClearBlur.resize(FloatPlane(gridWidth, gridHeight, reduced[c]), width, height,
            if (interpolation == OriginalFilter.CUBIC) StageClearBlur.Filter.BICUBIC else StageClearBlur.Filter.BILINEAR).copyValues() }
        return reduced to restored
    }

    private fun assertRows(field: OriginalField, expected: Array<FloatArray>, width: Int, height: Int, channels: Int) {
        val row = FloatArray(width * channels)
        for (y in (0 until height).toList() + listOf(height - 1, 0, height / 2, 0)) {
            field.row(y, row)
            for (x in 0 until width) for (c in 0 until channels)
                if (expected[c][y * width + x].toRawBits() != row[x * channels + c].toRawBits())
                    fail("Field ${field.width}x${field.height} channel=$c row=$y x=$x differs from canonical bits")
        }
    }

    private fun projection(channels: Int): (FloatArray, FloatArray) -> Unit = { rgb, out ->
        if (channels == 3) out.indices.forEach { out[it] = rgb[it] * .73f + .17f }
        else out.indices.forEach { out[it] = rgb[it * 3] * .2126f + rgb[it * 3 + 1] * .7152f + rgb[it * 3 + 2] * .0722f }
    }

    private fun fill(y: Int, row: FloatArray) {
        row.indices.forEach { i -> row[i] = (((i * 73L + y * 113 + i / 7 * 19) % 257) - 41) / 19f }
        row[0] = -3.25f; row[row.lastIndex] = 32.75f + y * .01f
    }

    private class Raster(override val width: Int, override val height: Int) : OriginalRaster {
        var walks = 0; var closes = 0; var open = 0
        var mode = "normal"
        var failClose = false
        var readFailureAt = -1
        var afterRows: () -> Unit = {}
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            walks++; open++
            try {
                val row = FloatArray(width * 3 - if (mode == "length") 1 else 0)
                val count = height + when (mode) { "short" -> -1; "extra" -> 1; else -> 0 }
                repeat(count) { y ->
                    originalCheckpoint(stop)
                    if (y == readFailureAt) throw IOException("synthetic read failure")
                    row.indices.forEach { i -> row[i] = (((i * 73L + y * 113 + i / 7 * 19) % 257) - 41) / 19f }
                    row[0] = -3.25f; row[row.lastIndex] = 32.75f + y * .01f
                    if (mode == "nan") row[0] = Float.NaN
                    consume(when (mode) { "skip" -> y + 1; "repeat" -> 0; "negative" -> -1; else -> y }, row)
                }
                afterRows()
            } finally {
                closes++
                if (failClose) throw IOException("synthetic close failure")
                open--
            }
        }
    }
}
