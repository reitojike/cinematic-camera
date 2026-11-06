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
    override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
        check(!hasPendingOwners)
        owners.using(FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel ->
            if (channel.size() != bytes) throw IOException("Original raster length differs")
            val buffer = ByteBuffer.allocate(width * 3 * bytesPerChannel).order(ByteOrder.LITTLE_ENDIAN)
            val row = FloatArray(width * 3)
            repeat(height) { y ->
                originalCheckpoint(stop)
                buffer.clear()
                while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) throw IOException("Short original raster")
                buffer.flip()
                if (bytesPerChannel == 4) buffer.asFloatBuffer().get(row)
                else repeat(row.size) { row[it] = (buffer.short.toInt() and 65535) / 65535f }
                if (!row.all { it.isFinite() }) throw IOException("Non-finite original raster")
                consume(y, row)
            }
            if (channel.size() != bytes || channel.position() != bytes) throw IOException("Original raster changed")
        }
    }
}

/** One create-only attempt directory. Failed attempts remain private until their owner confirms cleanup. */
internal class OriginalRasterWorkspace(val directory: File, private val byteBudget: Long = 2L * 1024 * 1024 * 1024) {
    private val owned = linkedMapOf<OriginalRasterFile, Long>()
    private val writers = OriginalCloseOwners()
    private var next = 0
    init { require(byteBudget > 0); check(!directory.exists() && directory.mkdir()) }
    val reservedBytes get() = owned.values.sum()
    val hasPendingOwners get() = writers.hasPending || owned.keys.any { it.hasPendingOwners }
    fun closePending() {
        var failure: Throwable? = null
        fun release(action: () -> Unit) { try { action() } catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) } }
        release { writers.releaseAll() }
        owned.keys.forEach { raster -> release { raster.closePending() } }
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
        map(OriginalParallelRows(input, transform), stop) { _, row -> row }

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
) : OriginalRaster {
    override val width get() = input.width
    override val height get() = input.height

    override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
        val executor = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "original-r7-row").apply { isDaemon = true }
        }
        val pending = ArrayDeque<Pair<Int, Future<FloatArray>>>(4)
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
                if (pending.size == 4) drainOne()
            }
            while (pending.isNotEmpty()) drainOne()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (failure == null) executor.shutdown() else {
                pending.forEach { (_, future) -> future.cancel(true) }
                executor.shutdownNow()
            }
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS))
                    throw IOException("Original row workers did not stop")
            } catch (cleanup: Throwable) {
                if (cleanup is InterruptedException) Thread.currentThread().interrupt()
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }
}
