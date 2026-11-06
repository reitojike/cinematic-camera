package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.SharedOriginalReference

/** Identity only, never permission to read the provider or a receipt authorizing publication. */
internal data class PhotoRollPreviewKey(
    val source: SharedOriginalReference,
    val strength: Double,
    val adjustments: PhotoAdjustments,
    val lookVersion: String,
    val maxEdge: Int,
    val mode: PhotoRollPreviewMode,
)

/** Called under the bitmap cache's lock. Memory pressure/selection changes fence late writes. */
internal class PhotoRollPreviewRetention {
    data class Ticket(val owner: Any, val epoch: Long, val key: PhotoRollPreviewKey)
    private val owner = Any()
    private var epoch = 0L
    private var key: PhotoRollPreviewKey? = null
    fun begin(next: PhotoRollPreviewKey): Ticket {
        if (key != next) { clear(); key = next }
        return Ticket(owner, epoch, next)
    }
    fun accepts(ticket: Ticket) = ticket.owner === owner && ticket.epoch == epoch && ticket.key == key
    fun clear() { check(epoch < Long.MAX_VALUE); epoch++; key = null }
}
