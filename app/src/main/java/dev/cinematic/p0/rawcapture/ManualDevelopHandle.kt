package dev.cinematic.p0.rawcapture

import java.util.concurrent.CompletableFuture

/** Process-local invocation authority, independent of durable edit generation. No IO under this lock. */
internal class ManualDevelopControl(initiallyRequested: Boolean) {
    class Ticket internal constructor()
    private var epoch = Ticket()
    private var requested = initiallyRequested
    @Synchronized fun begin(): Ticket { requested = true; return epoch }
    @Synchronized fun stop() { requested = false; epoch = Ticket() }
    @Synchronized fun isRequested() = requested
    @Synchronized fun accepts(ticket: Ticket?) = requested && epoch === ticket
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
    fun requestStop() { control.stop(); queue.stopManual(request) }
    fun retry(): CompletableFuture<AutoDevelopRecord> {
        return queue.retryManual(request, control.begin())
    }
    /** Only this fresh same-owner result may supply a URI to the user's Open action. */
    fun confirmPublished(observations: AutoDevelopConditions, shouldStop: () -> Boolean): CompletableFuture<JpegPublicationRecord> {
        return queue.confirmManual(request, observations, control.begin(), shouldStop)
    }
}
