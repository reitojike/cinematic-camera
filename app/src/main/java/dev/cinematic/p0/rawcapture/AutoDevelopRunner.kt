package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AutoRunStatus { BLOCKED, RETRYABLE, HELD, PUBLICATION_PENDING, PUBLISHED, RESOURCES_HELD, PRIVATE_READY }
internal data class AutoRunResult(val status: AutoRunStatus, val record: AutoDevelopRecord?)

internal interface AutoDevelopPublication {
    val hasUnreleasedResources: Boolean
    fun releaseResources()
    fun advance(shouldStop: () -> Boolean): JpegPublicationPhase
    fun confirmPublished(shouldStop: () -> Boolean): JpegPublicationRecord = throw IOException("Fresh confirmation unavailable")
}
internal class RecoverableAutoPublication(private val publisher: RecoverablePhotoPublisher) : AutoDevelopPublication {
    override val hasUnreleasedResources get() = publisher.hasUnreleasedResources
    override fun releaseResources() = publisher.releaseResources()
    override fun advance(shouldStop: () -> Boolean) = publisher.advance(shouldStop).phase
    override fun confirmPublished(shouldStop: () -> Boolean) = publisher.advance(shouldStop)
}

/** Read-only original + attempt-local output. Implementations retain failed closes, not just their counts. */
internal interface AutoDevelopBackend {
    val hasUnreleasedResources: Boolean
    fun releaseResources()
    fun supports(request: AutoDevelopRequest): Boolean
    fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean)
    fun createAttempt(request: AutoDevelopRequest, attemptId: String): AutoRenderAttempt
    fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean): JpegInspection
    fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication
    fun privatePreview(record: AutoDevelopRecord, shouldStop: () -> Boolean): PrivateTrialPreview = throw IOException("Private trial readback unavailable")
    /** Capture the current readback's cancellation target, never a lookup that can stop a later read. */
    fun privatePreviewStopAction(): (() -> Unit)? = null
}

/**
 * One retained runner/backend per generation, using the process coordinator. Call on the single IO
 * owner, never on UI/capture callbacks. Submission/generation changes use that same owner; capture
 * reservation and requestStop use ONLY AutoDevelopCoordinator's short lock and never wait for IO.
 */
