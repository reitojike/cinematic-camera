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
    private class Job(val journal: AutoDevelopJournal, val runner: AutoDevelopRunner, var enabled: Boolean = true) {
        @Volatile var observations: AutoDevelopConditions? = null
        val control = ManualDevelopControl(enabled)
        var publicationIntent: ManualDevelopControl.Ticket? = null // IO owner; never revived by a new start.
    }
    private val jobs = linkedMapOf<Key, Job>() // IO owner only; never remove a resource owner on cancel.
    private val latest = mutableMapOf<String, Long>()
    private val manualFences = mutableSetOf<String>() // Uncertain allocation must not leave an older edit runnable.
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
    fun stopObserved(attempt: AutoDevelopCoordinator.Attempt) {
        val active = resourceOwner?.second ?: return
        val target = active.stopTarget() ?: return
        if (target.slot === attempt) active.requestStop(target)
    }
    fun updateConditions(conditions: AutoDevelopConditions) {
        admission.updateConditions(conditions)
        val active = resourceOwner?.second
        if (active?.conditionsBlocked(conditions) == true) active.requestStop()
        wake()
    }

    fun submit(request: AutoDevelopRequest): CompletableFuture<AutoDevelopRecord> {
        require(request.quality != AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY) { "Use the explicit manual entry" }
        val active = resourceOwner
        if (active != null && active.first.captureId == request.captureId && active.first.generation < request.generation) {
            active.second.requestStop() // Supersession only; duplicate delivery/another capture must not cancel valid work.
        }
        return onIo { accept(request) }
    }
    private fun accept(request: AutoDevelopRequest, enabled: Boolean = true): AutoDevelopRecord {
        val key = Key(request.captureId, request.generation)
        val current = latest[request.captureId]
        if (current != null && request.generation < current) throw CaptureJournalConflict()
        val existing = jobs[key]
        val journal = existing?.journal ?: journalFactory(request)
        val record = journal.prepare(request)
        if (existing == null) {
            latest[request.captureId] = request.generation
            jobs[key] = Job(journal, AutoDevelopRunner(journal, admission, backendFactory(request), cancellationIo, ::wake,
                interactive = request.boundedManual, observations = { jobs[key]?.observations },
                allowPublication = { !request.boundedManual || jobs[key]?.let { it.control.accepts(it.publicationIntent) } == true }) {
                latest[request.captureId] == request.generation && request.captureId !in manualFences &&
                    (!request.boundedManual || jobs[key]?.control?.isRequested() == true)
            }, enabled)
        }
        wake()
        return record
    }
    /** Allocation and immutable generation acceptance share the existing IO owner. No render on prepare. */
    fun prepareManual(captureId: String, allocate: () -> AutoDevelopRequest): CompletableFuture<ManualDevelopHandle> = onIo {
        requireCaptureId(captureId)
        manualFences.add(captureId)
        val request = try { allocate() } catch (failure: Throwable) {
            resourceOwner?.takeIf { it.first.captureId == captureId }?.second?.requestStop()
            throw failure
        }
        require(request.boundedManual && request.captureId == captureId && captureId == ManualDevelopRequests.identity(request.source))
        resourceOwner?.takeIf { it.first.captureId == captureId && it.first.generation < request.generation }?.second?.requestStop()
        accept(request, enabled = false)
        manualFences.remove(captureId)
        ManualDevelopHandle(this, request, currentManual(request).control)
    }
    internal fun startManual(request: AutoDevelopRequest, observations: AutoDevelopConditions,
        ticket: ManualDevelopControl.Ticket): CompletableFuture<AutoDevelopRecord> = onIo {
        val job = currentManual(request)
        requireIntent(job, ticket)
        if (!job.runner.supports(request)) throw AutoRenderUnsupported()
        job.observations = observations
        job.enabled = true
        (job.journal.read() ?: throw CaptureJournalConflict()).also { wake() }
    }
    internal fun manualStatus(request: AutoDevelopRequest): CompletableFuture<AutoDevelopRecord> = onIo {
        currentManual(request).journal.read() ?: throw CaptureJournalConflict()
    }
    /** Explicit user Save intent; first publication is never implied by generation or status. */
    internal fun publishManual(request: AutoDevelopRequest, observations: AutoDevelopConditions,
        ticket: ManualDevelopControl.Ticket): CompletableFuture<AutoDevelopRecord> = onIo {
        val job = currentManual(request)
        requireIntent(job, ticket)
        val record = job.journal.read() ?: throw CaptureJournalConflict()
        if (record.phase !in setOf(AutoJobPhase.RENDERED, AutoJobPhase.PUBLISHING, AutoJobPhase.PUBLISHED)) throw CaptureJournalConflict()
        job.observations = observations
        job.publicationIntent = ticket
        job.enabled = true
        record.also { wake() }
    }
    internal fun stopManual(request: AutoDevelopRequest) {
        // Do not read the IO-owned map from the UI thread or stop another generation's resource owner.
        resourceOwner?.takeIf { it.first.captureId == request.captureId && it.first.generation == request.generation }
            ?.second?.requestStop()
    }
    internal fun confirmManual(request: AutoDevelopRequest, observations: AutoDevelopConditions,
        ticket: ManualDevelopControl.Ticket, shouldStop: () -> Boolean): CompletableFuture<JpegPublicationRecord> = onIo {
        val job = currentManual(request)
        requireIntent(job, ticket)
        job.observations = observations
        val held = resourceOwner
        if (held != null && held.second !== job.runner && held.second.hasUnreleasedResources) throw CaptureJournalConflict()
        resourceOwner = Key(request.captureId, request.generation) to job.runner
        try { job.runner.confirmPublished { !job.control.accepts(ticket) || shouldStop() } }
        finally { if (!job.runner.hasUnreleasedResources) resourceOwner = null }
    }
    private fun currentManual(request: AutoDevelopRequest): Job {
        require(request.boundedManual)
        val job = jobs[Key(request.captureId, request.generation)] ?: throw CaptureJournalConflict()
        if (request.captureId in manualFences || latest[request.captureId] != request.generation || job.journal.read()?.request != request) throw CaptureJournalConflict()
        return job
    }
    private fun requireIntent(job: Job, ticket: ManualDevelopControl.Ticket) {
        if (!job.control.accepts(ticket)) throw java.util.concurrent.CancellationException("Manual invocation cancelled")
    }
    /** Rebuild only on a new process owner; a live owner's handles must never be bypassed. */
    fun restore(requests: List<AutoDevelopRequest>): CompletableFuture<Unit> = onIo {
        if (jobs.isNotEmpty()) throw IllegalStateException("Auto-develop owner already initialized")
        val restoredJobs = linkedMapOf<Key, Job>()
        val restoredLatest = mutableMapOf<String, Long>()
        requests.sortedBy { it.generation }.forEach { request ->
            val key = Key(request.captureId, request.generation)
            if (key in restoredJobs) throw CaptureJournalConflict()
            val journal = journalFactory(request)
            val record = journal.recoverAfterProcessDeath() ?: throw CaptureJournalConflict()
            if (record.request != request) throw CaptureJournalConflict()
            restoredLatest[request.captureId] = maxOf(restoredLatest[request.captureId] ?: 0, request.generation)
            restoredJobs[key] = Job(journal, AutoDevelopRunner(journal, admission, backendFactory(request), cancellationIo, ::wake,
                interactive = request.boundedManual, observations = { jobs[key]?.observations },
                allowPublication = { !request.boundedManual || jobs[key]?.let { it.control.accepts(it.publicationIntent) } == true }) {
                latest[request.captureId] == request.generation && request.captureId !in manualFences &&
                    (!request.boundedManual || jobs[key]?.control?.isRequested() == true)
            }, enabled = !request.boundedManual)
        }
        // An unreadable newer definition must not leave an older partial inventory runnable.
        latest.putAll(restoredLatest)
        jobs.putAll(restoredJobs)
        wake()
    }
    fun retry(captureId: String, generation: Long): CompletableFuture<AutoDevelopRecord> = onIo {
        val job = jobs[Key(captureId, generation)] ?: throw CaptureJournalConflict()
        if (job.journal.read()?.request?.boundedManual == true) throw CaptureJournalConflict()
        retryJob(captureId, generation, job)
    }
    internal fun retryManual(request: AutoDevelopRequest, ticket: ManualDevelopControl.Ticket): CompletableFuture<AutoDevelopRecord> = onIo {
        val job = currentManual(request)
        requireIntent(job, ticket)
        retryJob(request.captureId, request.generation, job)
    }
    private fun retryJob(captureId: String, generation: Long, job: Job): AutoDevelopRecord {
        if (latest[captureId] != generation || job.runner.hasUnreleasedResources) throw CaptureJournalConflict()
        val current = job.journal.read() ?: throw CaptureJournalConflict()
        return job.journal.retry(current.revision).also { job.enabled = true; wake() }
    }
    fun wake() {
        if (!scheduled.compareAndSet(false, true)) return
        try {
            io.execute {
                scheduled.set(false)
                jobs.entries.forEach { (key, job) ->
                    // Old owners must still close; old generations must not start new work.
                    if ((job.enabled && job.control.isRequested() && key.captureId !in manualFences && latest[key.captureId] == key.generation) || job.runner.hasUnreleasedResources) {
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
