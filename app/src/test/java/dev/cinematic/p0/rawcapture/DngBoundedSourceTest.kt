package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DngBoundedSourceTest {
    @Test fun unknownSizeIsCountedAndHasSameFingerprintAsKnownSize() {
        val bytes = ByteArray(100_003) { (it % 251).toByte() }
        var closed = false
        val actual = DngContentVerifier.identifyBounded(200_000, {
            object : ByteArrayInputStream(bytes) { override fun close() { closed = true } }
        })
        assertTrue(closed)
        assertEquals(DngContentVerifier.identify(bytes.size.toLong(), { ByteArrayInputStream(bytes) }), actual)
    }
    @Test fun emptyAndOneByteOverLimitAreRejectedAndClosed() {
        for (bytes in listOf(byteArrayOf(), ByteArray(11))) {
            var closed = false
            assertThrows(DngContentChanged::class.java) { DngContentVerifier.identifyBounded(10, {
                object : ByteArrayInputStream(bytes) { override fun close() { closed = true } }
            }) }
            assertTrue(closed)
        }
    }
    @Test fun exactLimitIsAcceptedWithoutTrustingMetadata() {
        assertEquals(10L, DngContentVerifier.identifyBounded(10, { ByteArrayInputStream(ByteArray(10)) }).bytes)
    }
    @Test fun closeFailureNeverProducesSuccessfulIdentity() {
        assertThrows(IOException::class.java) { DngContentVerifier.identifyBounded(10, {
            object : ByteArrayInputStream(ByteArray(10)) { override fun close() { throw IOException("close") } }
        }) }
    }
    @Test fun cancellationAndNoProgressStopBoundedScan() {
        assertThrows(DngVerificationStopped::class.java) {
            DngContentVerifier.identifyBounded(10, { fail("must not open"); ByteArrayInputStream(ByteArray(1)) }, { true })
        }
        assertThrows(IOException::class.java) { DngContentVerifier.identifyBounded(10, {
            object : ByteArrayInputStream(ByteArray(1)) { override fun read(b: ByteArray, off: Int, len: Int) = 0 }
        }) }
    }
}