internal class AutoDevelopRunner(
    private val journal: AutoDevelopJournal,
    private val admission: AutoDevelopCoordinator,
    private val backend: AutoDevelopBackend,
    private val cancellationIo: Executor = AutoDevelopCancellation.io,
    private val onResourcesMayClose: () -> Unit = {},
    private val interactive: Boolean = false,
    private val observations: () -> AutoDevelopConditions? = { null },
    private val allowPublication: () -> Boolean = { true },
    private val isCurrent: () -> Boolean,
) {
    private val advancing = AtomicBoolean(false)
    @Volatile private var slot: AutoDevelopCoordinator.Attempt? = null
    private var attempt: AutoRenderAttempt? = null
    private val ownership = Any()
    private var closing = false
    private var cancelSent = false
    private var cancelCalls = 0
    private var receipt: AutoRenderReceipt? = null
    private var renderPending = false
    private var renderFailure: Throwable? = null
    private var renderAttemptId: String? = null
    private data class RenderDecision(val attemptId: String, val phase: AutoJobPhase, val reason: AutoJobReason,
        val fingerprint: OriginalFingerprint? = null, val observed: AutoRenderReceipt? = null)
    private var renderDecision: RenderDecision? = null
    private var publication: AutoDevelopPublication? = null
    val hasUnreleasedResources get() = slot != null
    fun supports(request: AutoDevelopRequest) = request.admissible && backend.supports(request)
    fun conditionsBlocked(conditions: AutoDevelopConditions) =
        AutoDevelopPolicy.blockers(if (interactive) conditions.copy(rendererAvailable = true) else conditions).isNotEmpty()

    /** Process-local cancellation intent. A delayed caller must retain the original owner+slot pair. */
    class StopTarget internal constructor(internal val owner: AutoDevelopRunner, internal val slot: AutoDevelopCoordinator.Attempt)
    fun stopTarget(): StopTarget? = slot?.let { StopTarget(this, it) }

    /** Never invoke adapter/native cancellation on the capture/UI caller or the occupied renderer executor. */
    fun requestStop(requested: StopTarget? = stopTarget()) {
        if (requested == null || requested.owner !== this) return
        admission.requestStop(requested.slot)
        val target = synchronized(ownership) {
            if (slot !== requested.slot) return
            if (closing || cancelSent) return
            val stop = attempt?.let { owner -> { owner.requestStop() } } ?: backend.privatePreviewStopAction() ?: return
            cancelSent = true
            cancelCalls++
            stop
        }
        try {
            cancellationIo.execute {
                try { target() } catch (_: Throwable) { /* Stop remains latched; close still owns resources. */ }
                finally { synchronized(ownership) { cancelCalls-- }; notifyClose() }
            }
        } catch (_: RuntimeException) { synchronized(ownership) { cancelCalls--; cancelSent = false }; notifyClose() }
    }

    private fun notifyClose() { try { onResourcesMayClose() } catch (_: RuntimeException) { /* Owner/receipts remain retained. */ } }

    fun advance(): AutoRunResult {
        if (!advancing.compareAndSet(false, true)) throw IOException("Auto-develop runner is active")
        val answer: AutoRunResult
        try {
            // Close ownership precedes even metadata reads; a broken journal must not strand handles.
            if (attempt != null && !closeRender()) return result(AutoRunStatus.RESOURCES_HELD, null)
            if (slot != null && !releaseIo()) return result(AutoRunStatus.RESOURCES_HELD, null)
            answer = run()
        } finally {
            if (attempt == null && releaseIo()) {
                // Resource ownership may end while its metadata commit is uncertain. Preserve the
                // observed terminal decision independently; a fresh slot must not make it successful.
                val current = slot
                val renderId = renderAttemptId
                if (renderPending && renderDecision == null && renderId != null && current != null && admission.shouldStop(current)) {
                    renderDecision = RenderDecision(renderId, AutoJobPhase.RETRYABLE, AutoJobReason.INTERRUPTED)
                }
                current?.let(admission::completeAfterResourcesClosed)
                slot = null
            }
            advancing.set(false)
        }
        return if (slot != null) answer.copy(status = AutoRunStatus.RESOURCES_HELD) else answer
    }

    private fun run(): AutoRunResult {
        var record = journal.read() ?: throw IOException("Auto-develop request missing")
        if (record.phase == AutoJobPhase.PUBLISHED) return result(AutoRunStatus.PUBLISHED, record)
        if (record.phase == AutoJobPhase.HELD) return result(AutoRunStatus.HELD, record)
        // A readable legacy FULL record is not corrupt inventory. Preserve its artifact/attempt
        // and any publication journal/URI, but never reconstruct success from request metadata.
        if ((record.request.quality == AutoRenderQuality.FINAL_ORIGINAL ||
                (record.request.publishable && record.observed?.original == null)) &&
            record.phase in setOf(AutoJobPhase.RENDERED, AutoJobPhase.PUBLISHING)) {
            val reason = if (record.observed == null) AutoJobReason.MISSING_OBSERVATION else AutoJobReason.MISSING_ORIGINAL_EVIDENCE
            return result(AutoRunStatus.HELD, journal.hold(record.revision, reason))
        }
        if (record.phase == AutoJobPhase.RETRYABLE && slot == null) return result(AutoRunStatus.RETRYABLE, record)
        if (!supports(record.request)) {
            if (slot != null) throw IOException("Live renderer contract changed")
            return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.UNSUPPORTED))
        }
        if (record.phase == AutoJobPhase.QUEUED) {
            try { AutoDevelopCodec.requireAttemptCapacity(record) }
            catch (_: AutoDevelopJournalTooLarge) {
                return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.INVALID_OUTPUT))
            }
        }
        if (slot == null) slot = admit() ?: return result(AutoRunStatus.BLOCKED, record)
        if (renderPending && record.phase == AutoJobPhase.RENDERING) {
            record = finishRender(record)
        } else if (record.phase == AutoJobPhase.QUEUED) {
            // An explicit retry starts a different attempt, including if the next intent commit fails.
            receipt = null; renderFailure = null; renderPending = false; renderDecision = null; renderAttemptId = null
            try { backend.verifyOriginal(record.request, ::stopped) }
            catch (changed: DngContentChanged) { return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.SOURCE_CHANGED)) }
            catch (failure: Exception) {
                if (stopped()) return result(AutoRunStatus.BLOCKED, record)
                return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.SOURCE_UNAVAILABLE))
            }
            if (stopped()) return result(AutoRunStatus.BLOCKED, record)
            record = journal.start(record.revision) // A failed/readback-uncertain commit never calls renderer.
            renderAttemptId = record.attemptId
            val created = backend.createAttempt(record.request, requireNotNull(record.attemptId))
            synchronized(ownership) { attempt = created; cancelSent = false }
            renderPending = true
            try {
                if (stopped()) throw CancellationException("Auto-develop attempt superseded before render")
                receipt = requireNotNull(attempt).render(::stopped)
            }
            catch (failure: Throwable) { renderFailure = failure }
            if (!closeRender()) return result(AutoRunStatus.RESOURCES_HELD, record)
            record = finishRender(record)
        } else if (record.phase == AutoJobPhase.RENDERING) {
            // No live handle means no completion/close acknowledgement. Never inspect/promote orphan output.
            record = journal.retryable(record.revision, AutoJobReason.INTERRUPTED)
        }
        if (record.phase == AutoJobPhase.RETRYABLE) return result(AutoRunStatus.RETRYABLE, record)
        if (record.phase == AutoJobPhase.HELD) return result(AutoRunStatus.HELD, record)
        if (record.phase == AutoJobPhase.RENDERED) {
            if (record.request.boundedTrial) return result(AutoRunStatus.PRIVATE_READY, record)
            if (stopped() || !allowPublication()) return result(AutoRunStatus.PUBLICATION_PENDING, record)
            record = journal.publishing(record.revision)
        }
        if (record.phase == AutoJobPhase.PUBLISHING) {
            if (!allowPublication()) return result(AutoRunStatus.PUBLICATION_PENDING, record)
            val owner = try { publication ?: backend.publication(record) { !stopped() }.also { publication = it } }
            catch (_: DngContentChanged) { return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.CONTENT_CHANGED)) }
            try {
                owner.releaseResources()
                if (stopped()) return result(AutoRunStatus.PUBLICATION_PENDING, record)
                val phase = owner.advance { stopped() || !allowPublication() }
                if (owner.hasUnreleasedResources) return result(AutoRunStatus.RESOURCES_HELD, record)
                when (phase) {
                    JpegPublicationPhase.PUBLISHED -> record = journal.published(record.revision)
                    JpegPublicationPhase.UNKNOWN -> record = journal.hold(record.revision, AutoJobReason.PUBLICATION_UNKNOWN)
                    else -> return result(AutoRunStatus.PUBLICATION_PENDING, record)
                }
            } catch (_: DngContentChanged) {
                return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.CONTENT_CHANGED))
            } catch (_: JpegPublicationTooLarge) {
                return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.INVALID_OUTPUT))
            } catch (_: Exception) {
                return result(if (owner.hasUnreleasedResources) AutoRunStatus.RESOURCES_HELD else AutoRunStatus.PUBLICATION_PENDING, journal.read())
            }
        }
        return result(if (record.phase == AutoJobPhase.PUBLISHED) AutoRunStatus.PUBLISHED else AutoRunStatus.HELD, record)
    }

    private fun releaseIo(): Boolean = try {
        publication?.releaseResources()
        backend.releaseResources()
        publication?.hasUnreleasedResources != true && !backend.hasUnreleasedResources
    } catch (_: Throwable) { false }

    private fun admit() = if (interactive) observations()?.let(admission::tryStartObserved) else admission.tryStart()
    private fun stopped() = slot?.let { admission.shouldStop(it) } != false || !isCurrent() ||
        (interactive && observations()?.let { AutoDevelopPolicy.blockers(it).isNotEmpty() } != false)
    private fun closeRender(): Boolean {
        val owner = synchronized(ownership) {
            if (cancelCalls != 0) return false
            (attempt ?: return true).also { closing = true }
        }
        return try { owner.close(); synchronized(ownership) { attempt = null }; true }
        catch (_: Throwable) { false }
        finally { synchronized(ownership) { closing = false } }
    }
    private fun finishRender(record: AutoDevelopRecord): AutoDevelopRecord {
        if (record.phase != AutoJobPhase.RENDERING) throw CaptureJournalConflict()
        val decision = renderDecision ?: decideRender(record).also { renderDecision = it }
        if (decision.attemptId != record.attemptId) throw CaptureJournalConflict()
        return when (decision.phase) {
            AutoJobPhase.RETRYABLE -> journal.retryable(record.revision, decision.reason)
            AutoJobPhase.HELD -> journal.hold(record.revision, decision.reason)
            AutoJobPhase.RENDERED -> journal.rendered(record.revision, requireNotNull(decision.fingerprint), decision.observed)
            else -> throw CaptureJournalConflict()
        }
    }
    private fun decideRender(record: AutoDevelopRecord): RenderDecision {
        val renderId = requireNotNull(record.attemptId)
        fun interrupted() = RenderDecision(renderId, AutoJobPhase.RETRYABLE, AutoJobReason.INTERRUPTED)
        fun failed() = RenderDecision(renderId, AutoJobPhase.RETRYABLE, AutoJobReason.RENDER_FAILED)
        fun invalid() = RenderDecision(renderId, AutoJobPhase.HELD, AutoJobReason.INVALID_OUTPUT)
        val failure = renderFailure
        if (stopped() || failure is CancellationException || failure is DngVerificationStopped) {
            return interrupted()
        }
        if (failure is AutoRenderUnsupported) return RenderDecision(renderId, AutoJobPhase.HELD, AutoJobReason.UNSUPPORTED)
        if (failure != null) return failed()
        val value = receipt ?: return failed()
        val request = record.request
        if (!request.accepts(value)) return invalid()
        if (request.publishable && value.original?.binding?.attemptId != renderId) return invalid()
        val inspection = try { backend.inspect(request, requireNotNull(record.attemptId), ::stopped) }
        catch (_: Exception) {
            return if (stopped()) interrupted() else invalid()
        }
        if (stopped()) return interrupted()
        if (backend.hasUnreleasedResources || inspection.output != value.output || inspection.fingerprint.bytes != value.output.bytes) return invalid()
        try {
            // Bounded metadata validation after close/inspection, before the durable success commit.
            AutoDevelopCodec.encode(record.copy(phase = AutoJobPhase.RENDERED, outputFingerprint = inspection.fingerprint, observed = value))
        } catch (_: AutoDevelopJournalTooLarge) { return invalid() }
        return RenderDecision(renderId, AutoJobPhase.RENDERED, AutoJobReason.NONE, inspection.fingerprint, value)
    }

    /** Historical PUBLISHED is insufficient. Re-enter this same owner and #118 provider readback.
     * Caller serializes generation changes on the same queue; failures never fabricate a result URI. */
    fun confirmPublished(shouldStop: () -> Boolean): JpegPublicationRecord {
        if (!advancing.compareAndSet(false, true)) throw IOException("Develop owner is active")
        try {
            if (attempt != null && !closeRender() || !releaseIo()) throw IOException("Develop resources remain open")
            val record = journal.read() ?: throw CaptureJournalConflict()
            if (record.phase != AutoJobPhase.PUBLISHED || !isCurrent() || !supports(record.request)) throw CaptureJournalConflict()
            val expected = record.completed() // Historical success without observations cannot acquire a publisher.
            if (slot == null) slot = admit() ?: throw IOException("Develop execution is occupied or blocked")
            val stop = { shouldStop() || stopped() }
            if (stop()) throw DngVerificationStopped()
            val owner = publication ?: backend.publication(record) { !stopped() }.also { publication = it }
            owner.releaseResources()
            val checked = owner.confirmPublished(stop)
            if (stop() || owner.hasUnreleasedResources || checked.phase != JpegPublicationPhase.PUBLISHED ||
                checked.uri == null || checked.render != expected) throw IOException("Published JPEG could not be confirmed")
            return checked
        } finally {
            if (attempt == null && releaseIo()) { slot?.let(admission::completeAfterResourcesClosed); slot = null }
            advancing.set(false)
        }
    }
    /** Same generation/attempt only. Produces a display image, never a completed/publication receipt. */
    fun readPrivateTrial(shouldStop: () -> Boolean): PrivateTrialPreview {
        if (!advancing.compareAndSet(false, true)) throw IOException("Develop owner is active")
        var image: PrivateTrialPreview? = null
        try {
            if (attempt != null && !closeRender() || !releaseIo()) throw IOException("Develop resources remain open")
            val record = journal.read() ?: throw CaptureJournalConflict()
            if (!record.request.boundedTrial || record.phase != AutoJobPhase.RENDERED || !isCurrent() || !supports(record.request)) throw CaptureJournalConflict()
            if (slot == null) slot = admit() ?: throw IOException("Develop execution is occupied or blocked")
            synchronized(ownership) { cancelSent = false }
            val stop = { shouldStop() || stopped() }
            if (stop()) throw DngVerificationStopped()
            val readback = backend.privatePreview(record, stop).also { image = it }
            if (stop() || !releaseIo() || readback.status != PrivateTrialStatus.from(record)) throw IOException("Private preview not confirmed")
            image = null // Handoff is registered by the queue before its future completes.
            return readback
        } finally {
            image?.close()
            if (attempt == null && releaseIo()) { slot?.let(admission::completeAfterResourcesClosed); slot = null }
            advancing.set(false)
        }
    }
    private fun result(status: AutoRunStatus, record: AutoDevelopRecord?) = AutoRunResult(status, record)
}

internal object AutoDevelopCancellation {
    val io: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "auto-develop-cancel").apply { isDaemon = true } }
}
