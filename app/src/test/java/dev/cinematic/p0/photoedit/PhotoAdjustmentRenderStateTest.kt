package dev.cinematic.p0.photoedit

import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class PhotoAdjustmentRenderStateTest {
    // Fixture coordinates only: no adopted product range, unit or tap step.
    private val defaults = PhotoEditValues(.5, 0.0, 0.0)
    private fun open(confirmed: PhotoEditValues = defaults): PhotoAdjustmentRenderState {
        val source = SharedOriginalReference("content://example.test/photo/1",
            OriginalFingerprint(12, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        val scales = PhotoEditScales(.01, PhotoEditScale(-10.0, 10.0, 1.0), PhotoEditScale(-3.0, 3.0, .1))
        return PhotoAdjustmentRenderState.open(PhotoEditSession.open(source, scales, confirmed, defaults), confirmed)
    }

    @Test fun continuousChangesAreFrozenTogetherOnlyOnRequest() {
        var state = open()
        val entry = state.ticket
        val token = state.session.inputToken
        state = state.change(token, PhotoEditParameter.TEMPERATURE, 1.23456789)
            .change(token, PhotoEditParameter.BRIGHTNESS, .43210987)
        assertEquals(entry, state.ticket)
        assertNull(state.readyToSave())
        state = state.request(token)
        assertEquals(PhotoEditValues(.5, 1.23456789, .43210987), state.ticket.values)
        assertNull(state.readyToSave())
        state = state.shown(state.ticket)
        assertEquals(state.ticket, state.readyToSave())
    }

    @Test fun sameStrengthWithDifferentTemperatureCannotAcceptAnOldRender() {
        var state = open()
        val token = state.session.inputToken
        state = state.change(token, PhotoEditParameter.TEMPERATURE, 2.0).request(token)
        val stale = state.ticket
        state = state.change(token, PhotoEditParameter.TEMPERATURE, 3.0).request(token)
        assertSame(state, state.shown(stale))
        assertNull(state.readyToSave())
    }

    @Test fun resetRestoresAllDefaultsAndRejectsOldGestureAndResult() {
        var state = open()
        val token = state.session.inputToken
        state = state.change(token, PhotoEditParameter.BRIGHTNESS, 1.0).request(token)
        val stale = state.ticket
        state = state.reset(token)
        assertEquals(defaults, state.requested)
        assertSame(state, state.change(token, PhotoEditParameter.TEMPERATURE, 2.0))
        assertSame(state, state.shown(stale))
        assertNull(state.readyToSave())
        assertNotNull(state.shown(state.ticket).readyToSave())
    }

    @Test fun cancelRestoresEntryAndPermanentlyDisablesInputAndSave() {
        var state = open()
        val token = state.session.inputToken
        state = state.change(token, PhotoEditParameter.BRIGHTNESS, 1.0).request(token)
        val stale = state.ticket
        state = state.cancel(token)
        assertEquals(defaults, state.requested)
        assertSame(state, state.shown(stale))
        state = state.shown(state.ticket)
        assertEquals(defaults, state.displayed)
        assertNull(state.readyToSave())
        assertSame(state, state.request(token))
        assertSame(state, state.reset(token))
    }

    @Test fun tapStepsFromContinuousPositionAndClampsAtEndpoint() {
        var state = open()
        val token = state.session.inputToken
        state = state.change(token, PhotoEditParameter.BRIGHTNESS, .123)
            .step(token, PhotoEditParameter.BRIGHTNESS, 1)
        assertEquals(.223, state.session.draft.brightness!!, 1e-12)
        state = state.change(token, PhotoEditParameter.BRIGHTNESS, 2.99)
            .step(token, PhotoEditParameter.BRIGHTNESS, 1)
        assertEquals(3.0, state.session.draft.brightness!!, 0.0)
    }

    @Test fun reopenedPhotoRejectsPriorSessionEvenWithIdenticalValues() {
        val previous = open()
        val current = open()
        assertSame(current, current.shown(previous.ticket))
        assertSame(current, current.change(previous.session.inputToken, PhotoEditParameter.TEMPERATURE, 1.0))
    }

    @Test fun changingDraftWhileRenderFinishesDoesNotEnableSavingWrongValues() {
        var state = open()
        val token = state.session.inputToken
        state = state.change(token, PhotoEditParameter.BRIGHTNESS, .5).request(token)
        val rendering = state.ticket
        state = state.change(token, PhotoEditParameter.TEMPERATURE, 1.0).shown(rendering)
        assertNull(state.readyToSave())
    }

    @Test fun matchingStrengthAloneDoesNotAuthorizeOpening() {
        val state = open()
        assertThrows(IllegalArgumentException::class.java) {
            PhotoAdjustmentRenderState.open(state.session, defaults.copy(temperature = 1.0))
        }
    }

    @Test fun resetThenCancelRestoresEntryRatherThanDefaults() {
        val entry = PhotoEditValues(.8, 2.0, .3)
        var state = open(entry)
        state = state.reset(state.session.inputToken)
        assertEquals(defaults, state.requested)
        state = state.shown(state.ticket).cancel(state.session.inputToken)
        assertEquals(entry, state.requested)
        assertNull(state.displayed)
        assertNull(state.shown(state.ticket).readyToSave())
    }
}
