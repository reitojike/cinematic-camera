package dev.cinematic.p0.raw.original

import java.io.File
import java.io.IOException
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Borrowed row buffers must not escape a callback. No full-frame Java RGB allocation. */
internal interface OriginalRaster {
    val width: Int
    val height: Int
    fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit)
}

internal fun originalCheckpoint(stop: () -> Boolean) {
    if (stop()) throw CancellationException("Original rendering stopped")
}

internal fun validateOriginalSize(width: Int, height: Int) {
    require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 24_000_000)
}

/** A close failure remains owned and retryable; actual acknowledgement, not a stop flag, releases it. */
internal class OriginalCloseOwners {
    private val pending = linkedSetOf<Closeable>()
    val hasPending get() = pending.isNotEmpty()
    fun <T : Closeable, R> using(resource: T, action: (T) -> R): R {
        check(pending.add(resource))
        var failure: Throwable? = null
        try { return action(resource) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { resource.close(); pending.remove(resource) }
            catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }
    fun releaseAll() {
        var failure: Throwable? = null
        pending.toList().forEach { resource ->
            try { resource.close(); pending.remove(resource) }
            catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
}

/** A checked private linear-sRGB raster. Native RGB16 and intermediate Float32 are little endian. */
internal class OriginalRasterFile internal constructor(
    val file: File, override val width: Int, override val height: Int, private val bytesPerChannel: Int = 4,
) : OriginalRaster {
    private val owners = OriginalCloseOwners()
    val hasPendingOwners get() = owners.hasPending
    fun closePending() = owners.releaseAll()
    init { validateOriginalSize(width, height); require(bytesPerChannel == 2 || bytesPerChannel == 4) }
    val bytes get() = width.toLong() * height * 3 * bytesPerChannel
    // Unsigned 16-bit samples divided by 65535f are finite by construction.
    val isRgb16 get() = bytesPerChannel == 2
    override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
        withRows(stop, setup = {
            ByteBuffer.allocate(width * 3 * bytesPerChannel).order(ByteOrder.LITTLE_ENDIAN) to FloatArray(width * 3)
        }) { channel, y, (buffer, row) ->
            buffer.clear()
            readRow(channel, buffer)
            buffer.flip()
            if (bytesPerChannel == 4) buffer.asFloatBuffer().get(row)
            else repeat(row.size) { row[it] = (buffer.short.toInt() and 65535) / 65535f }
            if (!isRgb16 && !row.all { it.isFinite() }) throw IOException("Non-finite original raster")
            consume(y, row)
        }
    }

    /** Appends one file row to the borrowed direct band before calling consume. */
    fun rgb16Rows(stop: () -> Boolean, destination: () -> ByteBuffer, consume: (Int) -> Unit) {
        require(isRgb16)
        withRows(stop, setup = { Unit }) { channel, y, _ ->
            val buffer = destination()
            val start = buffer.position()
            val end = start + width * 6
            val limit = buffer.limit()
            require(buffer.isDirect && end <= limit)
            buffer.limit(end)
            try {
                readRow(channel, buffer)
            } finally {
                buffer.limit(limit)
            }
            // The file is little endian and GL consumes native-order unsigned shorts.
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
                for (offset in start until end step 2) {
                    val low = buffer.get(offset)
                    buffer.put(offset, buffer.get(offset + 1))
                    buffer.put(offset + 1, low)
                }
            }
            consume(y)
        }
    }

    /** Reads only [startRow, endRow) x [startCol, endCol) from an RGB16 file into a borrowed row buffer. */
    fun readRgb16Window(
        startRow: Int,
        endRow: Int,
        startCol: Int,
        endCol: Int,
        stop: () -> Boolean,
        consume: (Int, FloatArray) -> Unit,
    ) {
        require(isRgb16)
        require(startRow in 0 until endRow && endRow <= height)
        require(startCol in 0 until endCol && endCol <= width)
        check(!hasPendingOwners)
        owners.using(FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel ->
            if (channel.size() != bytes) throw IOException("Original raster length differs")
            val sliceWidth = endCol - startCol
            val rawBytes = ByteArray(sliceWidth * 6)
            val buffer = ByteBuffer.wrap(rawBytes)
            val row = FloatArray(sliceWidth * 3)
            val lut = u16ToFloat
            val rowStride = width.toLong() * 6L
            val colOffset = startCol.toLong() * 6L
            for (y in startRow until endRow) {
                originalCheckpoint(stop)
                var pos = y.toLong() * rowStride + colOffset
                buffer.clear()
                while (buffer.hasRemaining()) {
                    val n = channel.read(buffer, pos)
                    if (n <= 0) throw IOException("Short original raster")
                    pos += n
                }
                var byteIndex = 0
                for (i in 0 until row.size) {
                    val sample = (rawBytes[byteIndex].toInt() and 0xFF) or
                        ((rawBytes[byteIndex + 1].toInt() and 0xFF) shl 8)
                    row[i] = lut[sample]
                    byteIndex += 2
                }
                consume(y, row)
            }
            if (channel.size() != bytes) throw IOException("Original raster changed")
        }
    }

    private fun <T> withRows(stop: () -> Boolean, setup: () -> T, consume: (FileChannel, Int, T) -> Unit) {
        check(!hasPendingOwners)
        owners.using(FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel ->
            if (channel.size() != bytes) throw IOException("Original raster length differs")
            val state = setup()
            repeat(height) { y ->
                originalCheckpoint(stop)
                consume(channel, y, state)
            }
            if (channel.size() != bytes || channel.position() != bytes) throw IOException("Original raster changed")
        }
    }

    private fun readRow(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) throw IOException("Short original raster")
    }

