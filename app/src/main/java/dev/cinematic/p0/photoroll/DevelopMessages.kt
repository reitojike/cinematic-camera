package dev.cinematic.p0.photoroll

import dev.cinematic.p0.*
import dev.cinematic.p0.gpu.GpuFailureHandling

/** Read-only display inputs. Action eligibility is supplied by the existing owner, not inferred here. */
internal data class DevelopMessageState(
    val phase: PhotoRollPhase,
    val savePhase: PhotoRollSavePhase? = null,
    val exportPaused: Boolean = false,
    val saving: Boolean = false,
    val savingRecipe: Boolean = false,
    val editing: Boolean = false,
    val foreground: Boolean = true,
    val memoryProblem: UiMessage? = null,
    val gpuFailure: GpuFailureHandling? = null,
    val message: String? = null,
    val saveFailureMessage: String? = null,
    val canResumePreview: Boolean = false,
    val canLeave: Boolean = true,
    val canRecover: Boolean = false,
    val retryEnabled: Boolean = false,
    val rechecking: Boolean = false,
    val justPublished: Boolean = false,
)

internal data class DevelopSavePresentation(
    val label: String,
    val tone: CaptureChromeTone,
    val icon: String,
    val description: String,
)

/** GPU product presentation only. The legacy CPU helper and save button eligibility stay unchanged. */
internal fun developSavePresentation(state: DevelopMessageState): DevelopSavePresentation {
    fun value(label: String, tone: CaptureChromeTone, icon: String, description: String) =
        DevelopSavePresentation(label, tone, icon, description)
    return when {
        state.rechecking -> value("保存照合中", CaptureChromeTone.WARNING, "↻", "保存結果を確認中")
        state.savingRecipe -> value("設定保存中", CaptureChromeTone.WARNING, "↓", "調整設定を保存中")
        state.saving || state.savePhase == PhotoRollSavePhase.SAVING ->
            value("保存中", CaptureChromeTone.WARNING, "↓", "保存中")
        state.editing -> value("調整中", CaptureChromeTone.NORMAL, "↓", "調整を閉じると保存できます")
        else -> when (state.savePhase) {
            null -> value("保存準備中", CaptureChromeTone.NORMAL, "↓", "JPEGを保存（準備中）")
            PhotoRollSavePhase.READY -> value("未保存", CaptureChromeTone.READY, "↓", "JPEGを保存")
            PhotoRollSavePhase.SAVING -> value("保存中", CaptureChromeTone.WARNING, "↓", "保存中")
            PhotoRollSavePhase.PUBLISHED -> value("保存済み", CaptureChromeTone.ACCEPTED, "↗",
                "保存したJPEGを開く（開く前に照合します）")
            PhotoRollSavePhase.CANCELLED -> value("保存取消", CaptureChromeTone.WARNING, "↻",
                "同じ設定で保存を再試行")
            PhotoRollSavePhase.UNCONFIRMED -> value("保存未確認", CaptureChromeTone.FAILURE, "↻",
                "保存結果をもう一度確認")
            PhotoRollSavePhase.GPU_FAILED -> value("保存失敗", CaptureChromeTone.FAILURE, "↻",
                "同じ設定で保存を再試行")
            PhotoRollSavePhase.HOLD -> value("保存保留", CaptureChromeTone.FAILURE, "↓", "保存保留中")
        }
    }
}

