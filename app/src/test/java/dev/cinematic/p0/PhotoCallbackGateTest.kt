package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class PhotoCallbackGateTest {
    private val jpeg = 256
    private val raw = 32

    @Test fun jpegOnlyCompletesExactlyOnce() {
        val gate = PhotoCallbackGate(setOf(jpeg))
        assertFalse(gate.terminal); assertFalse(gate.publishing)
        assertEquals(PhotoCallbackOutcome.COMPLETE, gate.saved(jpeg))
        assertTrue(gate.terminal); assertTrue(gate.publishing)
        assertEquals(PhotoCallbackOutcome.IGNORED, gate.saved(jpeg))
    }

    @Test fun rawAndJpegCompleteOnlyAfterBothFormatsInEitherOrder() {
        for ((first, second) in listOf(raw to jpeg, jpeg to raw)) {
            val gate = PhotoCallbackGate(setOf(raw, jpeg))
            assertEquals(PhotoCallbackOutcome.PARTIAL, gate.saved(first))
            assertFalse(gate.terminal); assertFalse(gate.publishing)
            assertEquals(PhotoCallbackOutcome.COMPLETE, gate.saved(second))
            assertTrue(gate.terminal); assertTrue(gate.publishing)
        }
    }

    @Test fun duplicateFormatNeverSubstitutesForMissingOutput() {
        val gate = PhotoCallbackGate(setOf(raw, jpeg))
        assertEquals(PhotoCallbackOutcome.PARTIAL, gate.saved(raw))
        assertEquals(PhotoCallbackOutcome.IGNORED, gate.saved(raw))
        assertFalse(gate.terminal); assertFalse(gate.publishing)
        assertEquals(PhotoCallbackOutcome.COMPLETE, gate.saved(jpeg))
        assertEquals(PhotoCallbackOutcome.IGNORED, gate.saved(raw))
        assertEquals(PhotoCallbackOutcome.IGNORED, gate.saved(jpeg))
    }

    @Test fun partialThenErrorNeverPublishesWhenOtherOutputArrivesLate() {
        val gate = PhotoCallbackGate(setOf(raw, jpeg))
        assertEquals(PhotoCallbackOutcome.PARTIAL, gate.saved(raw))
        assertTrue(gate.fail()); assertFalse(gate.fail())
        assertEquals(PhotoCallbackOutcome.IGNORED, gate.saved(jpeg))
        assertTrue(gate.terminal)
        assertFalse(gate.publishing) // Caller may clean staging after this late callback.
    }

    @Test fun unexpectedFormatIsRejectedAndCallerCanMarkTheFirstFailure() {
        assertThrows(IllegalArgumentException::class.java) { PhotoCallbackGate(emptySet()) }
        val expected = mutableSetOf(raw, jpeg)
        val gate = PhotoCallbackGate(expected)
        expected += 999 // Caller mutation must not change which outputs complete the capture.
        assertThrows(IllegalArgumentException::class.java) { gate.saved(999) }
        assertFalse(gate.terminal); assertFalse(gate.publishing)
        assertTrue(gate.fail())
        assertEquals(PhotoCallbackOutcome.IGNORED, gate.saved(raw))
        assertFalse(gate.publishing)
    }

    @Test fun errorOrUnexpectedLateCallbackCannotClearPublicationOwnership() {
        val gate = PhotoCallbackGate(setOf(raw, jpeg))
        gate.saved(raw)
        assertEquals(PhotoCallbackOutcome.COMPLETE, gate.saved(jpeg))
        assertFalse(gate.fail())
        assertEquals(PhotoCallbackOutcome.IGNORED, gate.saved(999))
        assertTrue(gate.publishing) // Caller must not delete files while publication IO owns them.
    }
}
