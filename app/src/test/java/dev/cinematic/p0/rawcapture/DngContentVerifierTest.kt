package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class DngContentVerifierTest {
    private val abc = "abc".toByteArray()
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun knownSha256VectorAndShortReadsEstablishContentIdentityOnly() {
        val source = object : FilterInputStream(ByteArrayInputStream(abc)) {
            var closed = false
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 1))
            override fun close() { closed = true; super.close() }
        }
        val fingerprint = DngContentVerifier.identify(3, { source })
        assertEquals(OriginalFingerprint(3, abcHash), fingerprint)
        assertTrue(source.closed) // These synthetic bytes are deliberately not a valid DNG.
    }

    @Test fun matchingReadbackSucceedsButSameSizeChangeShortAndLongInputsFail() {
        val expected = OriginalFingerprint(3, abcHash)
        assertEquals(expected, DngContentVerifier.verify(expected, { ByteArrayInputStream(abc) }))
        for (changed in listOf("abd", "ab", "abcd", "")) {
            assertThrows(DngContentChanged::class.java) {
                DngContentVerifier.verify(expected, { ByteArrayInputStream(changed.toByteArray()) })
            }
        }
    }

    @Test fun invalidFingerprintAndSizeAreRejectedBeforeOpeningSource() {
        for (hash in listOf("", "a".repeat(63), "G".repeat(64), abcHash.uppercase())) {
            assertThrows(IllegalArgumentException::class.java) { OriginalFingerprint(3, hash) }
        }
        assertThrows(IllegalArgumentException::class.java) { OriginalFingerprint(0, abcHash) }
        var opens = 0
        for (size in listOf(0L, -1L)) {
            assertThrows(IllegalArgumentException::class.java) {
                DngContentVerifier.identify(size, { opens++; ByteArrayInputStream(abc) })
            }
        }
        assertEquals(0, opens)
    }

    @Test fun openReadAndCloseFailuresNeverProduceFingerprint() {
        assertThrows(IOException::class.java) { DngContentVerifier.identify(3, { throw IOException("Injected open failure") }) }
        for (fault in listOf("read", "close")) {
            val source = object : FilterInputStream(ByteArrayInputStream(abc)) {
                var closed = false
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (fault == "read") throw IOException("Injected read failure")
                    return super.read(bytes, offset, length)
                }
                override fun close() {
                    closed = true
                    super.close()
                    if (fault == "close") throw IOException("Injected close failure")
                }
            }
            assertThrows(IOException::class.java) { DngContentVerifier.identify(3, { source }) }
            assertTrue(source.closed)
        }
    }

    @Test fun stopBeforeOpenAndDuringReadClosesWithoutSuccessfulResult() {
        var opens = 0
        assertThrows(DngVerificationStopped::class.java) {
            DngContentVerifier.identify(3, { opens++; ByteArrayInputStream(abc) }, { true })
        }
        assertEquals(0, opens)
        var stopped = false
        val source = object : FilterInputStream(ByteArrayInputStream(abc)) {
            var closed = false
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                stopped = true
                return count
            }
            override fun close() { closed = true; super.close() }
        }
        assertThrows(DngVerificationStopped::class.java) { DngContentVerifier.identify(3, { source }, { stopped }) }
        assertTrue(source.closed)
    }

    @Test fun stopDuringCloseIsObservedBeforeReturningFingerprint() {
        var stopped = false
        val source = object : ByteArrayInputStream(abc) {
            override fun close() { super.close(); stopped = true }
        }
        assertThrows(DngVerificationStopped::class.java) { DngContentVerifier.identify(3, { source }, { stopped }) }
    }

    @Test fun readRequestsStayBoundedAndNoProgressDoesNotSpin() {
        var maxRequested = 0
        var reads = 0
        val source = object : InputStream() {
            private var remaining = 200_000
            override fun read(): Int = error("Bulk reads expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                maxRequested = maxOf(maxRequested, length)
                reads++
                if (remaining == 0) return -1
                val count = minOf(length, remaining)
                bytes.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        assertEquals(200_000L, DngContentVerifier.identify(200_000, { source }).bytes)
        assertTrue(maxRequested <= 64 * 1024)
        assertTrue(reads < 10)
        var zeroReads = 0
        val stalled = object : InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { zeroReads++; return 0 }
        }
        assertThrows(IOException::class.java) { DngContentVerifier.identify(1, { stalled }) }
        assertEquals(1, zeroReads)
    }
}