/** Pure assembly. No subscriptions, cache disposal, save commands, state transitions, or toast timers. */
internal fun developMessages(
    state: DevelopMessageState,
    foregroundMessages: List<UiMessage> = emptyList(),
    reportInvalid: ((String) -> Unit)? = null,
): ScreenMessages {
    val candidates = mutableListOf<UiMessage>()
    val presentation = developSavePresentation(state)
    candidates += UiMessage(MessageSlot.CHIP, presentation.tone, presentation.label, "save.summary")
    val resume = MessageControl(MessageAction.RESUME_PREVIEW, "プレビューを再開", state.canResumePreview)
    val recover = MessageControl(MessageAction.RECHECK_SAVE, "保存結果を確認・保存し直す", state.canRecover)
    val retry = MessageControl(MessageAction.RETRY, "再試行", state.retryEnabled)
    val uncertain = state.savePhase in setOf(PhotoRollSavePhase.HOLD, PhotoRollSavePhase.UNCONFIRMED)

    // Uncertain publication owns the recovery path even if a later memory failure is also present.
    when {
        state.rechecking -> candidates += UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
            "保存結果を確認しています。", "save.reconciliation", inProgress = true)
        uncertain && state.savePhase == PhotoRollSavePhase.HOLD -> candidates += UiMessage(
            MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
            "保存先の状態を確認できません。保存済みとは扱いません。",
            "save.uncertain", title = "保存結果を確認できません", primary = recover)
        uncertain -> candidates += UiMessage(MessageSlot.BANNER, CaptureChromeTone.FAILURE,
            "保存できたか確認できません。もう一度確認してください。", "save.uncertain", primary = recover)
        state.memoryProblem != null -> candidates += state.memoryProblem.copy(
            title = "処理を続けられません",
            body = "処理に必要なメモリが不足しています。時間をおいて再試行してください。",
            note = "原本は変更していません。",
        )
        state.phase == PhotoRollPhase.BLOCKED -> candidates += UiMessage(
            MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
            state.gpuFailure?.reason ?: state.message ?: "画像を表示できません。再試行してください。",
            "develop.failure", title = state.gpuFailure?.title ?: "画像を表示できません", primary = retry)
        state.savePhase == PhotoRollSavePhase.GPU_FAILED -> candidates += UiMessage(
            MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
            state.saveFailureMessage ?: "JPEGの保存に失敗しました。再試行してください。",
            "save.failure", title = "JPEGを保存できませんでした", primary = resume)
        state.savePhase == PhotoRollSavePhase.CANCELLED -> candidates += UiMessage(
            MessageSlot.BANNER, CaptureChromeTone.WARNING,
            "保存を取り消しました。同じ設定で再試行するか、プレビューに戻って調整できます。",
            "save.cancelled", primary = resume)
    }

    // These are deliberately different facts: no image because preview is paused vs JPEG progress.
    if (state.exportPaused) {
        val writing = !state.rechecking && !state.savingRecipe &&
            (state.saving || state.savePhase == PhotoRollSavePhase.SAVING)
        candidates += UiMessage(MessageSlot.CENTER,
            if (writing) CaptureChromeTone.WARNING else CaptureChromeTone.NORMAL,
            if (writing) "保存中 · プレビューを止めています" else "保存のためプレビューを止めました",
            "preview.export_paused",
            primary = resume)
    } else when (state.phase) {
        PhotoRollPhase.EMPTY, PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING ->
            candidates += UiMessage(MessageSlot.CENTER, CaptureChromeTone.NORMAL,
                "画像を準備しています。", "preview.preparing")
        PhotoRollPhase.PAUSED -> candidates += UiMessage(MessageSlot.CENTER, CaptureChromeTone.WARNING,
            "プレビューを止めています。", "preview.paused", primary = retry)
        else -> Unit
    }
    // The caller supplies one existing FGS snapshot; do not synthesize cancellability from a phase.
    candidates += foregroundMessages
    if (state.justPublished && state.savePhase == PhotoRollSavePhase.PUBLISHED)
        candidates += UiMessage(MessageSlot.TOAST, CaptureChromeTone.ACCEPTED,
            "JPEGを保存しました。", "save.published")
    val fallback = developFailureFallback(state.canLeave)
    val selected = if (reportInvalid == null) screenMessages(candidates, failureFallback = fallback)
        else screenMessages(candidates, report = reportInvalid, failureFallback = fallback)
    // Deduplicate only against the chosen banner. A higher-priority save from another photo
    // can replace the cancellation banner; its unrelated action must not hide preview recovery.
    val center = selected.center
    val bannerHasResume = listOfNotNull(selected.banner?.primary, selected.banner?.secondary)
        .any { it.action == MessageAction.RESUME_PREVIEW }
    return if (center?.slot == MessageSlot.CENTER &&
        center.primary?.action == MessageAction.RESUME_PREVIEW && bannerHasResume)
        selected.copy(center = center.copy(primary = null))
    else selected
}

/** Only returns through the existing navigation handler; never retries or releases a held save. */
internal fun developFailureFallback(canLeave: Boolean) = UiMessage(
    MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
    "画面の表示を確認できません。一覧に戻ってください。", "develop.invalid_failure",
    title = "現像を続けられません",
    primary = MessageControl(MessageAction.BACK_TO_LIST, "一覧に戻る", canLeave),
)
