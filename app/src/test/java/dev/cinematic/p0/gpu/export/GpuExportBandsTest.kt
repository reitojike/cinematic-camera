package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.raw.original.OriginalRaster
import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.cinematic.p0.raw.original.OriginalRasterFile
import java.nio.file.Files
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CancellationException

class GpuExportBandsTest {
    @Test fun orderedRowsBecomeRgbaAndReuseTheBoundedBufferWithShortTail() {
        val bands = mutableListOf<Pair<Int, Int>>()
        val buffers = Collections.newSetFromMap(IdentityHashMap<FloatBuffer, Boolean>())
        GpuExportBands.forEach(raster(3, 513), stop = { false }) { origin, rows, rgba ->
            buffers += rgba
            assertTrue(buffers.size <= 2)
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
        buffers.forEach { assertEquals(3 * 256 * 4, it.capacity()) }
    }

    @Test fun explicit64RowBandsKeepOriginalOrder() {
        val origins = mutableListOf<Int>()
        GpuExportBands.forEach(raster(4, 130), 64, { false }) { origin, _, _ -> origins += origin }
        assertEquals(listOf(0, 64, 128), origins)
    }

    @Test fun cancellationAfterABandReadsAtMostOneAdditionalBand() {
        var reads = 0; var cancelled = false
        val source = raster(4, 513) { reads++ }
        assertThrows(CancellationException::class.java) {
            GpuExportBands.forEach(source, stop = { cancelled }) { _, _, _ -> cancelled = true }
        }
        assertTrue("reads=$reads", reads in 256..512)
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
        assertSame(failure, observed); assertEquals(1, calls); assertTrue("reads=$reads", reads in 256..512)
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

    @Test fun readerRunsAheadByOnlyOneBandAndJoinsBeforeFailureReturns() {
        val reads = AtomicInteger()
        val closed = AtomicBoolean()
        val second = CountDownLatch(1)
        val source = object : OriginalRaster {
            override val width = 4; override val height = 1024
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                try {
                    raster(width, height) {
                        if (reads.incrementAndGet() == 512) second.countDown()
                    }.rows(stop, consume)
                } finally { closed.set(true) }
            }
        }
        val caller = Thread.currentThread()
        val failure = IOException("synthetic consumer")
        assertSame(failure, assertThrows(IOException::class.java) {
            GpuExportBands.forEach(source, stop = { false }) { _, _, _ ->
                assertSame(caller, Thread.currentThread())
                assertTrue(second.await(5, TimeUnit.SECONDS))
                assertEquals(512, reads.get())
                throw failure
            }
        })
        assertEquals(512, reads.get())
        assertTrue(closed.get())
    }

    @Test fun eofFailureAfterLastBandAndCloseFailureAreNotLost() {
        val eof = IOException("synthetic EOF close")
        val source = object : OriginalRaster {
            override val width = 4; override val height = 1
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                try { consume(0, FloatArray(12)) } finally { throw eof }
            }
        }
        assertSame(eof, assertThrows(IOException::class.java) {
            GpuExportBands.forEach(source, stop = { false }) { _, _, _ -> }
        })
        val consumer = IllegalStateException("consumer")
        assertSame(consumer, assertThrows(IllegalStateException::class.java) {
            GpuExportBands.forEach(source, stop = { false }) { _, _, _ -> throw consumer }
        })
        assertTrue(consumer.suppressed.any { it === eof })
    }

    @Test fun callerInterruptDrainsReaderBeforeRestoringInterrupt() {
        val closed = AtomicBoolean()
        val source = object : OriginalRaster {
            override val width = 4; override val height = 1024
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                try { raster(width, height).rows(stop, consume) } finally { closed.set(true) }
            }
        }
        try {
            assertThrows(InterruptedException::class.java) {
                GpuExportBands.forEach(source, stop = { false }) { _, _, _ -> Thread.currentThread().interrupt() }
            }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(closed.get())
        } finally { Thread.interrupted() }
    }

    @Test fun rgb16AllSampleValuesKeepExactBitsButFloatNanStillFails() {
        val file = Files.createTempFile("synthetic-rgb16", ".bin").toFile()
        try {
            val bytes = ByteBuffer.allocate(256 * 256 * 3 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(256 * 256 * 3) { bytes.putShort(it.toShort()) }
            file.writeBytes(bytes.array())
            var sample = 0
            val input = OriginalRasterFile(file, 256, 256, 2)
            GpuExportBands.forEach(input, stop = { false }) { _, _, rgba ->
                while (rgba.hasRemaining()) {
                    repeat(3) { assertEquals(((sample++ and 65535) / 65535f).toRawBits(), rgba.get().toRawBits()) }
                    assertEquals(1f.toRawBits(), rgba.get().toRawBits())
                }
            }
            assertEquals(256 * 256 * 3, sample)
            assertFalse(input.hasPendingOwners)
            file.writeBytes(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).putFloat(0f).putFloat(0f).array())
            assertThrows(IOException::class.java) {
                GpuExportBands.forEach(OriginalRasterFile(file, 1, 1), stop = { false }) { _, _, _ -> fail() }
            }
            val nan = object : OriginalRaster {
                override val width = 1; override val height = 1
                override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) = consume(0, floatArrayOf(Float.NaN, 0f, 0f))
            }
            assertThrows(IllegalArgumentException::class.java) {
                GpuExportBands.forEach(nan, stop = { false }) { _, _, _ -> fail() }
            }
        } finally { file.delete() }
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
