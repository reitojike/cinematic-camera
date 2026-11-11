package dev.cinematic.p0.photoroll

import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditParameter
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class GpuPhotoRollPresentationTest {
    private fun editor(): TrialStrengthEditor = checkNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
        SharedOriginalReference("content://synthetic/gpu", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))

    @Test fun submissionDoesNotConfirmUntilTheSameValuesArePresented() {
        val initial = editor()
        assertNull(gpuPresentedEditor(initial, null, false).displayedValues)
        assertEquals(initial.requestedValues, gpuPresentedEditor(initial, initial.requestedValues, false).displayedValues)
        assertNull(gpuPresentedEditor(initial, initial.requestedValues, true).displayedValues)
    }

    @Test fun cancelCannotConfirmCommittedValuesWhileTheDraftIsStillVisible() {
        val initial = editor()
        val committed = initial.requestedValues
        val open = gpuPresentedEditor(initial, committed, false).begin(.01, true)
        val token = checkNotNull(open.session).inputToken
        val draft = open.change(token, PhotoEditParameter.BRIGHTNESS, 1.0)
        val visible = gpuPresentedEditor(draft, PhotoEditValues(.5, 0.0, 1.0), false)
        assertNull(visible.displayedValues)
        val cancelled = visible.cancel(token)
        assertNull(gpuPresentedEditor(cancelled, PhotoEditValues(.5, 0.0, 1.0), false).displayedValues)
        assertEquals(committed, gpuPresentedEditor(cancelled, committed, false).displayedValues)
    }

    @Test fun everyParameterUsesTheExactDisplayedRecipe() {
        val initial = editor()
        for (values in listOf(PhotoEditValues(.75, 0.0, 0.0), PhotoEditValues(.5, 30.0, 0.0),
            PhotoEditValues(.5, 0.0, -.5))) assertNull(gpuPresentedEditor(initial, values, false).displayedValues)
    }
}
