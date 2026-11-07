package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.*
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class OriginalR7ColorFusionTest {
    private class Source(override val width: Int, override val height: Int, val bright: Boolean = false) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(width * 3)
            repeat(height) { y ->
                originalCheckpoint(stop)
                row.indices.forEach { i -> row[i] = if (bright) 1.2f else (((i * 37L + y * 19) % 4096) / 2048f) - .05f }
                consume(y, row)
            }
        }
    }
    private fun bits(raster: OriginalRaster): IntArray {
        val result = IntArray(raster.width * raster.height * 3)
        raster.rows({ false }) { y, row -> row.indices.forEach { result[y * row.size + it] = row[it].toRawBits() } }
        return result
    }
    @Test fun rowKernelsKeepEveryFloatBoundaryAndBorrowedInputUnchanged() {
        for (width in listOf(1, 73, 1024)) {
            val row = FloatArray(width * 3) { i -> when (i % 11) {
                0 -> -0f; 1 -> 0f; 2 -> 8f; else -> ((i * 53L % 4096) / 2048f) - .2f
            } }
            val before = row.map { it.toRawBits() }.toIntArray()
            val expected = R7Base.skin(R7Base.crisp(R7Base.vibrance(LinearSrgbImage(width, 1, row)))).copyPixels()
            val actual = originalR7ColorRow(width, row, { false })
            assertArrayEquals(expected.map { it.toRawBits() }.toIntArray(), actual.map { it.toRawBits() }.toIntArray())
            assertArrayEquals(before, row.map { it.toRawBits() }.toIntArray())
        }
    }
    @Test fun fullPipelineMatchesSeparatePassesIncludingFaceCutAndExternalRoi() {
        for ((width, height) in listOf(48 to 32, 116 to 174, 1 to 17, 17 to 1)) {
            for (strength in listOf(0.0, .5, 1.0)) {
                val root = Files.createTempDirectory("r7-fusion-bits").toFile()
                val outputs = mutableListOf<Pair<IntArray, Double>>()
                try {
                    for (fused in listOf(false, true)) {
                        val work = OriginalRasterWorkspace(root.resolve("attempt-$fused"))
                        try {
                            val caller = Thread.currentThread()
                            var nextRow = 0
                            val traceEvents = mutableListOf<OriginalStage>()
                            val renderer = OriginalStageClear24(work,
                                OriginalStageTrace { stage, _, complete -> assertTrue(complete); traceEvents += stage },
                                parallelOrtonWorkers = 4, parallelCharacterWorkers = 2,
                                parallelFoundationWorkers = 2, fuseR7ColorRows = fused)
                            val result = renderer.render(Source(width, height, bright = strength == 1.0), .23, strength,
                                foundation = OriginalMask { y, row ->
                                    assertSame(caller, Thread.currentThread()); assertEquals(nextRow++, y)
                                    row.fill(if (strength == 1.0) 0f else (y % 5) / 4f)
                                }, faces = OriginalMask { _, row -> row.fill(1f) })
                            assertEquals(height, nextRow)
                            assertEquals(fused, traceEvents.contains(OriginalStage.R7_COLOR_FUSED))
                            assertEquals(!fused, traceEvents.contains(OriginalStage.R7_VIBRANCE))
                            outputs += bits(result.raster) to result.faceCutFactor
                            assertFalse(work.hasPendingOwners)
                        } finally { work.closePending(); work.disposeConfirmed() }
                    }
                    assertArrayEquals(outputs[0].first, outputs[1].first)
                    assertEquals(outputs[0].second, outputs[1].second, 0.0)
                    if (width * height >= 50 && strength == 1.0) assertTrue(outputs[1].second < 1.0)
                } finally { check(root.delete()) }
            }
        }
    }
    @Test fun cancellationAndExceptionsAtEveryFusedBoundaryDoNotReturnRows() {
        for (boundary in 1..4) for (fail in listOf(false, true)) {
            var count = 0
            val error = IOException("synthetic fused boundary failure")
            val action = {
                originalR7ColorRow(17, FloatArray(51) { .3f }) {
                    if (++count == boundary) { if (fail) throw error else true } else false
                }
            }
            if (fail) assertSame(error, assertThrows(IOException::class.java) { action() })
            else assertThrows(CancellationException::class.java) { action() }
            assertEquals(boundary, count)
        }
        assertThrows(IllegalArgumentException::class.java) {
            originalR7ColorRow(1, floatArrayOf(Float.NaN, 0f, 0f), { false })
        }
    }
    @Test fun fusedWorkerFailureAndCancellationRetainCleanupContract() {
        for (fail in listOf(false, true)) {
            val root = Files.createTempDirectory("r7-fusion-stop").toFile()
            val work = OriginalRasterWorkspace(root.resolve("attempt"))
            val armed = AtomicBoolean(false)
            val error = IOException("synthetic fused worker failure")
            try {
                val renderer = OriginalStageClear24(work, OriginalStageTrace { stage, _, complete ->
                    if (stage == OriginalStage.R7_FOUNDATION && complete) armed.set(true)
                }, fuseR7ColorRows = true)
                val action = {
                    renderer.render(Source(73, 41), .23, stop = {
                        if (armed.get() && Thread.currentThread().name == "original-r7-row") {
                            if (fail) throw error else true
                        } else false
                    })
                }
                if (fail) assertSame(error, assertThrows(IOException::class.java) { action() })
                else assertThrows(CancellationException::class.java) { action() }
                assertTrue(armed.get())
                assertFalse(work.hasPendingOwners)
            } finally { work.closePending(); work.disposeConfirmed(); check(root.delete()) }
        }
    }
    @Test fun intermediateRasterObserverIsRejectedBeforeAnyWork() {
        val root = Files.createTempDirectory("r7-fusion-observer").toFile()
        val work = OriginalRasterWorkspace(root.resolve("attempt"))
        try {
            assertThrows(IllegalArgumentException::class.java) {
                OriginalStageClear24(work, fuseR7ColorRows = true).render(Source(12, 8), .23,
                    stage = { _, _ -> fail("No callback may run") })
            }
            assertEquals(0, work.reservedBytes)
            assertFalse(work.hasPendingOwners)
        } finally { work.disposeConfirmed(); check(root.delete()) }
    }
}
