package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AutoRunStatus { BLOCKED, RETRYABLE, HELD, PUBLICATION_PENDING, PUBLISHED, RESOURCES_HELD }
internal data class AutoRunResult(val status: AutoRunStatus, val record: AutoDevelopRecord?)

internal interface AutoDevelopPublication {
    val hasUnreleasedResources: Boolean
    fun releaseResources()
    fun advance(shouldStop: () -> Boolean): JpegPublicationPhase
}
internal class RecoverableAutoPublication(private val publisher: RecoverablePhotoPublisher) : AutoDevelopPublication {
    override val hasUnreleasedResources get() = publisher.hasUnreleasedResources
    override fun releaseResources() = publisher.releaseResources()
    override fun advance(shouldStop: () -> Boolean) = publisher.advance(shouldStop).phase
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
    private var publication: AutoDevelopPublication? = null
    val hasUnreleasedResources get() = slot != null

    /** Process-local cancellation intent. A delayed caller must retain the original owner+slot pair. */
    class StopTarget internal constructor(internal val owner: AutoDevelopRunner, internal val slot: AutoDevelopCoordinator.Attempt)
    fun stopTarget(): StopTarget? = slot?.let { StopTarget(this, it) }

    /** Never invoke adapter/native cancellation on the capture/UI caller or the occupied renderer executor. */
    fun requestStop(requested: StopTarget? = stopTarget()) {
        if (requested == null || requested.owner !== this) return
        admission.requestStop(requested.slot)
        val target = synchronized(ownership) {
            if (slot !== requested.slot) return
            val owner = attempt
            if (owner == null || closing || cancelSent) return
            cancelSent = true
            cancelCalls++
            owner
        }
        try {
            cancellationIo.execute {
                try { target.requestStop() } catch (_: Throwable) { /* Stop remains latched; close still owns resources. */ }
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
            if (attempt == null && releaseIo()) { slot?.let(admission::completeAfterResourcesClosed); slot = null }
            advancing.set(false)
        }
        return if (slot != null) answer.copy(status = AutoRunStatus.RESOURCES_HELD) else answer
    }

    private fun run(): AutoRunResult {
        var record = journal.read() ?: throw IOException("Auto-develop request missing")
        if (record.phase == AutoJobPhase.PUBLISHED) return result(AutoRunStatus.PUBLISHED, record)
        if (record.phase == AutoJobPhase.HELD) return result(AutoRunStatus.HELD, record)
        if (record.phase == AutoJobPhase.RETRYABLE && slot == null) return result(AutoRunStatus.RETRYABLE, record)
        if (!record.request.publishable || !backend.supports(record.request)) {
            if (slot != null) throw IOException("Live renderer contract changed")
            return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.UNSUPPORTED))
        }
        if (slot == null) slot = admission.tryStart() ?: return result(AutoRunStatus.BLOCKED, record)
        if (renderPending && record.phase == AutoJobPhase.RENDERING) {
            record = finishRender(record)
        } else if (record.phase == AutoJobPhase.QUEUED) {
            try { backend.verifyOriginal(record.request, ::stopped) }
            catch (changed: DngContentChanged) { return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.SOURCE_CHANGED)) }
            catch (failure: Exception) {
                if (stopped()) return result(AutoRunStatus.BLOCKED, record)
                return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.SOURCE_UNAVAILABLE))
            }
            if (stopped()) return result(AutoRunStatus.BLOCKED, record)
            record = journal.start(record.revision) // A failed/readback-uncertain commit never calls renderer.
            receipt = null; renderFailure = null
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
            if (stopped()) return result(AutoRunStatus.PUBLICATION_PENDING, record)
            record = journal.publishing(record.revision)
        }
        if (record.phase == AutoJobPhase.PUBLISHING) {
            val owner = try { publication ?: backend.publication(record) { !stopped() }.also { publication = it } }
            catch (_: DngContentChanged) { return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.CONTENT_CHANGED)) }
            try {
                owner.releaseResources()
                if (stopped()) return result(AutoRunStatus.PUBLICATION_PENDING, record)
                val phase = owner.advance(::stopped)
                if (owner.hasUnreleasedResources) return result(AutoRunStatus.RESOURCES_HELD, record)
                when (phase) {
                    JpegPublicationPhase.PUBLISHED -> record = journal.published(record.revision)
                    JpegPublicationPhase.UNKNOWN -> record = journal.hold(record.revision, AutoJobReason.PUBLICATION_UNKNOWN)
                    else -> return result(AutoRunStatus.PUBLICATION_PENDING, record)
                }
            } catch (_: DngContentChanged) {
                return result(AutoRunStatus.HELD, journal.hold(record.revision, AutoJobReason.CONTENT_CHANGED))
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

    private fun stopped() = slot?.let { admission.shouldStop(it) } != false || !isCurrent()
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
        val failure = renderFailure
        if (stopped() || failure is CancellationException || failure is DngVerificationStopped) {
            return journal.retryable(record.revision, AutoJobReason.INTERRUPTED)
        }
        if (failure is AutoRenderUnsupported) return journal.hold(record.revision, AutoJobReason.UNSUPPORTED)
        if (failure != null) return journal.retryable(record.revision, AutoJobReason.RENDER_FAILED)
        val value = receipt ?: return journal.retryable(record.revision, AutoJobReason.RENDER_FAILED)
        val request = record.request
        if (value.request != request || !value.request.publishable || value.output.width != request.width || value.output.height != request.height ||
            value.output.orientation != 1 || value.output.color != "sRGB") return journal.hold(record.revision, AutoJobReason.INVALID_OUTPUT)
        val inspection = try { backend.inspect(request, requireNotNull(record.attemptId), ::stopped) }
        catch (_: Exception) {
            if (stopped()) return journal.retryable(record.revision, AutoJobReason.INTERRUPTED)
            return journal.hold(record.revision, AutoJobReason.INVALID_OUTPUT)
        }
        if (stopped()) return journal.retryable(record.revision, AutoJobReason.INTERRUPTED)
        if (backend.hasUnreleasedResources) throw IOException("Output inspection resources remain open")
        if (inspection.output != value.output || inspection.fingerprint.bytes != value.output.bytes) return journal.hold(record.revision, AutoJobReason.INVALID_OUTPUT)
        return journal.rendered(record.revision, inspection.fingerprint)
    }
    private fun result(status: AutoRunStatus, record: AutoDevelopRecord?) = AutoRunResult(status, record)
}

internal object AutoDevelopCancellation {
    val io: Executor = Executors.newSingleThreadExecutor { task -> Thread(task, "auto-develop-cancel").apply { isDaemon = true } }
}
