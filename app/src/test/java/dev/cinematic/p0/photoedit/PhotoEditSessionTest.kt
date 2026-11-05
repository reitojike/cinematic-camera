package dev.cinematic.p0.photoedit

import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class PhotoEditSessionTest {
    // Arbitrary UI coordinate fixtures. Neither policy nor these steps are enrolled in a renderer.
    private val scales = PhotoEditScales(.1, PhotoEditScale(-10.0, 10.0, .25), PhotoEditScale(-5.0, 5.0, .1))
    private val confirmed = PhotoEditValues(.75, 1.0, .3)
    private val defaults = PhotoEditValues(.5, 0.0, 0.0)
    private fun source(row: Int = 1) = SharedOriginalReference("content://example.test/images/$row",
        OriginalFingerprint(12, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private fun open(row: Int = 1) = PhotoEditSession.open(source(row), scales, confirmed, defaults)

    @Test fun changingOneParameterPreservesTheOthersAndConfirmedSettings() {
        val session = open()
        val changed = session.change(session.inputToken, PhotoEditParameter.STRENGTH, .537)
        assertEquals(confirmed.copy(strength = .537), changed.draft)
        assertEquals(confirmed, changed.confirmed)
        assertEquals(session.original, changed.original)
        assertEquals(session.inputToken, changed.inputToken)
        assertEquals(session.revision + 1, changed.revision)
        assertTrue(changed.dirty)
    }

    @Test fun allParametersAcceptContinuousValuesAndTheirSeparateTapSteps() {
        var session = open()
        for ((field, continuous, stepped) in listOf(
            Triple(PhotoEditParameter.STRENGTH, .537, .637),
            Triple(PhotoEditParameter.TEMPERATURE, 2.123, 2.373),
            Triple(PhotoEditParameter.BRIGHTNESS, -.237, -.137),
        )) {
            session = session.change(session.inputToken, field, continuous)
            assertEquals(continuous, requireNotNull(session.draft[field]), 0.0)
            session = session.step(session.inputToken, field, 1)
            assertEquals(stepped, requireNotNull(session.draft[field]), 1e-12)
        }
    }

    @Test fun resetIsUndoableByCancelAndInvalidatesTheFingerAlreadyOnTheBar() {
        val original = open()
        val edited = original.change(original.inputToken, PhotoEditParameter.BRIGHTNESS, 1.5)
        val previousInput = edited.inputToken
        val reset = edited.reset(previousInput)
        assertEquals(defaults, reset.draft)
        assertTrue(reset.active)
        assertNotEquals(previousInput, reset.inputToken)
        assertSame(reset, reset.change(previousInput, PhotoEditParameter.BRIGHTNESS, 2.0))
        val cancelled = reset.cancel(reset.inputToken)
        assertEquals(confirmed, cancelled.draft)
        assertFalse(cancelled.active)
        assertFalse(cancelled.dirty)
        assertSame(cancelled, cancelled.change(cancelled.inputToken, PhotoEditParameter.STRENGTH, .25))
    }

    @Test fun resetInvalidatesInputEvenIfItsValuesWereAlreadyDefault() {
        val session = PhotoEditSession.open(source(), scales, defaults, defaults)
        val reset = session.reset(session.inputToken)
        assertEquals(defaults, reset.draft)
        assertNotEquals(session.inputToken, reset.inputToken)
        assertFalse(reset.acceptsPreview(session.previewTicket))
    }

    @Test fun aLatePreviewCannotReplaceLatestValuesEvenAfterReturningToTheSameValues() {
        val session = open()
        val oldTicket = session.previewTicket
        val changed = session.change(session.inputToken, PhotoEditParameter.STRENGTH, .25)
        assertFalse(changed.acceptsPreview(oldTicket))
        assertTrue(changed.acceptsPreview(changed.previewTicket))
        val restored = changed.change(changed.inputToken, PhotoEditParameter.STRENGTH, confirmed.strength)
        assertEquals(confirmed, restored.draft)
        assertFalse(restored.acceptsPreview(oldTicket))
        assertFalse(restored.cancel(restored.inputToken).acceptsPreview(restored.previewTicket))
    }

    @Test fun anotherPhotoOrReopenedSessionCannotAcceptOldGesturesOrPreviews() {
        val first = open()
        for (next in listOf(open(2), open(1))) {
            assertSame(next, next.change(first.inputToken, PhotoEditParameter.STRENGTH, .25))
            assertSame(next, next.reset(first.inputToken))
            assertSame(next, next.cancel(first.inputToken))
            assertFalse(next.acceptsPreview(first.previewTicket))
        }
    }

    @Test fun invalidOrUnchangedInputDoesNotCreateRevisionsOrLoseOtherValues() {
        val session = open()
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1, confirmed.strength))
            assertSame(session, session.change(session.inputToken, PhotoEditParameter.STRENGTH, value))
        assertSame(session, session.step(session.inputToken, PhotoEditParameter.STRENGTH, 0))
        val minimum = session.change(session.inputToken, PhotoEditParameter.STRENGTH, 0.0)
        assertSame(minimum, minimum.step(minimum.inputToken, PhotoEditParameter.STRENGTH, -1))
    }

    @Test fun openingRequiresExplicitValidConfirmedAndDefaultValues() {
        assertThrows(IllegalArgumentException::class.java) {
            PhotoEditSession.open(source(), scales, confirmed.copy(temperature = 11.0), defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhotoEditSession.open(source(), scales, confirmed, defaults.copy(strength = 1.0))
        }
    }

    @Test fun strengthOnlyEditingDoesNotInventOtherControlValuesOrRanges() {
        val session = PhotoEditSession.open(source(), PhotoEditScales(.01), PhotoEditValues(.5), PhotoEditValues(.5))
        val changed = session.change(session.inputToken, PhotoEditParameter.STRENGTH, .75)
        assertEquals(.75, changed.draft.strength, 0.0)
        assertNull(changed.draft.temperature)
        assertNull(changed.draft.brightness)
        assertSame(changed, changed.change(changed.inputToken, PhotoEditParameter.TEMPERATURE, 4000.0))
        assertSame(changed, changed.change(changed.inputToken, PhotoEditParameter.BRIGHTNESS, 1.0))
        assertEquals(.76, changed.step(changed.inputToken, PhotoEditParameter.STRENGTH, 1).draft.strength, 1e-12)
        assertEquals(.5, changed.cancel(changed.inputToken).draft.strength, 0.0)
    }
}
