package dev.cinematic.p0.photoroll

internal fun canOpenPhotoRoll(busy: Boolean, recordingBusy: Boolean, ownsCapture: Boolean, unknown: Boolean) =
    !busy && !recordingBusy && !ownsCapture && !unknown

internal enum class PhotoRollPhase { EMPTY, CHECKING, RENDERING, DISPLAYED, BLOCKED, PAUSED }
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
