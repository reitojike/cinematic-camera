package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureScreenMessagesTest {
    @Test fun recoveryPrecedesFailureAndActionEligibilityIsPreserved() {
        val result = captureMessages(captureRecovery = true, controlRecovery = true,
            captureFailed = true, thermalWarning = true, notice = "保存しました")
        assertEquals("capture.recovery", result.center!!.fact)
        assertEquals(MessageAction.STOP_AND_RECOVER, result.center.primary!!.action)
        assertNull(result.banner)
        assertNull(result.toast)
        assertNull(captureMessages(controlRecovery = true).center!!.primary)
        assertEquals(MessageAction.RECONNECT,
            captureMessages(controlRecovery = true, canReconnect = true).center!!.primary!!.action)
    }

    @Test fun processingPrecedesWarningAndWarningsPrecedeToast() {
        assertEquals("capture.processing", captureMessages(phase = "処理中", thermalWarning = true).banner!!.fact)
        assertEquals("recording.thermal", captureMessages(thermalWarning = true,
            microphoneWarning = true, notice = "保存しました").banner!!.fact)
        assertNull(captureMessages(microphoneWarning = true, notice = "保存しました").toast)
    }

    @Test fun noticesRetainLifetimeOwnershipAndTone() {
        assertEquals(CaptureChromeTone.ACCEPTED, captureMessages(notice = "保存しました").toast!!.tone)
        assertEquals(CaptureChromeTone.ACCEPTED, captureMessages(notice = "設定API受付完了・実測は診断で確認").toast!!.tone)
        assertEquals(CaptureChromeTone.NORMAL, captureMessages(notice = "回転: 横向き").toast!!.tone)
    }

    @Test fun connectionCenterCanCoexistWithSeparateWarning() {
        val result = captureMessages(waitingForConnection = true, thermalWarning = true)
        assertEquals(MessageSlot.CENTER, result.center!!.slot)
        assertNotNull(result.banner)
    }
    @Test fun settingsNotesAreInlineAndApplyingReplacesBusy() {
        assertEquals("settings.busy", captureSettingsMessages(busy = true).inline.single().fact)
        val applying = captureSettingsMessages(busy = true, applying = "適用中")
        assertEquals("settings.applying", applying.inline.single().fact)
        assertEquals(CaptureChromeTone.WARNING, applying.inline.single().tone)
        assertEquals(ScreenMessages(), captureSettingsMessages())
    }
    @Test fun genericFailureUsesOnlyExistingDiagnosticPermission() {
        for (enabled in listOf(false, true)) {
            val fallback = captureFailureFallback(enabled)
            assertEquals(MessageSlot.PROBLEM, fallback.slot)
            assertEquals(MessageAction.OPEN_DIAGNOSTICS, fallback.primary!!.action)
            assertEquals(enabled, fallback.primary.enabled)
            assertNull(fallback.secondary)
            assertEquals(enabled, captureMessages(captureFailed = true,
                diagnosticsEnabled = enabled).banner!!.primary!!.enabled)
        }
    }
}
