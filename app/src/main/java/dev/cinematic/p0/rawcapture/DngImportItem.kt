package dev.cinematic.p0.rawcapture

/** Terminal byte state does not imply that an owned read grant has been released. */
internal data class DngImportItem(val id: String, val ready: Boolean, val cancelled: Boolean,
    val grantPending: Boolean)

internal fun DngImportRecord.importItem(): DngImportItem? {
    val ready = phase == DngImportPhase.READY
    val cancelled = phase == DngImportPhase.CANCELLED
    val grantPending = (ready || cancelled) && grant == DngImportGrant.ACQUIRED
    return if (cancelled && !grantPending) null
        else DngImportItem(target.operationId, ready, cancelled, grantPending)
}
