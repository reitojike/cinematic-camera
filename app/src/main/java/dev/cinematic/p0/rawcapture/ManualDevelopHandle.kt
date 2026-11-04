package dev.cinematic.p0.rawcapture

import java.util.concurrent.CompletableFuture

/** Process-local invocation authority, independent of durable edit generation. No IO under this lock. */
internal class ManualDevelopControl(initiallyRequested: Boolean) {
    class Ticket internal constructor()
    private var epoch = Ticket()
    private var requested = initiallyRequested
    private var pendingPreview: PrivateTrialDelivery<*>? = null
    private var previewFenced = false
    @Synchronized fun begin(): Ticket { requested = true; return epoch }
    fun stop() {
        val pending = synchronized(this) { requested = false; epoch = Ticket(); pendingPreview }
        pending?.close() // Display-only software Bitmap, never a native decoder/FD or coordinator lock.
    }
    @Synchronized fun isRequested() = requested
    @Synchronized fun accepts(ticket: Ticket?) = requested && epoch === ticket
    @Synchronized fun canClaim(ticket: Ticket?) = accepts(ticket) && !previewFenced
    @Synchronized fun fencePreview(value: Boolean) { previewFenced = value }
    @Synchronized fun hasPendingPreview() = pendingPreview != null
    @Synchronized fun holdPreview(delivery: PrivateTrialDelivery<*>, ticket: Ticket): Boolean {
        if (!canClaim(ticket) || pendingPreview != null) return false
        pendingPreview = delivery
        return true
    }
    @Synchronized fun releasePreview(delivery: PrivateTrialDelivery<*>) {
        if (pendingPreview === delivery) pendingPreview = null
    }
}

/** Opaque retained connection to the queue's sole generation and publication owner. No file/factory API.
 * Futures acknowledge metadata/status, not resource release. PUBLISHED status is historical only. */
internal class ManualDevelopHandle internal constructor(private val queue: AutoDevelopQueue, val request: AutoDevelopRequest,
    private val control: ManualDevelopControl) {
    val quality get() = request.quality
    fun start(observations: AutoDevelopConditions): CompletableFuture<AutoDevelopRecord> {
        return queue.startManual(request, observations, control.begin())
    }
    fun status() = queue.manualStatus(request)
    /** A separate explicit user Save action after RENDERED. Does not regenerate the JPEG. */
    fun publish(observations: AutoDevelopConditions): CompletableFuture<AutoDevelopRecord> {
        return queue.publishManual(request, observations, control.begin())
    }
    fun requestStop() { try { control.stop() } finally { queue.stopManual(request) } }
    fun retry(): CompletableFuture<AutoDevelopRecord> {
        return queue.retryManual(request, control.begin())
    }
    /** Only this fresh same-owner result may supply a URI to the user's Open action. */
    fun confirmPublished(observations: AutoDevelopConditions, shouldStop: () -> Boolean): CompletableFuture<JpegPublicationRecord> {
        return queue.confirmManual(request, observations, control.begin(), shouldStop)
    }
}
