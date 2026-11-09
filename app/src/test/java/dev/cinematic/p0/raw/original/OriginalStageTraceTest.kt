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
    @Test fun startAndFinishBracketTheActionWithoutAddingACancellationCheckpoint() {
        val events = mutableListOf<String>()
        val trace = object : OriginalStageTrace {
            override fun started(stage: OriginalStage) { events.add("start:$stage") }
            override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) {
                events.add("finish:$stage:$completed")
            }
        }
        originalStage(trace, OriginalStage.GLOW) { events.add("action") }
        assertEquals(listOf("start:GLOW", "action", "finish:GLOW:true"), events)
    }
    @Test fun startObserverFailureStillFinishesAndDoesNotRunTheAction() {
        val primary = IllegalStateException("synthetic")
        var called = false
        var ended = false
        val trace = object : OriginalStageTrace {
            override fun started(stage: OriginalStage) { throw primary }
            override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) {
                assertFalse(completed); ended = true
            }
        }
        assertSame(primary, assertThrows(IllegalStateException::class.java) {
            originalStage(trace, OriginalStage.MIST) { called = true }
        })
        assertFalse(called); assertTrue(ended)
    }
}
