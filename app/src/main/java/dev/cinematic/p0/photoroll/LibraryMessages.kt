package dev.cinematic.p0.photoroll

import dev.cinematic.p0.CaptureChromeTone
import dev.cinematic.p0.MessageAction
import dev.cinematic.p0.MessageControl
import dev.cinematic.p0.MessageSlot
import dev.cinematic.p0.ScreenMessages
import dev.cinematic.p0.UiMessage
import dev.cinematic.p0.screenMessages

/** Presentation snapshot only; never grants authority to cancel or restart a save. */
internal data class ForegroundSaveMessageState(
    val supported: Boolean,
    val active: Boolean,
    val held: Boolean,
    val stopping: Boolean,
    val foreground: Boolean,
    val notificationsAllowed: Boolean,
    val canRequestNotifications: Boolean,
)

/** Shared by libraryMessages and developMessages; callers retain their original action guards. */
internal fun foregroundSaveMessages(state: ForegroundSaveMessageState, activeOnly: Boolean = false): List<UiMessage> {
    if ((!state.supported && !state.active) || (activeOnly && !state.active)) return emptyList()
    val cancel = if (state.active) MessageControl(MessageAction.CANCEL_SAVE, "保存を取り消す",
        enabled = state.foreground && !state.stopping) else null
    val candidates = mutableListOf<UiMessage>()
    if (state.active) candidates += UiMessage(
        MessageSlot.BANNER,
        if (state.held) CaptureChromeTone.FAILURE else CaptureChromeTone.WARNING,
        when {
            state.held -> "保存の終了を確認できないため停止しています。次の保存は始めません。"
            state.stopping -> "保存を止めています。"
            else -> "JPEGを保存中です。アプリを離れても続きます。"
        },
        "save.foreground_progress", primary = cancel, inProgress = !state.held,
    )
    else candidates += UiMessage(MessageSlot.INLINE, CaptureChromeTone.NORMAL,
        "保存はアプリ切替後も継続します。撮影開始時は中断し、自動再開しません。", "save.background_policy")
    if (!state.notificationsAllowed) candidates += UiMessage(
        MessageSlot.BANNER, CaptureChromeTone.WARNING,
        "保存の通知はオフです。取消はこの画面から行えます。", "save.notifications_disabled",
        primary = if (state.canRequestNotifications) MessageControl(MessageAction.ALLOW_NOTIFICATIONS,
            "通知を許可", enabled = state.foreground) else null,
    )
    return candidates
}

/** Display-only library inputs. Loading, selection and permission authority remain with the caller. */
internal fun libraryMessages(
    access: CameraRollAccess,
    notice: String?,
    listingMessage: String?,
    empty: Boolean,
    permissionEnabled: Boolean,
    filesEnabled: Boolean,
    settingsEnabled: Boolean,
    save: List<UiMessage> = emptyList(),
): ScreenMessages = screenMessages(buildList {
    notice?.takeIf { it.isNotBlank() }?.let {
        add(UiMessage(MessageSlot.BANNER, CaptureChromeTone.FAILURE, it, "library.failure"))
    }
    addAll(save)
    if (!(access.images && access.videos)) add(UiMessage(
        MessageSlot.BANNER, CaptureChromeTone.WARNING,
        if (access.selected && !access.images && !access.videos) "選択した写真・動画だけにアクセスできます"
        else access.description,
        "library.access_limited",
        primary = MessageControl(MessageAction.CHOOSE_AGAIN,
            if (access.selected) "選び直す" else "アクセス許可・選び直す", permissionEnabled),
        secondary = MessageControl(MessageAction.OPEN_SETTINGS, "設定", settingsEnabled),
    ))
    listingMessage?.takeIf { it.isNotBlank() }?.let {
        add(UiMessage(MessageSlot.INLINE, CaptureChromeTone.NORMAL, it, "library.listing_note"))
    }
    if (empty) add(UiMessage(MessageSlot.CENTER, CaptureChromeTone.NORMAL,
        "該当する項目がありません。アクセス許可や絞り込みを確認してください。DNGはFilesからも選べます。",
        "library.empty", primary = MessageControl(MessageAction.GRANT_ACCESS, "アクセスを許可", permissionEnabled),
        secondary = MessageControl(MessageAction.OPEN_FILES, "Filesで開く", filesEnabled)))
})
