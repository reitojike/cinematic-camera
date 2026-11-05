package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class TrialStrengthEditorTest {
    private val source = SharedOriginalReference("content://example.test/images/1", OriginalFingerprint(12, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private fun displayed() = TrialStrengthEditor.empty().let { initial ->
        requireNotNull(initial.bind(source)).shown(initial.ticket, .5)
    }

    @Test fun onlyAVerifiedDisplayedSourceCanEnterStrengthEditing() {
        val initial = TrialStrengthEditor.empty()
        assertEquals(.5, initial.requestedStrength, 0.0)
        assertSame(initial, initial.begin(.01))
        val checked = requireNotNull(initial.bind(source))
        assertSame(checked, checked.begin(.01))
        val editing = checked.shown(initial.ticket, .5).begin(.01)
        assertNotNull(editing.session)
        assertNull(editing.session!!.draft.temperature)
        assertNull(editing.session!!.draft.brightness)
        assertEquals(.01, editing.session!!.scales.strength.tapStep, 0.0)
    }

    @Test fun draggingChangesOnlyTheDraftUntilTheUserRequestsARealRender() {
        var state = displayed().begin(.01)
        val token = state.session!!.inputToken
        val originalTicket = state.ticket
        for (strength in listOf(.1, .234567890123, .75, 1.0, 0.0)) state = state.change(token, strength)
        assertEquals(originalTicket, state.ticket)
        assertEquals(.5, state.requestedStrength, 0.0)
        assertTrue(state.hasUnappliedChanges)
        state = state.apply(token)
        assertEquals(0.0, state.requestedStrength, 0.0)
        assertNull(state.displayedStrength)
        assertNotEquals(originalTicket, state.ticket)
        assertFalse(state.accepts(originalTicket))
        assertSame(state, state.apply(token))
    }

    @Test fun anOldStrengthResultCannotReplaceTheLatestRequestedImage() {
        var state = displayed().begin(.1) // Fixture step, not production policy.
        val token = state.session!!.inputToken
        state = state.change(token, .75).apply(token)
        val prior = state.ticket
        state = state.change(token, .25).apply(token)
        assertSame(state, state.shown(prior, .75))
        assertSame(state, state.shown(state.ticket, .75))
        val current = state.shown(state.ticket, .25)
        assertEquals(.25, requireNotNull(current.displayedStrength), 0.0)
        assertFalse(current.hasUnappliedChanges)
    }

    @Test fun cancelRestoresEntryStrengthAndInvalidatesNewerWorkAndOldInput() {
        var state = displayed().begin(.01)
        val token = state.session!!.inputToken
        state = state.change(token, .8).apply(token)
        val pending = state.ticket
        val cancelled = state.cancel(token)
        assertNull(cancelled.session)
        assertEquals(.5, cancelled.requestedStrength, 0.0)
        assertFalse(cancelled.accepts(pending))
        assertSame(cancelled, cancelled.change(token, .9))
        assertSame(cancelled, cancelled.shown(pending, .8))
        assertEquals(.5, requireNotNull(cancelled.shown(cancelled.ticket, .5).displayedStrength), 0.0)
    }

    @Test fun closingKeepsOnlyTheDisplayedTemporaryValueAndResetStillUsesTheAgreedDefault() {
        var state = displayed().begin(.01)
        val token = state.session!!.inputToken
        state = state.change(token, .75)
        assertSame(state, state.close(token))
        state = state.apply(token)
        assertSame(state, state.close(token))
        state = state.shown(state.ticket, .75).close(token).begin(.01)
        assertEquals(.75, state.session!!.confirmed.strength, 0.0)
        val nextToken = state.session!!.inputToken
        state = state.reset(nextToken)
        assertEquals(.5, state.session!!.draft.strength, 0.0)
        assertEquals(.75, state.requestedStrength, 0.0)
        assertSame(state, state.change(nextToken, 1.0))
        state = state.cancel(state.session!!.inputToken)
        assertEquals(.75, state.requestedStrength, 0.0)
    }

    @Test fun backgroundAndReselectionNeverApplyTheUnconfirmedDraftOrAdoptLateWork() {
        var state = displayed().begin(.01)
        val token = state.session!!.inputToken
        state = state.change(token, .9).apply(token)
        val pending = state.ticket
        val paused = state.pause()
        assertNull(paused.session)
        assertNull(paused.displayedStrength)
        assertEquals(.5, paused.requestedStrength, 0.0)
        assertFalse(paused.accepts(pending))
        assertSame(paused, paused.apply(token))
        val reselected = TrialStrengthEditor.empty()
        assertFalse(reselected.accepts(state.ticket))
        assertEquals(.5, reselected.requestedStrength, 0.0)
    }

    @Test fun aChangedOriginalCannotInheritTheExistingDraft() {
        val state = displayed().begin(.01)
        assertSame(state, state.bind(source))
        assertNull(state.bind(source.copy(fingerprint = OriginalFingerprint(12, "b".repeat(64)))))
        assertNull(state.bind(source.copy(uri = "content://example.test/images/2")))
        assertNull(state.bind(source.copy(grant = OriginalGrant.APP_OWNED)))
    }
}
