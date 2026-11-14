package dev.cinematic.p0.raw.original

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/** Shared raster/owner contracts retained after CPU renderer retirement. */
class OriginalSharedSafetyTest {
    private class MemoryRaster(override val width: Int, override val height: Int, private val values: FloatArray) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(width * 3)
            repeat(height) { y -> originalCheckpoint(stop); values.copyInto(row, 0, y * row.size, (y + 1) * row.size); consume(y, row) }
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
