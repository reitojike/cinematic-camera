package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class CaptureScreenMessagesTest {
    @Test fun recoveryPrecedesFailureAndActionEligibilityIsPreserved() {
        val result = captureMessages(captureRecovery = true, controlRecovery = true,
            captureFailed = true, thermalWarning = true, notice = CaptureNotice("保存しました", CaptureChromeTone.ACCEPTED))
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
            microphoneWarning = true, notice = CaptureNotice("保存しました", CaptureChromeTone.ACCEPTED)).banner!!.fact)
        assertNull(captureMessages(microphoneWarning = true, notice = CaptureNotice("保存しました", CaptureChromeTone.ACCEPTED)).toast)
    }

    @Test fun noticesRetainLifetimeOwnershipAndTone() {
        assertEquals(CaptureChromeTone.ACCEPTED, captureMessages(notice = CaptureNotice("保存しました", CaptureChromeTone.ACCEPTED)).toast!!.tone)
        assertEquals(CaptureChromeTone.ACCEPTED, captureMessages(notice = CaptureNotice("設定を受け付けました。実測値は診断で確認できます。", CaptureChromeTone.ACCEPTED)).toast!!.tone)
        assertEquals(CaptureChromeTone.NORMAL, captureMessages(notice = captureRotationNotice(false)).toast!!.tone)
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
    @Test fun toneComesFromEventAndNotTheWording() {
        assertEquals(CaptureChromeTone.NORMAL, captureMessages(notice = CaptureNotice("保存しました")).toast!!.tone)
        assertEquals(CaptureChromeTone.ACCEPTED,
            captureMessages(notice = CaptureNotice("別の完了文", CaptureChromeTone.ACCEPTED)).toast!!.tone)
    }

    @Test fun permissionUsesCenterAndOnlyExistingPermissionAction() {
        val message = captureMessages(cameraPermissionMissing = true).center!!
        assertEquals(MessageSlot.CENTER, message.slot)
        assertEquals(CaptureChromeTone.NORMAL, message.tone)
        assertEquals("カメラの許可が必要です。許可しない場合は、端末のアプリ設定からも変更できます。", message.body)
        assertEquals(MessageAction.GRANT_ACCESS, message.primary!!.action)
        assertEquals("カメラを許可", message.primary.label)
        assertNull(message.secondary)
    }

    @Test fun thermalFailureIsOnlyTheRecentRejectedPresentation() {
        val recent = captureMessages(thermalWarning = true, thermalRejected = true).banner!!
        assertEquals(CaptureChromeTone.FAILURE, recent.tone)
        assertEquals("端末が熱いため録画を開始できませんでした。温度が下がるまでお待ちください。", recent.body)
        assertEquals(CaptureChromeTone.WARNING, captureMessages(thermalWarning = true).banner!!.tone)
        assertNull(captureMessages().banner)
        assertNull(captureMessages(captureRecovery = true, thermalRejected = true).banner)
    }

    @Test fun rotationWaitingAndChangingTextAreNaturalAndNormal() {
        assertEquals("画面の向きに合わせて文字だけ回転します。", captureRotationNotice(false).text)
        assertEquals("画面の向きに合わせて文字だけ回転します。録画は続いています。", captureRotationNotice(true).text)
        for (recording in listOf(false, true)) assertEquals(CaptureChromeTone.NORMAL, captureRotationNotice(recording).tone)
        assertNull(captureWaitingNotice(true))
        assertEquals("撮影とプレビューの復帰を待っています。完了後に操作できます。", captureWaitingNotice(false)!!.text)
        assertEquals(CaptureChromeTone.NORMAL, captureWaitingNotice(false)!!.tone)
        assertEquals("設定を変更しています。", captureChangingMessage(emptyList()))
        assertEquals("ISO を変更しています。", captureChangingMessage(listOf("ISO")))
        assertEquals("ISO と SHUTTER を変更しています。", captureChangingMessage(listOf("ISO", "SHUTTER")))
        assertEquals("ISO、SHUTTER と WB を変更しています。", captureChangingMessage(listOf("ISO", "SHUTTER", "WB")))
    }
}
