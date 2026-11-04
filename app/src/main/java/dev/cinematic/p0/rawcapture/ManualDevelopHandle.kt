package dev.cinematic.p0.rawcapture

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Opaque retained connection to the queue's sole generation and publication owner. No file/factory API.
 * Futures acknowledge metadata/status, not resource release. PUBLISHED status is historical only. */
internal class ManualDevelopHandle internal constructor(private val queue: AutoDevelopQueue, val request: AutoDevelopRequest,
    private val requested: AtomicBoolean) {
    val quality get() = request.quality
    fun start(observations: AutoDevelopConditions): CompletableFuture<AutoDevelopRecord> {
        requested.set(true)
        return queue.startManual(request, observations)
    }
    fun status() = queue.manualStatus(request)
    /** A separate explicit user Save action after RENDERED. Does not regenerate the JPEG. */
    fun publish(observations: AutoDevelopConditions): CompletableFuture<AutoDevelopRecord> {
        requested.set(true)
        return queue.publishManual(request, observations)
    }
    fun requestStop() { requested.set(false); queue.stopManual(request) }
    fun retry(): CompletableFuture<AutoDevelopRecord> {
        requested.set(true) // Intent precedes enqueue; a later stop must remain false on the IO worker.
        return queue.retry(request.captureId, request.generation)
    }
    /** Only this fresh same-owner result may supply a URI to the user's Open action. */
    fun confirmPublished(observations: AutoDevelopConditions, shouldStop: () -> Boolean): CompletableFuture<JpegPublicationRecord> {
        requested.set(true)
        return queue.confirmManual(request, observations, shouldStop)
    }
}
