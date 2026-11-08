package dev.cinematic.p0.photoroll

import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditParameter
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

class DraftPreviewAdmissionTest {
    private val source = SharedOriginalReference("content://example.test/draft/1",
        OriginalFingerprint(16, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private fun editing(reference: SharedOriginalReference = source): TrialStrengthEditor {
        val initial = TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults)
        return requireNotNull(initial.bind(reference)).shown(initial.ticket, initial.requestedValues).begin(.01, true)
    }
    @Test fun continuousDragDisplaysCompletedWorkButOnlyStartsLatestPending() {
        val admission = DraftPreviewAdmission()
        var editor = editing()
        val token = editor.session!!.inputToken
        val committed = editor.ticket
        editor = editor.change(token, .6)
        val first = requireNotNull(admission.request(editor))
        assertTrue(admission.canStart(first, editor, source.uri))
        editor = editor.change(token, .7)
        val middle = requireNotNull(admission.request(editor))
        editor = editor.change(token, .8)
        val latest = requireNotNull(admission.request(editor))
        assertFalse(admission.canStart(first, editor, source.uri))
        assertFalse(admission.canStart(middle, editor, source.uri))
        assertTrue(admission.canStart(latest, editor, source.uri))
        assertTrue(admission.canDisplay(first, editor, source.uri))
        admission.displayed(first)
        assertTrue(admission.canDisplay(latest, editor, source.uri))
        admission.displayed(latest)
        assertFalse(admission.canDisplay(middle, editor, source.uri))
        assertEquals(committed, editor.ticket)
        assertEquals(PhotoAdjustmentPolicy.defaults, editor.requestedValues)
    }
    @Test fun releaseRejectsAllOldDraftsIncludingAnUnchangedCommittedValue() {
        val admission = DraftPreviewAdmission()
        var editor = editing()
        val token = editor.session!!.inputToken
        val initial = editor.ticket
        editor = editor.change(token, .7)
        val old = requireNotNull(admission.request(editor))
        editor = editor.change(token, .5)
        val returned = requireNotNull(admission.request(editor))
        editor = editor.apply(token)
        assertEquals(initial, editor.ticket)
        admission.invalidate()
        assertFalse(admission.canDisplay(old, editor, source.uri))
        assertFalse(admission.canDisplay(returned, editor, source.uri))
        assertFalse(admission.canStart(returned, editor, source.uri))
    }
    @Test fun repeatedWarmthAndExposureReleaseNeverChangeSaveRecipeDuringDrag() {
        val admission = DraftPreviewAdmission()
        var editor = editing()
        repeat(5) { index ->
            val token = editor.session!!.inputToken
            val committed = editor.requestedValues
            editor = editor.change(token, PhotoEditParameter.TEMPERATURE, index * 15.0)
                .change(token, PhotoEditParameter.BRIGHTNESS, index * .2)
            val draft = requireNotNull(admission.request(editor))
            assertEquals(committed, editor.requestedValues)
            editor = editor.showingDraft()
            assertNull(editor.displayedValues)
            assertSame(editor, editor.close(token))
            admission.invalidate()
            editor = editor.apply(token)
            assertFalse(admission.canDisplay(draft, editor, source.uri))
            editor = editor.shown(editor.ticket, editor.requestedValues)
            assertEquals(editor.requestedValues, editor.displayedValues)
        }
    }
    @Test fun sourceFingerprintTokenCancelAndLifecycleInvalidateCompletion() {
        val admission = DraftPreviewAdmission()
        val editor = editing()
        val draft = requireNotNull(admission.request(editor))
        val token = editor.session!!.inputToken
        val replaced = source.copy(fingerprint = OriginalFingerprint(16, "b".repeat(64)))
        assertFalse(admission.canDisplay(draft, editing(replaced), source.uri))
        assertFalse(admission.canDisplay(draft, editing(), source.uri))
        assertFalse(admission.canDisplay(draft, editor, "content://example.test/draft/2"))
        assertFalse(admission.canDisplay(draft, editor.cancel(token), source.uri))
        assertFalse(admission.canDisplay(draft, editor.close(token), source.uri))
        assertFalse(admission.canDisplay(draft, editor.suspendForLifecycle(), source.uri))
    }
    @Test fun dragStartedWhileExactRendersSurvivesThatExactCompletionOnly() {
        val admission = DraftPreviewAdmission()
        var editor = editing()
        val token = editor.session!!.inputToken
        editor = editor.change(token, .6).apply(token)
        val exact = editor.ticket
        editor = editor.change(token, .9)
        val draft = requireNotNull(admission.request(editor))
        editor = editor.shown(exact, exact.values)
        assertTrue(admission.canDisplay(draft, editor, source.uri))
        editor = editor.apply(token)
        assertFalse(admission.canDisplay(draft, editor, source.uri))
    }
}
