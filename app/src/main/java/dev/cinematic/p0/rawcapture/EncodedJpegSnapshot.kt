package dev.cinematic.p0.rawcapture

import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** One immutable encoded snapshot, local to inspection/decode. Never exposes its mutable backing array. */
internal class EncodedJpegSnapshot private constructor(
    private val bytes: ByteArray,
    val fingerprint: OriginalFingerprint,
) {
    val size get() = bytes.size
    // ByteArrayInputStream.transferTo may pass its backing array to an arbitrary OutputStream.
    // Copy into reader-owned buffers instead, keeping this snapshot immutable for the next codec read.
    fun openRead(): InputStream = SnapshotStream(bytes)
    fun source(): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
    fun hasCompleteMarkers() = size >= 4 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[size - 2] == 0xff.toByte() && bytes[size - 1] == 0xd9.toByte()

    private class SnapshotStream(private val bytes: ByteArray) : InputStream() {
        private val size get() = bytes.size
        private var position = 0
        private var marked = 0
        override fun read(): Int = if (position < size) bytes[position++].toInt() and 0xff else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            if (position >= size) return -1
            val count = minOf(length, size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        override fun skip(count: Long): Long {
            val skipped = minOf(maxOf(0L, count), (size - position).toLong()).toInt()
            position += skipped
            return skipped.toLong()
        }
        override fun available() = size - position
        override fun markSupported() = true
        override fun mark(readlimit: Int) { marked = position }
        override fun reset() { position = marked }
    }
    companion object {
        private const val CHUNK = 64 * 1024
        fun read(openReadOnly: () -> InputStream, expectedBytes: Long? = null,
            shouldStop: () -> Boolean): EncodedJpegSnapshot {
            if (expectedBytes != null && expectedBytes !in 1..JpegOutputDescription.MAX_JPEG_BYTES) {
                throw IOException("Invalid expected JPEG length")
            }
            fun checkpoint() { if (shouldStop()) throw IOException("JPEG snapshot stopped") }
            checkpoint()
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = openReadOnly().use { input ->
                if (expectedBytes != null) {
                    val exact = ByteArray(expectedBytes.toInt())
                    var offset = 0
                    while (offset < exact.size) {
                        checkpoint()
                        val length = minOf(CHUNK, exact.size - offset)
                        val count = input.read(exact, offset, length)
                        if (count < 0) throw IOException("JPEG shorter than expected")
                        if (count == 0 || count > length) throw IOException("Invalid JPEG read")
                        digest.update(exact, offset, count)
                        offset += count
                    }
                    checkpoint()
                    if (input.read() != -1) throw IOException("JPEG longer than expected")
                    exact
                } else {
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(CHUNK)
                    while (true) {
                        checkpoint()
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (count == 0 || count > chunk.size || count > JpegOutputDescription.MAX_JPEG_BYTES - buffer.size()) {
                            throw IOException("Invalid JPEG length")
                        }
                        buffer.write(chunk, 0, count)
                        digest.update(chunk, 0, count)
                    }
                    if (buffer.size() == 0) throw IOException("Empty JPEG")
                    buffer.toByteArray()
                }
            }
            checkpoint() // A failed close or cancellation never returns a success snapshot.
            val fingerprint = OriginalFingerprint(bytes.size.toLong(), digest.digest().joinToString("") { "%02x".format(it) })
            return EncodedJpegSnapshot(bytes, fingerprint)
        }
    }
}
