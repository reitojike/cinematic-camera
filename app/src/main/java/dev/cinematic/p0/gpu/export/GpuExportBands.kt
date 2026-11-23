package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.rawcapture.SaveAuthorityBoundary

import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.raw.original.originalCheckpoint
import dev.cinematic.p0.raw.original.validateOriginalSize
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Two borrowed buffers: the GL caller owns one, the reader may prepare only the next band. */
internal object GpuExportBands {
    const val HEIGHT = 256
    private data class Band(val origin: Int, val rows: Int, val data: Buffer)

    fun forEach(input: OriginalRaster, height: Int = HEIGHT, stop: () -> Boolean, timings: GpuExportWorkTimings? = null,
        forceReaderBoundaries: Boolean = false,
        consume: (originY: Int, rows: Int, data: Buffer) -> Unit) {
        validateOriginalSize(input.width, input.height)
        require(height == HEIGHT || height == 64) { "Export band height must be explicit: 256 or test-only 64" }
        SaveAuthorityBoundary.durable { originalCheckpoint(stop) }
        val rgb16 = (input as? OriginalRasterFile)?.takeIf { it.isRgb16 }
        val bytesPerPixel = if (rgb16 != null) 6 else 16
        val free = ArrayBlockingQueue<Buffer>(2)
        repeat(2) {
            val bytes = ByteBuffer.allocateDirect(input.width * minOf(height, input.height) * bytesPerPixel)
                .order(ByteOrder.nativeOrder())
            free.add(if (rgb16 != null) bytes else bytes.asFloatBuffer())
        }
        val ready = ArrayBlockingQueue<Band>(2)
        val cancelled = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val readerFailure = AtomicReference<Throwable?>()
        val readerStop = { cancelled.get() || stop() }
        fun borrow(): Buffer {
            while (true) {
                originalCheckpoint(readerStop)
                free.poll(50, TimeUnit.MILLISECONDS)?.let { return it.apply { clear() } }
            }
        }
        val readerTimings = timings?.fork()
        val reader = Thread({
            try {
                if (forceReaderBoundaries) SaveAuthorityBoundary.durable { originalCheckpoint(readerStop) }
                var buffer = borrow()
                var expectedY = 0
                var origin = 0
                fun checkRow(y: Int) {
                    originalCheckpoint(readerStop)
                    check(y == expectedY && y < input.height) { "Original rows must be contiguous and ordered" }
                }
                fun acceptRow() {
                    expectedY++
                    if (expectedY - origin == height || expectedY == input.height) {
                        SaveAuthorityBoundary.durable { originalCheckpoint(readerStop) }
                        buffer.flip()
                        check(ready.offer(Band(origin, expectedY - origin, buffer)))
                        origin = expectedY
                        // Wait before the reader starts the next row.
                        if (expectedY < input.height) {
                            buffer = gpuWork(readerTimings, GpuExportDetail.INPUT_FREE_WAIT) { borrow() }
                            if (forceReaderBoundaries) SaveAuthorityBoundary.durable { originalCheckpoint(readerStop) }
                        }
                    }
                }
                val rgbaRow = if (rgb16 == null) FloatArray(input.width * 4) else null
                gpuWork(readerTimings, GpuExportDetail.READER_TOTAL) {
                    if (rgb16 != null) {
                        rgb16.rgb16Rows(readerStop, { buffer as ByteBuffer }) { y ->
                            checkRow(y)
                            acceptRow()
                        }
                    } else {
                        checkNotNull(rgbaRow)
                        input.rows(readerStop) { y, row ->
                            checkRow(y)
                            require(row.size == input.width * 3 && row.all { it.isFinite() })
                            for (x in 0 until input.width) {
                                val rgb = x * 3; val rgba = x * 4
                                rgbaRow[rgba] = row[rgb]; rgbaRow[rgba + 1] = row[rgb + 1]
                                rgbaRow[rgba + 2] = row[rgb + 2]; rgbaRow[rgba + 3] = 1f
                            }
                            (buffer as FloatBuffer).put(rgbaRow)
                            acceptRow()
                        }
                    }
                }
                check(expectedY == input.height && origin == expectedY) { "Original raster ended early" }
                SaveAuthorityBoundary.durable { originalCheckpoint(readerStop) }
            } catch (failure: Throwable) { readerFailure.set(failure) }
            finally { done.set(true) } // rows() EOF checks and resource close have completed.
        }, "gpu-export-band-reader")
        reader.start()
        var failure: Throwable? = null
        var interrupted = false
        var deliveredRows = 0
        try {
            while (true) {
                originalCheckpoint(stop)
                // done publishes EOF/close and guarantees that no more bands can be enqueued.
                // Check the queue too: a timed-out poll may race with the final offer.
                if (done.get() && ready.isEmpty()) {
                    readerFailure.get()?.let { throw it }
                    check(deliveredRows == input.height) { "Export rows ended early" }
                    break
                }
                val band = gpuWork(timings, GpuExportDetail.INPUT_WAIT) { ready.poll(50, TimeUnit.MILLISECONDS) } ?: continue
                SaveAuthorityBoundary.durable { originalCheckpoint(stop) }
                check(band.origin == deliveredRows && band.rows in 1..height)
                val data = band.data
                val samples = if (data is ByteBuffer) data.asShortBuffer() else data
                check(samples.remaining() == input.width * band.rows * (if (rgb16 != null) 3 else 4))
                consume(band.origin, band.rows, samples) // GL stays on the caller.
                deliveredRows += band.rows
                SaveAuthorityBoundary.durable { originalCheckpoint(stop) }
                check(free.offer(band.data)) // Return only after the complete GL callback.
            }
        } catch (caught: Throwable) {
            failure = caught
            if (caught is InterruptedException) interrupted = true
        } finally {
            if (failure != null) cancelled.set(true)
            // Like the existing GL Future drain, never abandon a reader still owning the source.
            while (reader.isAlive) {
                try { reader.join() }
                catch (_: InterruptedException) { interrupted = true; cancelled.set(true) }
            }
            timings?.mergeInput(readerTimings)
            val readError = readerFailure.get()
            if (readError != null && readError !== failure) {
                val internalStopOnly = cancelled.get() && readError is CancellationException && readError.suppressed.isEmpty()
                if (!internalStopOnly) {
                    if (failure == null) failure = readError else failure.addSuppressed(readError)
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt()
                if (failure == null) failure = CancellationException("Export band reader interrupted")
            }
        }
        failure?.let { throw it }
        SaveAuthorityBoundary.durable { originalCheckpoint(stop) }
    }
}
