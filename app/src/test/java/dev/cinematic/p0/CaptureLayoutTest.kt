package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureLayoutTest {
    @Test fun backClosesOneLayerAndNeverExitsDuringRecordingOrPendingApply() {
        assertEquals(CapturePanel.SETTINGS, captureBack(CapturePanel.DIAGNOSTICS, true, false, false, true).panel)
        assertEquals(CapturePanel.NONE, captureBack(CapturePanel.SETTINGS, true, false, false, true).panel)
        assertTrue(captureBack(CapturePanel.NONE, true, false, false, true).closeRuler)
        assertFalse(captureBack(CapturePanel.NONE, true, true, false, true).closeRuler)
        assertFalse(captureBack(CapturePanel.NONE, false, false, true, true).exit)
        assertTrue(captureBack(CapturePanel.NONE, false, false, false, false).armExit)
        assertTrue(captureBack(CapturePanel.NONE, false, false, false, true).exit)
    }
    @Test fun portraitAndCineFramesFitWithoutCrop() {
        for ((w, h) in listOf(412f to 772f, 412f to 428f, 320f to 260f)) {
            for ((iw, ih) in listOf(3f to 4f, 9f to 16f, 4f to 3f)) {
                val fit = CaptureLayout.fit(w, h, iw, ih)
                assertTrue(fit.first <= w + .01f && fit.second <= h + .01f)
                assertEquals(iw / ih, fit.first / fit.second, .0001f)
            }
        }
    }
    @Test fun sheetOverlaysPreviewAndNeverReservesAdditionalHeight() {
        assertEquals(344f, CaptureLayout.sheetHeight(868f), 0f)
        assertEquals(304f, CaptureLayout.sheetHeight(400f), 0f)
        assertEquals(84f, CaptureLayout.sheetHeight(180f), 0f)
        assertEquals(0f, CaptureLayout.sheetHeight(80f), 0f)
        for (available in listOf(868f, 400f, 180f)) {
            val preview = CaptureLayout.measure(available, CapturePanel.NONE).previewHeight
            assertEquals(available - 96f, preview, 0f)
            assertTrue(CaptureLayout.sheetHeight(available) <= preview)
            for (panel in CapturePanel.entries) {
                val dimensions = CaptureLayout.measure(available, panel)
                assertEquals(preview, dimensions.previewHeight, 0f)
                assertEquals(if (panel == CapturePanel.SETTINGS) CaptureLayout.sheetHeight(available) else 0f,
                    dimensions.settingsHeight, 0f)
            }
        }
    }
    @Test fun previewInsetDependsOnlyOnCaptureMode() {
        assertEquals(44f, CaptureLayout.previewTop(CaptureMode.STILL), 0f)
        assertEquals(0f, CaptureLayout.previewTop(CaptureMode.CINE), 0f)
    }
    @Test fun diagnosticsCannotCoverStopDuringAnyBusyRecordingStage() {
        for (stage in RecordingStage.entries) {
            assertEquals(if (stage == RecordingStage.IDLE) CapturePanel.DIAGNOSTICS else CapturePanel.NONE,
                recordingPanel(CapturePanel.DIAGNOSTICS, stage))
            assertEquals(CapturePanel.SETTINGS, recordingPanel(CapturePanel.SETTINGS, stage))
            assertEquals(CapturePanel.NONE, recordingPanel(CapturePanel.NONE, stage))
        }
    }
}
