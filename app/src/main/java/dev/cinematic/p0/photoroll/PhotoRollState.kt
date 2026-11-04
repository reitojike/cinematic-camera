package dev.cinematic.p0.photoroll

internal fun canOpenPhotoRoll(busy: Boolean, recordingBusy: Boolean, ownsCapture: Boolean, unknown: Boolean) =
    !busy && !recordingBusy && !ownsCapture && !unknown

internal enum class PhotoRollPhase { EMPTY, CHECKING, RENDERING, DISPLAYED, BLOCKED, PAUSED }
internal enum class PhotoRollPreviewMode { STAGE_CLEAR, DNG_TRIAL }

internal data class PhotoRollResources(
    val camerasIdle: Boolean?, val thermalStatus: Int?, val lowMemory: Boolean?, val availableBytes: Long?,
) {
    fun blockedReason(): String? = when {
        camerasIdle != true -> "カメラの解放を確認できません。撮影を終了してから再試行してください"
        thermalStatus == null || thermalStatus !in 0..6 -> "端末の温度状態を確認できません"
        thermalStatus >= 3 -> "端末が高温です。冷めてから再試行してください"
        lowMemory != false || availableBytes == null || availableBytes < REQUIRED_AVAILABLE_BYTES ->
            "試験表示に必要な空きメモリを確認できません。他のアプリを閉じて再試行してください"
        else -> null
    }
    companion object { const val REQUIRED_AVAILABLE_BYTES = 384L * 1024 * 1024 }
}

/** Main-thread owner. Cancellation requests do not claim a worker has released its permit. */
internal class PhotoRollWorkOwner<T : Any>(private val cancelToken: (T) -> Unit) {
    private var active: T? = null
    fun begin(token: T) { cancel(); active = token }
    fun cancel() { active?.let(cancelToken) }
    fun finish(token: T) { if (active === token) active = null }
}
internal data class PhotoRollTicket(val generation: Long, val source: String)

/** Main-thread state. A URI alone is never a completion token. */
internal data class PhotoRollSelection(
    val generation: Long = 0,
    val source: String? = null,
    val phase: PhotoRollPhase = PhotoRollPhase.EMPTY,
    val message: String? = null,
) {
    val ticket get() = source?.let { PhotoRollTicket(generation, it) }
    fun select(source: String) = PhotoRollSelection(generation + 1, source, PhotoRollPhase.CHECKING)
    fun rendering(ticket: PhotoRollTicket): PhotoRollSelection =
        if (accepts(ticket) && phase == PhotoRollPhase.CHECKING) copy(phase = PhotoRollPhase.RENDERING) else this
    fun finish(ticket: PhotoRollTicket, displayed: Boolean, message: String): PhotoRollSelection =
        if (accepts(ticket)) copy(phase = if (displayed) PhotoRollPhase.DISPLAYED else PhotoRollPhase.BLOCKED, message = message) else this
    fun pause() = copy(generation = generation + 1, phase = if (source == null) PhotoRollPhase.EMPTY else PhotoRollPhase.PAUSED,
        message = if (source == null) null else "処理を中断しました。再開時に原本を確認し直します")
    fun clear() = PhotoRollSelection(generation = generation + 1)
    fun accepts(ticket: PhotoRollTicket) = this.ticket == ticket && phase in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING)
}

/** Conservative local-provider policy; an SD document needs the future verified import path. */
internal fun isLocalDngDocument(authority: String?, documentId: String?): Boolean = when (authority) {
    "com.android.externalstorage.documents" -> documentId?.startsWith("primary:") == true
    else -> false
}
