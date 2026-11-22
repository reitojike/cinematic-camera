package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.rawcapture.SaveAuthorityBoundary
import org.junit.Assert.*
import org.junit.Test
import java.nio.ShortBuffer
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
        GpuExportBands.forEach(raster(3, 513), stop = { false }) { origin, rows, data ->
            val rgba = data as FloatBuffer
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

    @Test fun callerCapabilityReachesReaderWithoutInheritingIoThreadObserver() {
        for (active in listOf(false, true)) {
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            val forcedAtRows = mutableListOf<Int>()
            val forcedReader = AtomicInteger()
            val source = raster(4, 257) { forcedAtRows += forcedReader.get() }
            val stop = {
                if (Thread.currentThread().name == "gpu-export-band-reader" && SaveAuthorityBoundary.force)
                    forcedReader.incrementAndGet()
                false
            }
            try {
                val io = {
                    val forceReaderBoundaries = SaveAuthorityBoundary.isObserving
                    pool.submit {
                        assertFalse(SaveAuthorityBoundary.isObserving)
                        GpuExportBands.forEach(source, stop = { stop() },
                            forceReaderBoundaries = forceReaderBoundaries) { _, _, _ -> }
                    }.get(10, TimeUnit.SECONDS)
                }
                if (active) SaveAuthorityBoundary.observing({ true }, io) else io()
                assertEquals(if (active) 2 else 1, forcedAtRows[0])
                assertEquals(if (active) 261 else 259, forcedAtRows[256])
            } finally {
                pool.shutdownNow()
            }
        }
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

    @Test fun rgb16BandsAndLutMatchAllCpuSampleBits() {
        val file = Files.createTempFile("synthetic-rgb16", ".bin").toFile()
        try {
            val input = OriginalRasterFile(file, 257, 257, 2)
            val bytes = ByteBuffer.allocate(input.bytes.toInt()).order(ByteOrder.LITTLE_ENDIAN)
            repeat(input.width * input.height * 3) { bytes.putShort(it.toShort()) }
            file.writeBytes(bytes.array())

            val lut = GpuExportRgb16.lutBits()
            assertTrue(lut.isDirect)
            assertEquals(ByteOrder.nativeOrder(), lut.order())
            assertEquals(65_536, lut.remaining())
            val cpuBits = IntArray(input.width * input.height * 3)
            var index = 0
            input.rows({ false }) { _, row ->
                for (sample in row) {
                    val bits = sample.toRawBits()
                    assertEquals("LUT sample ${index and 65535}", bits, lut.get(index and 65535))
                    cpuBits[index++] = bits
                }
            }
            assertEquals(cpuBits.size, index)

            for (bandHeight in listOf(64, 256)) {
                var sampleIndex = 0
                GpuExportBands.forEach(input, bandHeight, { false }) { origin, rows, data ->
                    val rgb = data as ShortBuffer
                    assertTrue(rgb.isDirect)
                    assertEquals(ByteOrder.nativeOrder(), rgb.order())
                    assertEquals(origin * input.width * 3, sampleIndex)
                    assertEquals(minOf(bandHeight, input.height - origin), rows)
                    assertEquals(input.width * rows * 3, rgb.remaining())
                    while (rgb.hasRemaining()) {
                        val value = rgb.get().toInt() and 65535
                        assertEquals(sampleIndex and 65535, value)
                        assertEquals(cpuBits[sampleIndex++], lut.get(value))
                    }
                }
                assertEquals(cpuBits.size, sampleIndex)
                assertFalse(input.hasPendingOwners)
            }
        } finally { file.delete() }
    }

    @Test fun rgb16ReaderChecksShortReadFinalSizeAndCancellation() {
        val file = Files.createTempFile("synthetic-rgb16-errors", ".bin").toFile()
        try {
            val input = OriginalRasterFile(file, 3, 2, 2)
            val row = ByteBuffer.allocateDirect(18)
            fun read(stop: () -> Boolean = { false }, afterRow: (Int) -> Unit) =
                input.rgb16Rows(stop, { row.clear(); row }, afterRow)

            file.writeBytes(ByteArray(36))
            val short = assertThrows(IOException::class.java) {
                read { y -> if (y == 0) java.io.RandomAccessFile(file, "rw").use { it.setLength(19) } }
            }
            assertEquals("Short original raster", short.message)
            assertFalse(input.hasPendingOwners)

            file.writeBytes(ByteArray(36))
            val changed = assertThrows(IOException::class.java) {
                read { y -> if (y == 1) file.appendBytes(byteArrayOf(0)) }
            }
            assertEquals("Original raster changed", changed.message)
            assertFalse(input.hasPendingOwners)

            file.writeBytes(ByteArray(36))
            var stopped = false
            assertThrows(CancellationException::class.java) {
                read({ stopped }) { y -> assertEquals(0, y); stopped = true }
            }
            assertFalse(input.hasPendingOwners)
        } finally { file.delete() }
    }

    @Test fun rgb16BandCancellationAndConsumerFailureDrainTheReader() {
        val file = Files.createTempFile("synthetic-rgb16-band-errors", ".bin").toFile()
        try {
            val input = OriginalRasterFile(file, 3, 193, 2)
            file.writeBytes(ByteArray(input.bytes.toInt()))
            val cancelled = AtomicBoolean()
            var calls = 0
            assertThrows(CancellationException::class.java) {
                GpuExportBands.forEach(input, 64, cancelled::get) { origin, rows, data ->
                    assertEquals(0, origin)
                    assertEquals(64, rows)
                    assertTrue(data is ShortBuffer)
                    calls++
                    cancelled.set(true)
                }
            }
            assertEquals(1, calls)
            assertFalse(input.hasPendingOwners)

            calls = 0
            val failure = IOException("synthetic RGB16 upload failure")
            assertSame(failure, assertThrows(IOException::class.java) {
                GpuExportBands.forEach(input, 64, { false }) { _, _, _ ->
                    calls++
                    throw failure
                }
            })
            assertEquals(1, calls)
            assertFalse(input.hasPendingOwners)
        } finally { file.delete() }
    }

    @Test fun rgb16BandsReportInputTimingAndHonorForcedReaderBoundaries() {
        val file = Files.createTempFile("synthetic-rgb16-band-timing", ".bin").toFile()
        try {
            val input = OriginalRasterFile(file, 3, 130, 2)
            file.writeBytes(ByteArray(input.bytes.toInt()))
            val caller = Thread.currentThread()
            for (force in listOf(false, true)) {
                val clock = java.util.concurrent.atomic.AtomicLong()
                val timing = GpuExportWorkTimings { clock.incrementAndGet() }
                var checkpointsBeforeOpen = 0
                var opened = false
                val bands = mutableListOf<Pair<Int, Int>>()
                GpuExportBands.forEach(input, 64, stop = {
                    if (Thread.currentThread() !== caller) {
                        if (input.hasPendingOwners) opened = true
                        if (!opened) checkpointsBeforeOpen++
                    }
                    false
                }, timings = timing, forceReaderBoundaries = force) { origin, rows, data ->
                    assertTrue(data is ShortBuffer)
                    assertEquals(input.width * rows * 3, data.remaining())
                    bands += origin to rows
                }
                assertEquals(listOf(0 to 64, 64 to 64, 128 to 2), bands)
                assertTrue(opened)
                assertEquals(if (force) 2 else 1, checkpointsBeforeOpen)
                val read = checkNotNull(timing.sample(GpuExportDetail.INPUT_READ_CONVERT))
                assertTrue(read.completed)
                assertTrue(checkNotNull(read.nanos) > 0)
                assertNull(timing.sample(GpuExportDetail.READER_TOTAL))
                assertFalse(input.hasPendingOwners)
            }
        } finally { file.delete() }
    }

    @Test fun floatNanStillFails() {
        val file = Files.createTempFile("synthetic-float32", ".bin").toFile()
        try {
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

    @Test fun timingEnabledKeepsRowsAndDoesNotExposeReaderInternals() {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val timing = GpuExportWorkTimings { clock.incrementAndGet() }
        val origins = mutableListOf<Int>()
        GpuExportBands.forEach(raster(4, 130), 64, { false }, timing) { origin, rows, data ->
            val rgba = data as FloatBuffer
            origins += origin
            assertEquals(4 * rows * 4, rgba.remaining())
            assertEquals(origin.toFloat(), rgba.get(), 0f)
        }
        assertEquals(listOf(0, 64, 128), origins)
        assertNotNull(timing.sample(GpuExportDetail.INPUT_WAIT)?.nanos)
        assertNotNull(timing.sample(GpuExportDetail.INPUT_READ_CONVERT)?.nanos)
        assertNull(timing.sample(GpuExportDetail.READER_TOTAL))
    }

    @Test fun brokenTimingClockDoesNotMaskConsumerFailureOrAbandonReader() {
        val timing = GpuExportWorkTimings { throw IllegalStateException("clock") }
        val closed = AtomicBoolean()
        val source = object : OriginalRaster {
            override val width = 4; override val height = 513
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                try { raster(width, height).rows(stop, consume) } finally { closed.set(true) }
            }
        }
        val failure = IOException("consumer")
        assertSame(failure, assertThrows(IOException::class.java) {
            GpuExportBands.forEach(source, stop = { false }, timings = timing) { _, _, _ -> throw failure }
        })
        assertTrue(closed.get())
        assertNull(timing.sample(GpuExportDetail.INPUT_WAIT)?.nanos)
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
