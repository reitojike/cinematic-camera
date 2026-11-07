package dev.cinematic.p0.photoedit

import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class PhotoEditBarDispatchTest {
    private val scale = PhotoEditScale(0.0, 1.0, .1) // Fixture policy only.

    private inner class Driver {
        var draft = .5
        val key = Any()
        val notified = mutableListOf<Double>()
        fun gesture(x: Float = 50f) = PhotoEditBarGesture(scale, draft, x, 24f, 0f, 100f, 5f, 8f)
        fun send(input: PhotoEditBarInput?) = dispatchPhotoEditBarInput(scale, key, key, true, { draft }, input) {
            draft = it
            notified += it
        }
        fun pointer(gesture: PhotoEditBarGesture, x: Float, pressed: Boolean = true) =
            gesture.update(x, 24f, pressed, true).also { send(it.input) }
    }

    @Test fun dragOutAndBackBeforeRecompositionRestoresTheActualOwnerValue() {
        val owner = Driver()
        val displayedValue = owner.draft // Never recomposed throughout these inputs.
        val gesture = owner.gesture()
        owner.pointer(gesture, 60f)
        owner.pointer(gesture, 50f)
        owner.pointer(gesture, 50f, false)
        assertEquals(listOf(.4, .5), owner.notified)
        assertEquals(displayedValue, owner.draft, 0.0)
    }

    @Test fun consecutiveFineAdjustmentsUseTheOwnersLatestValueBeforeRecomposition() {
        val owner = Driver()
        for (direction in listOf(1, 1, -1, -1)) owner.send(PhotoEditBarInput.Step(direction))
        assertEquals(listOf(.6, .7, .6, .5), owner.notified)
        assertEquals(.5, owner.draft, 1e-12)
    }

    @Test fun consecutiveBarTapsAndAConcurrentStepReadTheActualOwnerWithoutRecomposition() {
        val owner = Driver()
        for (expected in listOf(.6, .7)) {
            owner.pointer(owner.gesture(95f), 95f, false)
            assertEquals(expected, owner.draft, 1e-12)
        }
        val heldTap = owner.gesture(95f)
        assertTrue(owner.send(PhotoEditBarInput.Step(1)))
        owner.pointer(heldTap, 95f, false)
        assertEquals(.9, owner.draft, 1e-12)
    }

    @Test fun fingerUpPreservesOtherInputAndAppliesOnlyItsRemainingNonzeroMotion() {
        for (other in listOf(PhotoEditBarInput.Step(1), PhotoEditBarInput.SetValue(.2))) {
            for (upX in listOf(60f, 65f)) {
                val owner = Driver()
                val gesture = owner.gesture()
                owner.pointer(gesture, 60f)
                assertTrue(owner.send(other))
                val accepted = owner.draft
                val count = owner.notified.size
                val update = owner.pointer(gesture, upX, false)
                assertEquals(accepted - (upX - 60f) / 100.0, owner.draft, 1e-12)
                if (upX == 60f) {
                    assertNull(update.input)
                    assertEquals(count, owner.notified.size)
                }
            }
        }
    }

    @Test fun bothDragDirectionsApplyOnlyTheirNewMotionToTheUpdatedOwner() {
        for (other in listOf(PhotoEditBarInput.Step(1), PhotoEditBarInput.SetValue(.2))) {
            val owner = Driver()
            val gesture = owner.gesture()
            owner.pointer(gesture, 60f)
            owner.send(other)
            val accepted = owner.draft
            owner.pointer(gesture, 65f)
            assertEquals(accepted - .05, owner.draft, 1e-12)
            owner.pointer(gesture, 55f)
            assertEquals(accepted + .05, owner.draft, 1e-12)
            owner.pointer(gesture, 55f, false)
            assertEquals(accepted + .05, owner.draft, 1e-12)
        }
        val owner = Driver()
        val gesture = owner.gesture()
        owner.pointer(gesture, -150f)
        owner.send(PhotoEditBarInput.Step(-1))
        assertEquals(.9, owner.draft, 1e-12)
        owner.pointer(gesture, -150f)
        assertEquals(.9, owner.draft, 1e-12)
        owner.pointer(gesture, -145f)
        assertEquals(.85, owner.draft, 1e-12)
    }

    @Test fun inputAfterDownButBeforeFirstDragIsPreservedAndPointerCancelDoesNotRollItBack() {
        val owner = Driver()
        val gesture = owner.gesture()
        owner.send(PhotoEditBarInput.Step(1))
        owner.pointer(gesture, 60f)
        assertEquals(.5, owner.draft, 1e-12)
        assertNull(gesture.update(70f, 24f, true, false).input)
        owner.pointer(gesture, 70f, false)
        assertEquals(.5, owner.draft, 1e-12)
    }

    @Test fun staleDisabledOrInvalidInputCannotReadOrMutateAnotherOwner() {
        val key = Any()
        val noRead = { fail("Invalid authority must not read the current draft"); 0.0 }
        val noWrite: (Double) -> Unit = { fail("Refused input must not mutate the owner") }
        for (input in listOf(PhotoEditBarInput.SetValue(.6), PhotoEditBarInput.Step(1), PhotoEditBarInput.RelativeDrag(.1))) {
            assertFalse(dispatchPhotoEditBarInput(scale, key, Any(), true, noRead, input, noWrite))
            assertFalse(dispatchPhotoEditBarInput(scale, key, key, false, noRead, input, noWrite))
            assertFalse(dispatchPhotoEditBarInput(scale, key, key, true, { Double.NaN }, input, noWrite))
        }
        for (input in listOf(null, PhotoEditBarInput.SetValue(Double.NaN), PhotoEditBarInput.SetValue(1.1),
            PhotoEditBarInput.Step(0), PhotoEditBarInput.Step(2), PhotoEditBarInput.RelativeDrag(Double.POSITIVE_INFINITY),
            PhotoEditBarInput.RelativeDrag(0.0)))
            assertFalse(dispatchPhotoEditBarInput(scale, key, key, true, noRead, input, noWrite))
        assertFalse(dispatchPhotoEditBarInput(scale, key, key, true, { .6 }, PhotoEditBarInput.SetValue(.6), noWrite))
    }

    @Test fun capturedTokenCannotWriteAnOldSessionSnapshotBackAfterResetCancelOrPhotoChange() {
        val source = SharedOriginalReference("content://example.test/images/1", OriginalFingerprint(12, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        val scales = PhotoEditScales(.1, PhotoEditScale(-1.0, 1.0, .1), PhotoEditScale(-1.0, 1.0, .1))
        val values = PhotoEditValues(.5, 0.0, 0.0)
        val original = PhotoEditSession.open(source, scales, values, values)
        val capturedToken = original.inputToken
        for (replacement in listOf(original.reset(capturedToken), original.cancel(capturedToken),
            PhotoEditSession.open(source.copy(uri = "content://example.test/images/2"), scales, values, values))) {
            var owner = replacement
            for (input in listOf(PhotoEditBarInput.SetValue(.8), PhotoEditBarInput.Step(1), PhotoEditBarInput.RelativeDrag(.1))) {
                // Composition has not updated the key yet. The callback must still use the current Session.
                dispatchPhotoEditBarInput(scale, capturedToken, capturedToken, true, { owner.draft.strength }, input) {
                    owner = owner.change(capturedToken, PhotoEditParameter.STRENGTH, it)
                }
                assertSame(replacement, owner)
            }
        }
    }
}
