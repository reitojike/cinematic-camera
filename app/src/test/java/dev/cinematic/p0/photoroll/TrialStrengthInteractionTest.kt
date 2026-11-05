package dev.cinematic.p0.photoroll

import dev.cinematic.p0.photoedit.*
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class TrialStrengthInteractionTest {
    private inner class Driver {
        private val source = SharedOriginalReference("content://example.test/images/1", OriginalFingerprint(12, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        var state = TrialStrengthEditor.empty().let { requireNotNull(it.bind(source)).shown(it.ticket, .5).begin(.01) }
        val requests = mutableListOf<Double>()
        fun gesture(x: Float = 50f): CapturedGesture {
            val session = requireNotNull(state.session)
            return CapturedGesture(session.inputToken, session.scales.strength,
                PhotoEditBarGesture(session.scales.strength, session.draft.strength, x, 24f, 0f, 100f, 5f, 8f))
        }
        fun pointer(gesture: CapturedGesture, x: Float, pressed: Boolean = true, allowed: Boolean = true) {
            val token = gesture.token
            val update = gesture.input.update(x, 24f, pressed, allowed)
            dispatchPhotoEditBarInput(gesture.scale, token, state.session?.inputToken ?: Any(), allowed,
                { state.session?.draft?.strength ?: Double.NaN }, update.input) { state = state.change(token, it) }
            if (update.completed) {
                val previous = state.ticket
                state = state.apply(token)
                if (previous != state.ticket) requests += state.requestedStrength
            }
        }
    }
    private data class CapturedGesture(val token: PhotoEditInputToken, val scale: PhotoEditScale, val input: PhotoEditBarGesture)

    @Test fun continuousMovesDoNotCreateRenderRequestsAndFingerUpFreezesTheLastValue() {
        val owner = Driver()
        val gesture = owner.gesture()
        owner.pointer(gesture, 60f)
        owner.pointer(gesture, 70f)
        assertEquals(.7, owner.state.session!!.draft.strength, 1e-12)
        assertTrue(owner.requests.isEmpty())
        assertEquals(.5, owner.state.requestedStrength, 0.0)
        owner.pointer(gesture, 75f, false)
        assertEquals(.75, owner.state.requestedStrength, 1e-12)
        assertEquals(1, owner.requests.size)
        assertEquals(.75, owner.requests.single(), 1e-12)
    }

    @Test fun aTapChangesOneDisplayedUnitAndCompletesOneRequest() {
        val owner = Driver()
        owner.pointer(owner.gesture(95f), 95f, false)
        assertEquals(.51, owner.state.requestedStrength, 1e-12)
        assertEquals("51", trialStrengthLabel(owner.state.requestedStrength))
        owner.pointer(owner.gesture(5f), 5f, false)
        assertEquals(.5, owner.state.requestedStrength, 1e-12)
        assertEquals(2, owner.requests.size)
        assertEquals("0", trialStrengthLabel(0.0))
        assertEquals("100", trialStrengthLabel(1.0))
    }

    @Test fun interruptedGestureDoesNotRenderOrTurnItsLaterUpIntoACommit() {
        val owner = Driver()
        val gesture = owner.gesture()
        owner.pointer(gesture, 75f)
        owner.pointer(gesture, 80f, allowed = false)
        owner.pointer(gesture, 80f, false)
        assertTrue(owner.requests.isEmpty())
        assertEquals(.5, owner.state.requestedStrength, 0.0)
    }

    @Test fun resetInvalidatesBothOldDragInputAndItsCompletionBeforeRecomposition() {
        val owner = Driver()
        val oldGesture = owner.gesture()
        owner.pointer(oldGesture, 75f)
        owner.state = owner.state.reset(requireNotNull(owner.state.session).inputToken)
        owner.pointer(oldGesture, 95f, false)
        assertEquals(.5, owner.state.session!!.draft.strength, 0.0)
        assertEquals(.5, owner.state.requestedStrength, 0.0)
        assertTrue(owner.requests.isEmpty())
    }

    @Test fun cancelOrReselectCannotBeUndoneByTheOldFingerUp() {
        val owner = Driver()
        val oldGesture = owner.gesture()
        owner.pointer(oldGesture, 75f)
        owner.state = owner.state.cancel(requireNotNull(owner.state.session).inputToken)
        owner.pointer(oldGesture, 95f, false)
        assertNull(owner.state.session)
        assertTrue(owner.requests.isEmpty())
        val reselected = Driver()
        val unfinished = reselected.gesture()
        reselected.pointer(unfinished, 75f)
        val ticket = reselected.state.ticket
        reselected.state = TrialStrengthEditor.empty()
        reselected.pointer(unfinished, 95f, false)
        assertNotEquals(ticket, reselected.state.ticket)
        assertEquals(.5, reselected.state.requestedStrength, 0.0)
        assertTrue(reselected.requests.isEmpty())
    }
}
