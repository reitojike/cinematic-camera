package dev.cinematic.p0.photoroll

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PhotoRollReadResourcesTest {
    @Test fun failedCloseStaysOwnedUntilActualRetryAcknowledgesIt() {
        val owner = PhotoRollReadResources()
        var failClose = true
        var closes = 0
        val stream = owner.open { object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closes++; if (failClose) throw IOException("close pending") }
        } }
        assertThrows(IOException::class.java) { stream.close() }
        assertFalse(owner.closeRetained())
        failClose = false
        assertTrue(owner.closeRetained())
        stream.close()
        assertEquals(3, closes)
    }

    @Test fun readFailureAndSuccessfulCloseDoNotLeaveAnUnknownOwner() {
        val owner = PhotoRollReadResources()
        var closed = false
        assertThrows(IOException::class.java) { owner.useOwned(object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true }
        }) { throw IOException("read failed") } }
        assertTrue(closed)
        assertTrue(owner.closeRetained())
    }
}
