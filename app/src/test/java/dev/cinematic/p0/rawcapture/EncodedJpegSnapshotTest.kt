package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class EncodedJpegSnapshotTest {
    private fun fingerprint(bytes: ByteArray) = OriginalFingerprint(bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private class Tracked(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        var closeFailure: IOException? = null
        var readFailure: IOException? = null
        var zeroRead = false
        var largestRead = 0
        var afterRead: () -> Unit = {}
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            largestRead = maxOf(largestRead, length)
            readFailure?.let { throw it }
            if (zeroRead) return 0
            return super.read(buffer, offset, minOf(length, 37)).also { afterRead() }
        }
        override fun close() { closeFailure?.let { throw it }; closed = true; super.close() }
    }

    @Test fun knownAndUnknownLengthsReadTheSameCompleteBytesThroughShortChunks() {
        val bytes = ByteArray(131077) { (it * 31).toByte() }
        listOf(null, bytes.size.toLong()).forEach { length ->
            val input = Tracked(bytes)
            val snapshot = EncodedJpegSnapshot.read({ input }, length) { false }
            assertTrue(input.closed)
            assertTrue(input.largestRead <= 64 * 1024)
            assertEquals(bytes.size, snapshot.size)
            assertEquals(fingerprint(bytes), snapshot.fingerprint)
            assertArrayEquals(bytes, snapshot.openRead().use { it.readBytes() })
        }
    }

    @Test fun invalidExpectedLengthsNeverOpenTheSource() {
        listOf(-1L, 0L, JpegOutputDescription.MAX_JPEG_BYTES + 1, Long.MAX_VALUE).forEach { length ->
            var opened = false
            assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ opened = true; error("Must not open") }, length) { false } }
            assertFalse(opened)
        }
    }

    @Test fun shorterAndLongerSourcesFailAndCloseIncludingAnExtraZeroByte() {
        listOf(byteArrayOf(1), byteArrayOf(1, 2, 0)).forEach { bytes ->
            val input = Tracked(bytes)
            assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ input }, 2) { false } }
            assertTrue(input.closed)
        }
    }

    @Test fun zeroReadIsNotEofAndDoesNotSpin() {
        listOf(null, 2L).forEach { length ->
            val input = Tracked(byteArrayOf(1, 2)).apply { zeroRead = true }
            assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ input }, length) { false } }
            assertTrue(input.closed)
        }
    }

    @Test fun readAndOpenFailuresPropagateWithoutSuccess() {
        val openFailure = IOException("Synthetic open failure")
        assertSame(openFailure, assertThrows(IOException::class.java) {
            EncodedJpegSnapshot.read({ throw openFailure }, 2) { false }
        })
        listOf(null, 2L).forEach { length ->
            val failure = IOException("Synthetic read failure")
            val input = Tracked(byteArrayOf(1, 2)).apply { readFailure = failure }
            assertSame(failure, assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ input }, length) { false } })
            assertTrue(input.closed)
        }
    }

    @Test fun failedCloseRemainsOwnedUntilActualAcknowledgement() {
        listOf(null, 2L).forEach { length ->
            val failure = IOException("Synthetic close failure")
            val input = Tracked(byteArrayOf(1, 2)).apply { closeFailure = failure }
            val owner = DngIoResources()
            assertSame(failure, assertThrows(IOException::class.java) {
                EncodedJpegSnapshot.read({ owner.input(input) }, length) { false }
            })
            assertTrue(owner.hasOpenResources)
            assertFalse(input.closed)
            input.closeFailure = null
            assertNull(owner.releaseAll())
            assertFalse(owner.hasOpenResources)
            assertTrue(input.closed)
        }
    }

    @Test fun cancellationBeforeOpenAndBetweenChunksCannotDeliverASnapshot() {
        var opened = false
        assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ opened = true; error("Must not open") }, 2) { true } }
        assertFalse(opened)
        listOf(null, 100L).forEach { length ->
            var stopped = false
            val input = Tracked(ByteArray(100)).apply { afterRead = { stopped = true } }
            assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ input }, length) { stopped } }
            assertTrue(input.closed)
        }
    }

    @Test fun unknownLengthRejectsEmptyAndOversizedSourcesAndClosesThem() {
        val empty = Tracked(byteArrayOf())
        assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ empty }) { false } }
        assertTrue(empty.closed)
        var closed = false
        val oversized = object : InputStream() {
            var remaining = JpegOutputDescription.MAX_JPEG_BYTES + 1
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
                remaining -= count
                return count
            }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { EncodedJpegSnapshot.read({ oversized }) { false } }
        assertTrue(closed)
    }

    @Test fun laterSourceOrReaderMutationCannotChangeTheSnapshotOrItsHash() {
        val source = byteArrayOf(-1, -40, 1, -1, -39)
        val original = source.clone()
        val snapshot = EncodedJpegSnapshot.read({ ByteArrayInputStream(source) }, source.size.toLong()) { false }
        source.fill(0)
        snapshot.openRead().use { it.readBytes() }.fill(0)
        assertEquals(fingerprint(original), snapshot.fingerprint)
        assertTrue(snapshot.hasCompleteMarkers())
        assertArrayEquals(original, snapshot.openRead().use { it.readBytes() })
        assertFalse(EncodedJpegSnapshot::class.java.methods.any { it.returnType == ByteArray::class.java || it.returnType == java.nio.ByteBuffer::class.java })
    }

    @Test fun defaultInspectorOverloadStillChecksBothReportedLengths() {
        val bytes = byteArrayOf(1, 2, 3)
        val valid = JpegInspection(JpegOutputDescription(16, 12, 1, "sRGB", 3), fingerprint(bytes))
        var calls = 0
        val inspector = JpegInspector { _, _ -> calls++; valid }
        assertThrows(IOException::class.java) { inspector.inspect({ ByteArrayInputStream(bytes) }, 0) { false } }
        assertEquals(0, calls)
        assertEquals(valid, inspector.inspect({ ByteArrayInputStream(bytes) }, 3) { false })
        assertThrows(IOException::class.java) { inspector.inspect({ ByteArrayInputStream(bytes) }, 4) { false } }
        val wrongHashLength = JpegInspector { _, _ -> valid.copy(fingerprint = OriginalFingerprint(4, valid.fingerprint.sha256)) }
        assertThrows(IOException::class.java) { wrongHashLength.inspect({ ByteArrayInputStream(bytes) }, 3) { false } }
    }

    @Test fun bulkTransferCannotLeakThePrivateArrayToAWriter() {
        val original = byteArrayOf(-1, -40, 1, -1, -39)
        val snapshot = EncodedJpegSnapshot.read({ ByteArrayInputStream(original) }, original.size.toLong()) { false }
        val writer = object : OutputStream() {
            override fun write(value: Int) {}
            override fun write(buffer: ByteArray, offset: Int, length: Int) { buffer.fill(0) }
        }
        // Host JDK API, not an Android API requirement for the app.
        InputStream::class.java.getMethod("transferTo", OutputStream::class.java).invoke(snapshot.openRead(), writer)
        assertArrayEquals(original, snapshot.openRead().use { it.readBytes() })
        assertEquals(fingerprint(original), snapshot.fingerprint)
    }
}
