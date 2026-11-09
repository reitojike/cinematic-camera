package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopBlocker
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy

/** Explain the actual admission blockers without changing the shared fail-closed policy. */
internal fun photoRollAdmissionFailure(conditions: AutoDevelopConditions): String? {
    val blockers = AutoDevelopPolicy.blockers(conditions)
    if (blockers.isEmpty()) return null
    val reasons = blockers.map { when (it) {
        AutoDevelopBlocker.FOREGROUND -> "編集画面が前面にありません"
        AutoDevelopBlocker.CAMERA_SESSION -> if (conditions.cameraSessionInUse == true)
            "カメラが使用中、または撮影処理が継続中です" else "カメラの解放状態を確認できません"
        AutoDevelopBlocker.RECORDING_OR_FINALIZING -> if (conditions.recordingOrFinalizing == true)
            "録画または終了処理中です" else "録画の終了状態を確認できません"
        AutoDevelopBlocker.REBIND -> if (conditions.rebindInProgress == true)
            "カメラを再接続しています" else "カメラの再接続状態を確認できません"
        AutoDevelopBlocker.THERMAL -> if (conditions.thermalAllowed == false)
            "端末が高温です。冷めるまでお待ちください" else "端末の温度状態を確認できません"
        AutoDevelopBlocker.STORAGE -> if (conditions.storageAllowed == false)
            "作業用の空き容量が不足しています" else "作業用の空き容量を確認できません"
        AutoDevelopBlocker.RENDERER -> "現像処理を利用できません"
        AutoDevelopBlocker.MEMORY -> if (conditions.memoryBudgetConfirmed == false)
            "現像に必要な空きメモリが不足しています" else "現像に必要な空きメモリを確認できません"
    } }
    return "現像を開始できません。" + reasons.joinToString("。") + "。状態を確認して再試行してください"
}
