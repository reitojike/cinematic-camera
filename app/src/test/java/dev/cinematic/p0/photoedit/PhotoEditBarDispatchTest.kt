package dev.cinematic.p0.photoedit

import org.junit.Assert.*
import org.junit.Test

class PhotoEditBarDispatchTest {
    private val scale = PhotoEditScale(0.0, 1.0, .1) // Fixture policy only.

    @Test fun dragOutAndBackBeforeRecompositionRestoresTheActualOwnerValue() {
        val displayedValue = .5
        var draft = displayedValue
        val key = Any()
        val notified = mutableListOf<Double>()
        val gesture = PhotoEditBarGesture(scale, draft, 50f, 24f, 0f, 100f, 5f, 8f)
        for ((x, pressed) in listOf(60f to true, 50f to true, 50f to false)) {
            val update = gesture.update(x, 24f, pressed, true)
            dispatchPhotoEditBarInput(scale, key, key, true, { draft }, update) {
                draft = it
                notified += it
            }
        }
        // The display never recomposes, but both changes reach the owner; release does not duplicate them.
        assertEquals(listOf(.6, .5), notified)
        assertEquals(displayedValue, draft, 0.0)
    }

    @Test fun consecutiveFineAdjustmentsUseTheOwnersLatestValueBeforeRecomposition() {
        val displayedValue = .5
        var draft = displayedValue
        val key = Any()
        val notified = mutableListOf<Double>()
        for (direction in listOf(1, 1, -1, -1)) {
            dispatchPhotoEditBarInput(scale, key, key, true, { draft }, PhotoEditBarUpdate(stepDirection = direction)) {
                draft = it
                notified += it
            }
        }
        assertEquals(listOf(.6, .7, .6, .5), notified)
        assertEquals(displayedValue, draft, 1e-12)
    }

    @Test fun consecutiveBarTapsAndAConcurrentStepReadTheActualOwnerWithoutRecomposition() {
        var draft = .5
        val key = Any()
        fun send(input: PhotoEditBarUpdate) = dispatchPhotoEditBarInput(scale, key, key, true, { draft }, input) { draft = it }
        for (expected in listOf(.6, .7)) {
            val tap = PhotoEditBarGesture(scale, draft, 95f, 24f, 0f, 100f, 5f, 8f)
            assertTrue(send(tap.update(95f, 24f, false, true)))
            assertEquals(expected, draft, 1e-12)
        }
        val heldTap = PhotoEditBarGesture(scale, draft, 95f, 24f, 0f, 100f, 5f, 8f)
        // Another valid keyboard/a11y step arrives while the finger is down. Finger-up adds its own step.
        assertTrue(send(PhotoEditBarUpdate(stepDirection = 1)))
        assertTrue(send(heldTap.update(95f, 24f, false, true)))
        assertEquals(.9, draft, 1e-12)
    }

    @Test fun staleDisabledOrInvalidInputCannotReadOrMutateAnotherOwner() {
        val key = Any()
        val noRead = { fail("Invalid authority must not read the current draft"); 0.0 }
        val noWrite: (Double) -> Unit = { fail("Refused input must not mutate the owner") }
        val change = PhotoEditBarUpdate(.6)
        assertFalse(dispatchPhotoEditBarInput(scale, key, Any(), true, noRead, change, noWrite))
        assertFalse(dispatchPhotoEditBarInput(scale, key, key, false, noRead, change, noWrite))
        assertFalse(dispatchPhotoEditBarInput(scale, key, key, true, noRead, PhotoEditBarUpdate(Double.NaN), noWrite))
        assertFalse(dispatchPhotoEditBarInput(scale, key, key, true, { Double.NaN }, change, noWrite))
        assertFalse(dispatchPhotoEditBarInput(scale, key, key, true, { .6 }, change, noWrite))
        assertFalse(dispatchPhotoEditBarInput(scale, key, Any(), true, noRead, PhotoEditBarUpdate(stepDirection = 1), noWrite))
    }
}
