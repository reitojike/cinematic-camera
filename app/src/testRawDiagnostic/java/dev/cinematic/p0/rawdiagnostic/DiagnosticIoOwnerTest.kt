package dev.cinematic.p0.rawdiagnostic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticIoOwnerTest {
    @Test fun failedInputAndOutputClosesHoldOwnerAndDoNotDeleteSource() {
        for (input in listOf(true, false)) {
            val owner = DiagnosticIoOwner()
            var closes = 0
            val stream: java.io.Closeable = if (input) owner.input(object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closes++; throw IOException("injected") }
            }) else owner.output(object : ByteArrayOutputStream() {
                override fun close() { closes++; throw IOException("injected") }
            })
            assertThrows(IOException::class.java) { stream.close() }
            var deleted = false
            val result = owner.finish(true) { deleted = true; true }
            assertEquals(2, closes)
            assertTrue(result.closeFailed)
            assertFalse(result.quiescent)
            assertFalse(result.sourceRemoved)
            assertTrue(owner.hasOpenResources)
            assertFalse(deleted)
        }
    }
    @Test fun transientCloseCanBeRetriedButOriginalOperationStillFails() {
        val owner = DiagnosticIoOwner()
        var closes = 0
        val stream = owner.input(object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { if (++closes == 1) throw IOException("injected") }
        })
        assertThrows(IOException::class.java) { stream.use { it.read() } }
        val result = owner.finish(true) { true }
        assertTrue(result.quiescent)
        assertTrue(result.sourceRemoved)
        assertFalse(owner.hasOpenResources)
    }
    @Test fun deletionFailureIsExplicitAndLiveAttemptPreventsDeletion() {
        val owner = DiagnosticIoOwner()
        val failed = owner.finish(true) { false }
        assertTrue(failed.quiescent)
        assertFalse(failed.sourceRemoved)
        assertFalse(owner.finish(true) { throw IOException("Synthetic removal failure") }.sourceRemoved)
        var deleted = false
        val held = owner.finish(false) { deleted = true; true }
        assertFalse(held.quiescent)
        assertFalse(deleted)
    }
}