    private companion object {
        // Every uint16 sample divides identically to (sample and 65535) / 65535f.
        val u16ToFloat: FloatArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
            FloatArray(65536) { it / 65535f }
        }
    }
}

/** One create-only attempt directory. Failed attempts remain private until their owner confirms cleanup. */
internal class OriginalRasterWorkspace(val directory: File, private val byteBudget: Long = 2L * 1024 * 1024 * 1024) {
    private val owned = linkedMapOf<OriginalRasterFile, Long>()
    private val writers = OriginalCloseOwners()
    private val workers = OriginalCloseOwners()
    private var next = 0
    init { require(byteBudget > 0); check(!directory.exists() && directory.mkdir()) }
    val reservedBytes get() = owned.values.sum()
    val hasPendingOwners get() = writers.hasPending || workers.hasPending || owned.keys.any { it.hasPendingOwners }
    fun closePending() {
        var failure: Throwable? = null
        fun release(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) } }
        release { writers.releaseAll() }
        owned.keys.forEach { raster -> release { raster.closePending() } }
        release { workers.releaseAll() }
        failure?.let { throw it }
    }

    fun map(input: OriginalRaster, stop: () -> Boolean,
        transform: (Int, FloatArray) -> FloatArray): OriginalRasterFile {
        validateOriginalSize(input.width, input.height)
        originalCheckpoint(stop)
        val bytes = input.width.toLong() * input.height * 12
        if (bytes > byteBudget - reservedBytes || directory.usableSpace < bytes + 256L * 1024 * 1024)
            throw IOException("Original workspace budget exhausted")
        val file = File(directory, "raster-${next++}.f32")
        val result = OriginalRasterFile(file, input.width, input.height)
        owned[result] = bytes // Retain the attempted path even after a failed write/close.
        writers.using(FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { channel ->
            val buffer = ByteBuffer.allocate(input.width * 12).order(ByteOrder.LITTLE_ENDIAN)
            var rows = 0
            input.rows(stop) { y, row ->
                check(y == rows)
                val output = transform(y, row)
                require(output.size == input.width * 3 && output.all { it.isFinite() })
                buffer.clear(); buffer.asFloatBuffer().put(output)
                while (buffer.hasRemaining()) {
                    originalCheckpoint(stop)
                    if (channel.write(buffer) <= 0) throw IOException("Short raster write")
                }
                rows++
            }
            check(rows == input.height && channel.size() == bytes)
        }
        originalCheckpoint(stop)
        return result
    }

    /** Opt-in for independent row transforms. Two workers and four in-flight rows bound CPU and RAM. */
    fun mapParallelRows(input: OriginalRaster, stop: () -> Boolean,
        transform: (Int, FloatArray) -> FloatArray): OriginalRasterFile =
        mapParallelRows(input, stop, 2, transform)

    /** Bounded worker count for independent row transforms; reads and writes stay ordered. */
    fun mapParallelRows(input: OriginalRaster, stop: () -> Boolean, workers: Int,
        transform: (Int, FloatArray) -> FloatArray): OriginalRasterFile {
        require(workers in 2..4)
        return map(OriginalParallelRows(input, transform, workers, this.workers), stop) { _, row -> row }
    }

    /** Call only after the last reader's rows() has returned and acknowledged close. */
    fun release(raster: OriginalRasterFile) {
        check(owned.containsKey(raster))
        check(!raster.hasPendingOwners && !writers.hasPending)
        java.nio.file.Files.deleteIfExists(raster.file.toPath())
        owned.remove(raster)
    }

    /** Only a successfully closed attempt may dispose its known files. No recursive or foreign deletion. */
    fun disposeConfirmed() {
        check(!hasPendingOwners)
        owned.keys.toList().forEach(::release)
        check(directory.delete())
    }
}

/** Reads and writes stay ordered on the caller; only owned row copies reach the workers. */
private class OriginalParallelRows(
    private val input: OriginalRaster,
    private val transform: (Int, FloatArray) -> FloatArray,
    private val workers: Int,
    private val owners: OriginalCloseOwners,
) : OriginalRaster {
    override val width get() = input.width
    override val height get() = input.height

    override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
        val executor = Executors.newFixedThreadPool(workers) { task ->
            Thread(task, "original-r7-row").apply { isDaemon = true }
        }
        val pendingLimit = workers * 2
        val pending = ArrayDeque<Pair<Int, Future<FloatArray>>>(pendingLimit)
        owners.using(OriginalWorkerOwner(executor)) {
            var failure: Throwable? = null
            try {
                fun drainOne() {
                    val (y, future) = pending.removeFirst()
                    val output = try { future.get() }
                    catch (error: ExecutionException) { throw error.cause ?: error }
                    originalCheckpoint(stop)
                    consume(y, output)
                }
                input.rows(stop) { y, row ->
                    originalCheckpoint(stop)
                    val owned = row.copyOf()
                    pending.addLast(y to executor.submit<FloatArray> { transform(y, owned) })
                    if (pending.size == pendingLimit) drainOne()
                }
                while (pending.isNotEmpty()) drainOne()
            } catch (error: Throwable) {
                failure = error
                if (error is InterruptedException) Thread.currentThread().interrupt()
                throw error
            } finally {
                if (failure == null) executor.shutdown() else {
                    pending.forEach { (_, future) -> future.cancel(true) }
                    executor.shutdownNow()
                }
            }
        }
    }
}

/** A failed wait remains owned by the workspace and can be retried by its save owner. */
private class OriginalWorkerOwner(private val executor: ExecutorService) : Closeable {
    override fun close() {
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow()
                throw IOException("Original row workers did not stop")
            }
        } catch (interrupted: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }
}
