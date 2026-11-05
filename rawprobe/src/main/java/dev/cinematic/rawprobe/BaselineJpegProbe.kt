package dev.cinematic.rawprobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import org.json.JSONObject

internal object NativeBaselineProbe {
    init { System.loadLibrary("cinematic_dng") }
    external fun decode(fd: Int, signal: CancellationSignal, observer: BaselineObserver): BaselineFrame
    external fun decodeLarge(fd: Int, signal: CancellationSignal, observer: BaselineObserver): BaselineFrame
}

internal interface BaselineObserver {
    fun onStage(name: String)
    fun allocate(width: Int, height: Int): Bitmap
}

/** Allocation is owned by Kotlin before native packing; cancellation cannot orphan its Bitmap. */
internal class BaselineAllocation(private val stage: (String) -> Unit = {}, private val large: Boolean = false) : BaselineObserver, AutoCloseable {
    var bitmap: Bitmap? = null
        private set
    override fun onStage(name: String) = stage(name)
    override fun allocate(width: Int, height: Int): Bitmap {
        check(bitmap == null)
        val edge = if (large) 8192 else 128
        require(width in 1..edge && height in 1..edge && width.toLong() * height <= if (large) 24_000_000 else 4096)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }
    override fun close() { bitmap?.recycle(); bitmap = null }
}

/** RGB16 oracle is diagnostic-only and bounded to at most 24KiB, never an original-size product array. */
internal class BaselineFrame(val bitmap: Bitmap, val geometry: String, val rgb16: ByteArray)

