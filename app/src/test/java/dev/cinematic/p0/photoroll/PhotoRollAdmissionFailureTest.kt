package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import org.junit.Assert.*
import org.junit.Test

class PhotoRollAdmissionFailureTest {
    private val ready = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Test fun readyHasNoFailureMessage() { assertNull(photoRollAdmissionFailure(ready)) }

    @Test fun eachBlockerIsIdentifiedWithoutCallingEveryFailureACaptureProblem() {
        val cases = listOf(
            ready.copy(foreground = false) to "前面",
            ready.copy(cameraSessionInUse = true) to "撮影処理",
            ready.copy(recordingOrFinalizing = true) to "録画または終了処理",
            ready.copy(rebindInProgress = true) to "再接続",
            ready.copy(thermalAllowed = false) to "高温",
            ready.copy(storageAllowed = false) to "空き容量",
            ready.copy(rendererAvailable = false) to "現像処理",
            ready.copy(memoryBudgetConfirmed = false) to "空きメモリ",
        )
        for ((conditions, reason) in cases) assertTrue(requireNotNull(photoRollAdmissionFailure(conditions)).contains(reason))
        assertFalse(requireNotNull(photoRollAdmissionFailure(ready.copy(thermalAllowed = false))).contains("撮影"))
        assertFalse(requireNotNull(photoRollAdmissionFailure(ready.copy(memoryBudgetConfirmed = false))).contains("撮影"))
    }

    @Test fun unknownResourcesAreNotPresentedAsObservedHeatOrMemoryShortage() {
        val unknown = requireNotNull(photoRollAdmissionFailure(ready.copy(thermalAllowed = null, memoryBudgetConfirmed = null)))
        assertTrue(unknown.contains("温度状態を確認できません"))
        assertTrue(unknown.contains("空きメモリを確認できません"))
        assertFalse(unknown.contains("高温"))
        assertFalse(unknown.contains("不足"))
    }

    @Test fun unknownOwnerRetainsAllApplicationWorkReasons() {
        val unknown = requireNotNull(photoRollAdmissionFailure(ready.copy(
            cameraSessionInUse = null, recordingOrFinalizing = null, rebindInProgress = null)))
        assertTrue(unknown.contains("カメラの解放状態を確認できません"))
        assertTrue(unknown.contains("録画の終了状態を確認できません"))
        assertTrue(unknown.contains("カメラの再接続状態を確認できません"))
    }
}
