package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class RawCapturePresentationTest {
    @Test fun autoDeadlineThenLateFullReceiptRestoresAutoReadiness() {
        val ui = RawCapturePresentation()
        val request = ui.begin("auto", false)
        assertTrue(ui.issued(request))
        assertTrue(ui.unknown(request))
        assertTrue(ui.ownsCapture(request))
        val completed = ui.received(request, receipt("auto", CaptureReceiptPhase.INTERRUPTED))!!
        assertTrue(completed.restoreAutoPreview)
        assertTrue(completed.interrupted)
        assertFalse(ui.ownsCapture(request))
        assertNull(ui.received(request, receipt("auto", CaptureReceiptPhase.INTERRUPTED)))
    }

    @Test fun nextManualPreparationImmediatelySupersedesOldSharedNotifications() {
        val ui = RawCapturePresentation()
        val first = ui.begin("first", false)
        ui.issued(first)
        ui.received(first, receipt("first"))
        assertTrue(ui.ownsSharedNotice(first))
        val next = ui.begin("next", true)
        assertFalse(ui.ownsSharedNotice(first))
        assertTrue(ui.ownsCapture(next))
        assertFalse(ui.failed(first))
        ui.invalidate() // A JPEG request or a stopped/replaced camera supersedes RAW notifications too.
        assertFalse(ui.ownsSharedNotice(next))
    }

    @Test fun manualLateFullReceiptKeepsManualPreviewRestoration() {
        val ui = RawCapturePresentation()
        val request = ui.begin("manual", true)
        ui.issued(request); ui.unknown(request)
        val completed = ui.received(request, receipt("manual", CaptureReceiptPhase.INTERRUPTED))!!
        assertFalse(completed.restoreAutoPreview)
        assertTrue(completed.interrupted)
    }

    @Test fun partialForeignAndPreIssueResultsCannotFinishTheCurrentCapture() {
        val ui = RawCapturePresentation()
        val request = ui.begin("pair", false)
        assertNull(ui.received(request, receipt("pair")))
        ui.issued(request)
        assertNull(ui.received(request, CaptureReceipt("pair", 1, setOf(RawOutput.DNG, RawOutput.JPEG),
            mapOf(RawOutput.DNG to 2L), CaptureReceiptPhase.CAPTURING)))
        assertNull(ui.received(request, receipt("foreign")))
        assertTrue(ui.ownsCapture(request))
        assertTrue(ui.failed(request))
        assertFalse(ui.ownsCapture(request))
        assertTrue(ui.ownsSharedNotice(request))
    }

    private fun receipt(id: String, phase: CaptureReceiptPhase = CaptureReceiptPhase.FILES_RECEIVED) =
        CaptureReceipt(id, 1, setOf(RawOutput.DNG), mapOf(RawOutput.DNG to 2L), phase)
}