/** One synchronous diagnostic attempt. Not a production renderer, receipt, queue or publication contract. */
internal class BaselineJpegAttempt(
    private val source: File,
    private val output: File,
    private val signal: CancellationSignal,
    private val stage: (String) -> Unit = {},
    private val wrap: (OutputStream) -> OutputStream = { it },
    private val encode: (Bitmap, OutputStream) -> Boolean = { image, sink -> image.compress(Bitmap.CompressFormat.JPEG, 95, sink) },
    private val openReader: (File) -> InputStream = { Files.newInputStream(it.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS) },
    private val closeDescriptor: (ParcelFileDescriptor) -> Unit = { it.close() },
    private val large: Boolean = false,
) {
    private val allocation = BaselineAllocation(stage, large)
    private val byteLimit = if (large) 64L * 1024 * 1024 else 128L * 1024
    private var writer: OutputStream? = null
    private var closeAttempted = false
    private var nativeActive = false
    private var descriptor: ParcelFileDescriptor? = null
    private var descriptorCloseAttempted = false
    private var reader: InputStream? = null
    private var readerCloseAttempted = false
    private var started = false
    var receipt: JSONObject? = null
        private set
    val released: Boolean get() = !nativeActive && writer == null && reader == null && descriptor == null && allocation.bitmap == null

    fun closePendingReader() {
        val pending = reader ?: return
        readerCloseAttempted = true
        pending.close()
        reader = null
    }

    fun closePendingDescriptor() {
        val pending = descriptor ?: return
        descriptorCloseAttempted = true
        closeDescriptor(pending)
        descriptor = null
    }

    private fun <T> reading(file: File, body: (InputStream) -> T): T {
        check(reader == null)
        reader = openReader(file); readerCloseAttempted = false
        var primary: Throwable? = null
        try { return body(requireNotNull(reader)) }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { closePendingReader() } catch (failure: Throwable) {
                if (primary == null) throw failure
                if (primary !== failure) primary.addSuppressed(failure)
            }
        }
    }

    private fun sourceDigest(): String {
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS) || source.length() !in 1..byteLimit) throw IOException("Bounded synthetic source required")
        return reading(source) { digestStream(it, byteLimit) }
    }

    fun closePendingWriter() {
        val pending = writer ?: return
        closeAttempted = true
        pending.close() // Retain exact owner on failure; never re-render to obtain a close acknowledgement.
        writer = null
    }

    fun execute(): JSONObject {
        check(!started) { "One attempt cannot be re-rendered" }; started = true
        var primary: Throwable? = null
        try {
            val before = sourceDigest()
            signal.throwIfCanceled()
            descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            val frame = try {
                nativeActive = true
                if (large) NativeBaselineProbe.decodeLarge(requireNotNull(descriptor).fd, signal, allocation)
                else NativeBaselineProbe.decode(requireNotNull(descriptor).fd, signal, allocation)
            } finally { nativeActive = false; closePendingDescriptor() }
            val geometry = validateFrame(frame, large)
            val w = geometry.getInt("width"); val h = geometry.getInt("height")
            signal.throwIfCanceled()
            val parent = requireNotNull(output.parentFile)
            if (!parent.isDirectory || parent.canonicalFile != parent.absoluteFile || Files.isSymbolicLink(parent.toPath())) throw IOException("Private directory redirected")
            writer = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            writer = wrap(requireNotNull(writer))
            var count = 0L
            val writtenDigest = MessageDigest.getInstance("SHA-256")
            var writeFailure: Throwable? = null
            val checkedSink = object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    try {
                        signal.throwIfCanceled()
                        if (count + length > byteLimit) throw IOException("Diagnostic JPEG byte limit")
                        requireNotNull(writer).write(bytes, offset, length)
                        writtenDigest.update(bytes, offset, length)
                        count += length
                    } catch (failure: Throwable) { writeFailure = failure; throw failure }
                }
            }
            stage("jpeg_begin")
            val compressed = encode(frame.bitmap, checkedSink)
            writeFailure?.let { throw it }
            if (!compressed) throw IOException("JPEG encoder failed")
            signal.throwIfCanceled()
            requireNotNull(writer).flush()
            signal.throwIfCanceled()
            closePendingWriter()
            stage("jpeg_closed")
            // Do not overlap the encoder Bitmap and original-size codec readback Bitmap.
            allocation.close()
            val expectedHash = writtenDigest.digest()
            val result = if (large) readLargeOutput(w, h, count, expectedHash) else readSmallOutput(w, h, count, expectedHash)
            signal.throwIfCanceled()
            stage("source_recheck")
            if (sourceDigest() != before) throw IOException("Source changed")
            allocation.close()
            signal.throwIfCanceled()
            check(released)
            return JSONObject().put("scope", "DIAGNOSTIC_BASELINE_NO_R7_NO_STAGE_CLEAR").put("geometry", geometry)
                .put("sourceSha256", before).put("outputSha256", result.first).put("bytes", count).put("largeDiagnostic", large)
                .put("width", w).put("height", h).put("pixelOrientation", 1).put("exifReaderOrientation", result.second).put("profile", "AS_SHOT_PRELIMINARY")
                .put("face", "NOT_RUN").put("exposure", "NOT_RUN").also { receipt = it }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            allocation.close()
            if (reader != null && !readerCloseAttempted) {
                try { closePendingReader() } catch (failure: Throwable) {
                    if (primary == null) throw failure
                    if (primary !== failure) primary.addSuppressed(failure)
                }
            }
            if (descriptor != null && !descriptorCloseAttempted) {
                try { closePendingDescriptor() } catch (failure: Throwable) {
                    if (primary == null) throw failure
                    if (primary !== failure) primary.addSuppressed(failure)
                }
            }
            if (writer != null && !closeAttempted) {
                try { closePendingWriter() } catch (failure: Throwable) {
                    if (primary == null) throw failure
                    if (primary !== failure) primary.addSuppressed(failure)
                }
            }
        }
    }

    private fun verifyBitmap(decoded: Bitmap?, options: BitmapFactory.Options, w: Int, h: Int) {
        if (decoded == null) throw IOException("JPEG codec failed")
        try {
            if (decoded.width != w || decoded.height != h || options.outMimeType != "image/jpeg" || decoded.colorSpace?.isSrgb != true) throw IOException("JPEG geometry/color mismatch")
        } finally { decoded.recycle() }
    }

    private fun readSmallOutput(w: Int, h: Int, count: Long, expected: ByteArray): Pair<String, Int> {
        val bytes = readOutput()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        if (!expected.contentEquals(hash) || bytes.size.toLong() != count || bytes.size < 4 ||
            bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() || bytes[bytes.size - 2] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) throw IOException("JPEG transport incomplete")
        val options = BitmapFactory.Options().apply { inScaled = false; inSampleSize = 1 }
        verifyBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options), options, w, h)
        val orientation = ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        if (orientation !in 0..1) throw IOException("JPEG geometry/color mismatch")
        return hex(hash) to orientation
    }

    private fun readLargeOutput(w: Int, h: Int, count: Long, expected: ByteArray): Pair<String, Int> {
        stage("readback_hash")
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var first = -1; var second = -1; var previous = -1; var last = -1
        reading(output) { input ->
            val chunk = ByteArray(4096)
            while (true) {
                signal.throwIfCanceled()
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0 || size + n > byteLimit) throw IOException("Diagnostic readback limit")
                for (i in 0 until minOf(n, 2)) {
                    if (size + i == 0L) first = chunk[i].toInt() and 255
                    if (size + i == 1L) second = chunk[i].toInt() and 255
                }
                previous = if (n >= 2) chunk[n - 2].toInt() and 255 else last
                last = chunk[n - 1].toInt() and 255
                size += n; hash.update(chunk, 0, n)
            }
        }
        val actual = hash.digest()
        if (size != count || size < 4 || first != 255 || second != 216 || previous != 255 || last != 217 || !expected.contentEquals(actual)) throw IOException("JPEG transport incomplete")
        signal.throwIfCanceled()
        stage("readback_decode")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        reading(output) { input -> BitmapFactory.decodeStream(input, null, bounds) }
        if (bounds.outWidth != w || bounds.outHeight != h || bounds.outMimeType != "image/jpeg") throw IOException("JPEG geometry/color mismatch")
        val options = BitmapFactory.Options().apply { inScaled = false; inSampleSize = 1 }
        reading(output) { input -> verifyBitmap(BitmapFactory.decodeStream(input, null, options), options, w, h) }
        signal.throwIfCanceled()
        val orientation = reading(output) { input -> ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED) }
        if (orientation !in 0..1) throw IOException("JPEG geometry/color mismatch")
        return hex(actual) to orientation
    }

    private fun readOutput(): ByteArray {
        val result = ByteArrayOutputStream()
        reading(output) { input ->
            val chunk = ByteArray(4096)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (n == 0 || result.size() + n > 128 * 1024) throw IOException("Diagnostic readback limit")
                result.write(chunk, 0, n)
            }
        }
        return result.toByteArray()
    }

    companion object {
        fun validateFrame(frame: BaselineFrame, large: Boolean = false): JSONObject {
            val g = JSONObject(frame.geometry)
            val w = g.getInt("width"); val h = g.getInt("height")
            val edge = if (large) 8192 else 128
            check(w in 1..edge && h in 1..edge && w.toLong() * h <= (if (large) 24_000_000 else 4096) &&
                g.getString("scope") == "DIAGNOSTIC_BASELINE_NO_R7_NO_STAGE_CLEAR" &&
                g.getString("policy") == "probe-integer-standard-crop-v1" && g.getInt("halfSize") == 0 && !g.getBoolean("resized") &&
                g.getLong("packedPixels") == w.toLong() * h && frame.rgb16.size == (if (large) 0 else w * h * 6) &&
                g.getString("oracle") == (if (large) "NONE_LARGE_DIAGNOSTIC" else "RGB16_SMALL") &&
                frame.bitmap.width == w && frame.bitmap.height == h && frame.bitmap.colorSpace?.isSrgb == true)
            return g
        }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun digest(file: File, limit: Long = 128 * 1024L): String {
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || file.length() !in 1..limit) throw IOException("Bounded synthetic source required")
            Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                return digestStream(input, limit)
            }
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun digestStream(input: InputStream, limit: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(4096)
            var count = 0
            while (true) { val n = input.read(chunk); if (n < 0) break; if (n == 0 || count + n > limit) throw IOException("Source read limit"); digest.update(chunk, 0, n); count += n }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
