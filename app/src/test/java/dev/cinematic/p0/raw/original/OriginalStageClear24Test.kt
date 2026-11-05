package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OriginalStageClear24Test {
    private class MemoryRaster(override val width: Int, override val height: Int, private val values: FloatArray) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(width * 3)
            repeat(height) { y -> originalCheckpoint(stop); values.copyInto(row, 0, y * row.size, (y + 1) * row.size); consume(y, row) }
        }
    }
    private fun pixels(raster: OriginalRaster): FloatArray = FloatArray(raster.width * raster.height * 3).also { out ->
        raster.rows({ false }) { y, row -> row.copyInto(out, y * row.size) }
    }
    private fun compare(expected: FloatArray, actual: FloatArray, label: String) {
        assertEquals(expected.size, actual.size)
        val error = expected.indices.maxOf { abs(expected[it] - actual[it]).toDouble() }
        assertTrue("$label maximum error $error", error <= 0.00003)
    }

    @Test fun diskRowsMatchR7AndAllLookStagesWithoutPerTileStatistics() {
        for ((width, height) in listOf(48 to 32, 174 to 116, 116 to 174, 96 to 288, 288 to 96, 1 to 17, 17 to 1)) for (strength in listOf(0.0, .5, 1.0)) {
            val rgb = FloatArray(width * height * 3) { i -> (((i * 37L + i / width * 19) % 4096) / 2048f) - .05f }
            val roi = FloatArray(width * height) { p -> if (p % 7 < 3) .4f else 0f }
            val face = FloatArray(width * height) { p -> if ((p / width) in height / 4..height / 2 && p % width in width / 4..width / 2) 1f else 0f }
            val source = LinearSrgbImage(width, height, rgb)
            val expectedBase = R7Base.stages(source, FloatPlane(width, height, roi), .23)
            val expected = StageClearLook.renderFromBase(expectedBase.last(), FaceMask(width, height, face), strength)
            val root = Files.createTempDirectory("original-row-test").toFile()
            val work = OriginalRasterWorkspace(java.io.File(root, "attempt"))
            try {
                var baseStage = 0
                val actual = OriginalStageClear24(work).render(MemoryRaster(width, height, rgb), .23, strength,
                    OriginalMask { y, row -> roi.copyInto(row, 0, y * width, (y + 1) * width) },
                    OriginalMask { y, row -> face.copyInto(row, 0, y * width, (y + 1) * width) },
                    stage = { name, raster -> if (name.startsWith("r7_")) compare(expectedBase[baseStage++].copyPixels(), pixels(raster), name) })
                assertEquals(6, baseStage)
                compare(expected.image.copyPixels(), pixels(actual.raster), "$width/$height/$strength")
                assertEquals(expected.faceCutFactor, actual.faceCutFactor, 1e-6)
            } finally { work.disposeConfirmed(); check(root.delete()) }
        }
    }

    @Test fun quantilesMatchSortedFloatValuesIncludingNegativeAndAdjacentRanks() {
        val values = floatArrayOf(-9f, -1f, -.01f, -0f, 0f, .1f, .3f, 1f, 2.9f, 300f)
        val samples = OriginalSamples { c -> values.reversed().forEach { c.accept(it) } }
        val actual = OriginalQuantiles.evaluate(samples, { false }, .5, .92)
        for ((i, q) in listOf(.5, .92).withIndex()) {
            val sorted = values.sorted(); val rank = (values.size - 1) * q
            val expected = sorted[floor(rank).toInt()].toDouble() +
                (sorted[ceil(rank).toInt()].toDouble() - sorted[floor(rank).toInt()].toDouble()) * (rank - floor(rank))
            assertEquals(expected, actual[i], 0.0)
        }
    }

    @Test fun tracePreservesAllPixelBitsAndFaceCountBoundaryStillRunsTheSameGuard() {
        val width = 48; val height = 32
        val rgb = FloatArray(width * height * 3) { 1.2f }
        val roi = FloatPlane(width, height, FloatArray(width * height))
        val base = R7Base.render(LinearSrgbImage(width, height, rgb), roi, .23)
        for (count in listOf(0, 49, 50)) {
            val weights = FloatArray(width * height) { if (it < count) 1f else .5f }
            val expected = StageClearLook.renderFromBase(base, FaceMask(width, height, weights), .5)
            var first: FloatArray? = null
            for (enabled in listOf(false, true)) {
                val events = mutableListOf<Pair<OriginalStage, Boolean>>()
                val trace = if (enabled) OriginalStageTrace { stage, _, complete -> events += stage to complete } else null
                val root = Files.createTempDirectory("original-trace-bits").toFile()
                val work = OriginalRasterWorkspace(java.io.File(root, "attempt"))
                try {
                    val actual = OriginalStageClear24(work, trace).render(MemoryRaster(width, height, rgb), .23, .5,
                        faces = OriginalMask { y, row -> weights.copyInto(row, 0, y * width, (y + 1) * width) })
                    val output = pixels(actual.raster)
                    compare(expected.image.copyPixels(), output, "face count $count")
                    assertEquals(expected.faceCutFactor, actual.faceCutFactor, 1e-6)
                    if (count == 50) assertTrue(actual.faceCutFactor < 1.0)
                    if (first == null) first = output else output.indices.forEach {
                        assertEquals("trace must not alter sample $it", first!![it].toRawBits(), output[it].toRawBits())
                    }
                    if (enabled) {
                        assertTrue(events.all { it.second })
                        assertEquals(count == 50, events.any { it.first == OriginalStage.FACE_PERCENTILE })
                    } else assertTrue(events.isEmpty())
                } finally { work.disposeConfirmed(); check(root.delete()) }
            }
        }
    }

    @Test fun corruptLayerRgbStillFailsAtTheNextReadWithoutReturningOutput() {
        for (short in listOf(false, true)) {
            val width = 16; val height = 12
            val root = Files.createTempDirectory("original-layer-fault").toFile()
            val work = OriginalRasterWorkspace(java.io.File(root, "attempt"))
            val events = mutableListOf<Pair<OriginalStage, Boolean>>()
            val trace = OriginalStageTrace { stage, _, complete -> events += stage to complete }
            try {
                assertThrows(java.io.IOException::class.java) {
                    OriginalStageClear24(work, trace).render(MemoryRaster(width, height,
                        FloatArray(width * height * 3) { .3f }), .23, .5, stage = { name, raster ->
                        if (name == "character_cap") java.io.RandomAccessFile(raster.file, "rw").use {
                            if (short) it.setLength(0) else it.write(byteArrayOf(0, 0, 0xc0.toByte(), 0x7f))
                        }
                    })
                }
                assertTrue(events.contains(OriginalStage.FACE_COUNT to true))
                assertTrue(events.contains(OriginalStage.SHOULDER to false))
                assertFalse(work.hasPendingOwners)
            } finally { work.disposeConfirmed(); check(root.delete()) }
        }
    }

    @Test fun cancellationAndBudgetFailureDoNotReturnPartialRasterAsSuccess() {
        val root = Files.createTempDirectory("original-row-fault").toFile()
        val source = MemoryRaster(12, 8, FloatArray(12 * 8 * 3) { .2f })
        val work = OriginalRasterWorkspace(java.io.File(root, "attempt"), 100)
        try {
            assertThrows(java.io.IOException::class.java) { work.map(source, { false }) { _, row -> row.copyOf() } }
            assertEquals(0, work.reservedBytes)
            assertThrows(java.util.concurrent.CancellationException::class.java) { work.map(source, { true }) { _, row -> row.copyOf() } }
            assertThrows(IllegalArgumentException::class.java) { validateOriginalSize(6001, 4000) }
        } finally { work.disposeConfirmed(); check(root.delete()) }
    }

    @Test fun failedCloseStaysOwnedAndDoesNotHidePrimaryFailure() {
        val owners = OriginalCloseOwners()
        var calls = 0
        val resource = java.io.Closeable { if (calls++ == 0) throw java.io.IOException("Synthetic close failure") }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            owners.using(resource) { throw IllegalArgumentException("Synthetic processing failure") }
        }
        assertTrue(owners.hasPending)
        assertEquals(1, failure.suppressed.size)
        owners.releaseAll()
        assertFalse(owners.hasPending)
        assertEquals(2, calls)
    }
}
