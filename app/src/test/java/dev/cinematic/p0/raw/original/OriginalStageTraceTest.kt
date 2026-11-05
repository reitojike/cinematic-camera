package dev.cinematic.p0.raw.original

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class OriginalStageTraceTest {
    @Test fun disconnectedAndConnectedSpansReturnTheSameValue() {
        val value = Any()
        assertSame(value, originalStage(null, OriginalStage.R7_PROFILE) { value })
        val events = mutableListOf<Triple<OriginalStage, Long, Boolean>>()
        val trace = OriginalStageTrace { stage, elapsed, complete -> events += Triple(stage, elapsed, complete) }
        assertSame(value, originalStage(trace, OriginalStage.R7_PROFILE) { value })
        assertEquals(1, events.size)
        assertEquals(OriginalStage.R7_PROFILE, events.single().first)
        assertTrue(events.single().second >= 0)
        assertTrue(events.single().third)
    }

    @Test fun observerFailureCannotReplaceThePrimaryProcessingFailure() {
        val primary = IOException("Synthetic producer failure")
        val observer = IOException("Synthetic diagnostic failure")
        var completed = true
        val trace = OriginalStageTrace { _, _, success -> completed = success; throw observer }
        val actual = assertThrows(IOException::class.java) {
            originalStage(trace, OriginalStage.JPEG_ENCODE) { throw primary }
        }
        assertSame(primary, actual)
        assertFalse(completed)
        assertArrayEquals(arrayOf(observer), actual.suppressed)
    }

    @Test fun failedObserverDoesNotReturnASuccessAndSameFailureIsNotSelfSuppressed() {
        val failure = IOException("Synthetic diagnostic failure")
        val trace = OriginalStageTrace { _, _, _ -> throw failure }
        assertSame(failure, assertThrows(IOException::class.java) {
            originalStage(trace, OriginalStage.RESOURCE_CLOSE) { Unit }
        })
        assertSame(failure, assertThrows(IOException::class.java) {
            originalStage(trace, OriginalStage.RESOURCE_CLOSE) { throw failure }
        })
        assertEquals(0, failure.suppressed.size)
    }
}
