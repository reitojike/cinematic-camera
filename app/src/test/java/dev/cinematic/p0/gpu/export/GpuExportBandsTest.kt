package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.raw.original.OriginalRaster
import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException

class GpuExportBandsTest {
    @Test fun orderedRowsBecomeRgbaAndReuseTheBoundedBufferWithShortTail() {
        val bands = mutableListOf<Pair<Int, Int>>()
        var first: FloatBuffer? = null
        GpuExportBands.forEach(raster(3, 513), stop = { false }) { origin, rows, rgba ->
            if (first == null) first = rgba else assertSame(first, rgba)
            assertTrue(rgba.isDirect)
            assertEquals(3 * rows * 4, rgba.remaining())
            for (y in 0 until rows) for (x in 0 until 3) {
                assertEquals((origin + y).toFloat(), rgba.get(), 0f)
                assertEquals(x.toFloat(), rgba.get(), 0f)
                assertEquals(.25f, rgba.get(), 0f)
                assertEquals(1f, rgba.get(), 0f)
            }
            bands += origin to rows
        }
        assertEquals(listOf(0 to 256, 256 to 256, 512 to 1), bands)
        assertEquals(3 * 256 * 4, first!!.capacity())
    }

    @Test fun explicit64RowBandsKeepOriginalOrder() {
        val origins = mutableListOf<Int>()
        GpuExportBands.forEach(raster(4, 130), 64, { false }) { origin, _, _ -> origins += origin }
        assertEquals(listOf(0, 64, 128), origins)
    }

    @Test fun cancellationAfterABandDoesNotReadTheNextRow() {
        var reads = 0; var cancelled = false
        val source = raster(4, 513) { reads++ }
        assertThrows(CancellationException::class.java) {
            GpuExportBands.forEach(source, stop = { cancelled }) { _, _, _ -> cancelled = true }
        }
        assertEquals(256, reads)
    }

    @Test fun cancellationBeforeStartingDoesNotOpenTheRaster() {
        var reads = 0
        assertThrows(CancellationException::class.java) {
            GpuExportBands.forEach(raster(4, 10) { reads++ }, stop = { true }) { _, _, _ -> fail() }
        }
        assertEquals(0, reads)
    }

    @Test fun callbackFailureIsNotReplayedOrConvertedToSuccess() {
        var reads = 0; var calls = 0
        val failure = IllegalStateException("synthetic upload failure")
        val observed = assertThrows(IllegalStateException::class.java) {
            GpuExportBands.forEach(raster(4, 513) { reads++ }, stop = { false }) { _, _, _ -> calls++; throw failure }
        }
        assertSame(failure, observed); assertEquals(1, calls); assertEquals(256, reads)
    }

    @Test fun rejectsReorderedOrMissingRowsAndImplicitBandSize() {
        for (indices in listOf(listOf(1, 0), listOf(0))) {
            val input = object : OriginalRaster {
                override val width = 4; override val height = 2
                override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                    indices.forEach { consume(it, FloatArray(12)) }
                }
            }
            assertThrows(IllegalStateException::class.java) {
                GpuExportBands.forEach(input, stop = { false }) { _, _, _ -> }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GpuExportBands.forEach(raster(4, 2), 128, { false }) { _, _, _ -> }
        }
    }

    private fun raster(w: Int, h: Int, beforeRow: () -> Unit = {}) = object : OriginalRaster {
        override val width = w; override val height = h
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(w * 3)
            repeat(h) { y ->
                beforeRow()
                for (x in 0 until w) { row[x * 3] = y.toFloat(); row[x * 3 + 1] = x.toFloat(); row[x * 3 + 2] = .25f }
                consume(y, row)
            }
        }
    }
}
