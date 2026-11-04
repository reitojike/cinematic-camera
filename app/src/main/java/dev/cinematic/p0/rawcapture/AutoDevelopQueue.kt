package dev.cinematic.p0.rawcapture

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One process owner and one serial IO executor. No implicit retry loop or UI/capture-thread IO.
 * Generation submissions are serialized with publication. requestStop/reserveCapture bypass this
 * executor and touch only the coordinator's short lock, even when render/provider/close is blocked.
 */
internal class AutoDevelopQueue(
    private val io: Executor,
    private val admission: AutoDevelopCoordinator,
    private val journalFactory: (AutoDevelopRequest) -> AutoDevelopJournal,
    private val backendFactory: (AutoDevelopRequest) -> AutoDevelopBackend,
    private val cancellationIo: Executor = AutoDevelopCancellation.io,
) {
    private data class Key(val captureId: String, val generation: Long)
    private data class Job(val journal: AutoDevelopJournal, val runner: AutoDevelopRunner)
    private val jobs = linkedMapOf<Key, Job>() // IO owner only; never remove a resource owner on cancel.
    private val latest = mutableMapOf<String, Long>()
    private val scheduled = AtomicBoolean(false)
    @Volatile private var resourceOwner: Pair<Key, AutoDevelopRunner>? = null

    fun reserveCapture(): AutoDevelopCoordinator.CaptureReservation {
        val reservation = admission.reserveCapture()
        resourceOwner?.second?.requestStop()
        return reservation
    }
    fun releaseCapture(reservation: AutoDevelopCoordinator.CaptureReservation) {
        admission.releaseCapture(reservation)
        wake()
    }
    fun requestStop() { admission.requestStop(); resourceOwner?.second?.requestStop() }
    fun updateConditions(conditions: AutoDevelopConditions) {
        admission.updateConditions(conditions)
        if (AutoDevelopPolicy.blockers(conditions).isNotEmpty()) resourceOwner?.second?.requestStop()
        wake()
    }

    fun submit(request: AutoDevelopRequest): CompletableFuture<AutoDevelopRecord> {
        val active = resourceOwner
        if (active != null && active.first.captureId == request.captureId && active.first.generation < request.generation) {
            active.second.requestStop() // Supersession only; duplicate delivery/another capture must not cancel valid work.
        }
        return onIo {
            val key = Key(request.captureId, request.generation)
            val current = latest[request.captureId]
            if (current != null && request.generation < current) throw CaptureJournalConflict()
            val existing = jobs[key]
            val journal = existing?.journal ?: journalFactory(request)
            val record = journal.prepare(request)
            if (existing == null) {
                latest[request.captureId] = request.generation
                jobs[key] = Job(journal, AutoDevelopRunner(journal, admission, backendFactory(request), cancellationIo, ::wake) {
                    latest[request.captureId] == request.generation
                })
            }
            wake()
            record
        }
    }
    /** Rebuild only on a new process owner; a live owner's handles must never be bypassed. */
    fun restore(requests: List<AutoDevelopRequest>): CompletableFuture<Unit> = onIo {
        if (jobs.isNotEmpty()) throw IllegalStateException("Auto-develop owner already initialized")
        requests.sortedBy { it.generation }.forEach { request ->
            val key = Key(request.captureId, request.generation)
            if (key in jobs) throw CaptureJournalConflict()
            val journal = journalFactory(request)
            val record = journal.recoverAfterProcessDeath() ?: throw CaptureJournalConflict()
            if (record.request != request) throw CaptureJournalConflict()
            latest[request.captureId] = maxOf(latest[request.captureId] ?: 0, request.generation)
            jobs[key] = Job(journal, AutoDevelopRunner(journal, admission, backendFactory(request), cancellationIo, ::wake) {
                latest[request.captureId] == request.generation
            })
        }
        wake()
    }
    fun retry(captureId: String, generation: Long): CompletableFuture<AutoDevelopRecord> = onIo {
        val job = jobs[Key(captureId, generation)] ?: throw CaptureJournalConflict()
        if (latest[captureId] != generation || job.runner.hasUnreleasedResources) throw CaptureJournalConflict()
        val current = job.journal.read() ?: throw CaptureJournalConflict()
        job.journal.retry(current.revision).also { wake() }
    }
    fun wake() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            io.execute {
                scheduled.set(false)
                jobs.entries.forEach { (key, job) ->
                    // Old owners must still close; old generations must not start new work.
                    if (latest[key.captureId] == key.generation || job.runner.hasUnreleasedResources) {
                        val held = resourceOwner
                        if (held != null && held.second !== job.runner && held.second.hasUnreleasedResources) return@forEach
                        resourceOwner = key to job.runner
                        try { job.runner.advance() } catch (_: Exception) { /* Preserve journal and owner; next external wake can reconcile. */ }
                        if (!job.runner.hasUnreleasedResources) resourceOwner = null
                    }
                }
            }
        } catch (failure: RuntimeException) { scheduled.set(false); throw failure }
    }
    private fun <T> onIo(action: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        try { io.execute { try { future.complete(action()) } catch (t: Throwable) { future.completeExceptionally(t) } } }
        catch (t: Throwable) { future.completeExceptionally(t) }
        return future
    }
}
