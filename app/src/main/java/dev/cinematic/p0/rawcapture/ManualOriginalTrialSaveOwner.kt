package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ManualOriginalTrialSavePhase { READY, SAVING, PUBLISHED, UNCONFIRMED, GPU_FAILED, CANCELLED, HOLD }
internal enum class ManualOriginalTrialSaveReason {
    STOPPED, SUPERSEDED, GENERATION_STARTED, RESOURCES_HELD, PUBLICATION_UNKNOWN, OPERATION_FAILED,
}
internal data class ManualOriginalTrialSaveState(
    val phase: ManualOriginalTrialSavePhase,
    val uri: String? = null,
    val reason: ManualOriginalTrialSaveReason? = null,
    val gpuFailure: GpuSaveFailure? = null,
)

/** Thin adapters retain the existing Android operation and publisher; neither API creates a queue. */
internal interface ManualOriginalTrialPublication {
    val hasUnreleasedResources: Boolean
    fun advance(stop: () -> Boolean): JpegPublicationRecord
    fun releaseResources()
}
internal interface ManualOriginalTrialSaveBackend {
    val hasUnreleasedResources: Boolean
    fun readPublication(): JpegPublicationRecord?
    /** Return only after all producer resources close and source/JPEG readback succeeds. */
    fun render(stop: () -> Boolean): ManualOriginalTrialRender
    fun publisher(render: ManualOriginalTrialRender): ManualOriginalTrialPublication
    fun releaseResources()
}

/** One retained owner per immutable generation. Only explicit advance starts full-size rendering.
 * The caller supplies source/current-generation authority and runs this on existing serial queue IO.
 * attemptStorage is dedicated to this generation; an intent without a journal proof is a permanent
 * HOLD after restart, because the prior producer/close outcome cannot be reconstructed safely. */
internal class ManualOriginalTrialSaveOwner(
    val request: Original24Request,
    private val backend: ManualOriginalTrialSaveBackend,
    private val attemptStorage: AtomicJournalStorage,
    private val isCurrent: () -> Boolean,
) {
    val appliedStrength get() = request.strength
    private val mutableState = MutableStateFlow(ManualOriginalTrialSaveState(ManualOriginalTrialSavePhase.READY))
    val state: StateFlow<ManualOriginalTrialSaveState> = mutableState.asStateFlow()
    private val active = AtomicBoolean(false)
    private var proof: ManualOriginalTrialRender? = null
    private var publication: ManualOriginalTrialPublication? = null
    private var renderStarted = false
    private var terminalHold = false
    private var gpuRetryAvailable = false
    private var cancellationRetryAvailable = false
    private var gpuFailure: GpuSaveFailure? = null
    var lastFailure: Throwable? = null
        private set
    val hasUnreleasedResources get() = backend.hasUnreleasedResources || publication?.hasUnreleasedResources == true

    init { ManualOriginalTrialRequestCodec.encode(request) }
    fun matches(source: SharedOriginalReference) = request.source == source

    /** Called only by the serialized generation fence for its exact previous request. Preparing
     * clears current authority first, so isCurrent is deliberately not used here. The durable
     * source request commits the successor; the old intent is never erased. A restarted owner
     * has no cancellation permit and cannot pass this fence. This does not start or retry work. */
    fun canSupersedeConfirmedCancellation(): Boolean = exclusive {
        if (terminalHold || !cancellationRetryAvailable || !renderStarted ||
            state.value.phase != ManualOriginalTrialSavePhase.CANCELLED || proof != null ||
            publication != null || hasUnreleasedResources) return@exclusive false
        try {
            if (backend.readPublication() != null) return@exclusive false
            if (attemptStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)
                    ?.contentEquals(ManualOriginalTrialRequestCodec.encode(request)) != true) {
                hold(ManualOriginalTrialSaveReason.GENERATION_STARTED)
                return@exclusive false
            }
            true
        } catch (failure: Exception) {
            lastFailure = failure
            hold(ManualOriginalTrialSaveReason.OPERATION_FAILED)
            throw failure
        }
    }

    fun advance(retryGpuFailure: Boolean = false, retryCancellation: Boolean = false, stop: () -> Boolean = { false }): ManualOriginalTrialSaveState = exclusive {
        if (terminalHold || (gpuRetryAvailable && !retryGpuFailure) || (cancellationRetryAvailable && !retryCancellation)) return@exclusive state.value
        try {
            if (!isCurrent()) return@exclusive hold(ManualOriginalTrialSaveReason.SUPERSEDED)
            val previous = backend.readPublication()
            previous?.let { record ->
                val recovered = record.render as? ManualOriginalTrialRender ?: throw CaptureJournalConflict()
                requireMatches(recovered)
                recovered.requireFingerprint(record.fingerprint)
                if (proof != null && proof != recovered) throw CaptureJournalConflict()
                proof = recovered
                // Completed output resumes the existing publication, never a producer retry.
                gpuRetryAvailable = false
                cancellationRetryAvailable = false
                if (record.phase == JpegPublicationPhase.UNKNOWN) {
                    return@exclusive update(ManualOriginalTrialSavePhase.UNCONFIRMED, record.uri,
                        ManualOriginalTrialSaveReason.PUBLICATION_UNKNOWN)
                }
            }
            if (hasUnreleasedResources) return@exclusive update(ManualOriginalTrialSavePhase.HOLD, previous?.uri,
                ManualOriginalTrialSaveReason.RESOURCES_HELD)
            if (stop()) return@exclusive update(if (cancellationRetryAvailable) ManualOriginalTrialSavePhase.CANCELLED
                else if (gpuRetryAvailable) ManualOriginalTrialSavePhase.GPU_FAILED
                else if (proof == null) ManualOriginalTrialSavePhase.READY else
                ManualOriginalTrialSavePhase.UNCONFIRMED, previous?.uri, ManualOriginalTrialSaveReason.STOPPED)
            update(ManualOriginalTrialSavePhase.SAVING, previous?.uri)
            if (proof == null) {
                val intent = ManualOriginalTrialRequestCodec.encode(request)
                val previousIntent = attemptStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)
                if (gpuRetryAvailable || cancellationRetryAvailable) {
                    // This in-process permit exists only for confirmed pre-JPEG failure/cancellation.
                    // Keep the durable intent: a restarted owner must still HOLD, never rerender.
                    val explicitlyRetried = (gpuRetryAvailable && retryGpuFailure) || (cancellationRetryAvailable && retryCancellation)
                    gpuRetryAvailable = false
                    cancellationRetryAvailable = false
                    if (!explicitlyRetried || !renderStarted || previousIntent?.contentEquals(intent) != true)
                        return@exclusive hold(ManualOriginalTrialSaveReason.GENERATION_STARTED)
                } else if (renderStarted || previousIntent != null)
                    return@exclusive hold(ManualOriginalTrialSaveReason.GENERATION_STARTED)
                // Intent is durable and read back BEFORE admission to any heavy source/render IO.
                renderStarted = true
                attemptStorage.replace(intent)
                if (attemptStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)?.contentEquals(intent) != true)
                    throw IOException("Manual trial generation intent unconfirmed")
                if (!isCurrent()) return@exclusive hold(ManualOriginalTrialSaveReason.SUPERSEDED)
                // No producer has been entered. The durable intent is retained for explicit retry.
                if (stop()) return@exclusive cancelled()
                proof = backend.render { stop() || !isCurrent() }.also(::requireMatches)
                if (hasUnreleasedResources) return@exclusive hold(ManualOriginalTrialSaveReason.RESOURCES_HELD)
            }
            if (!isCurrent()) return@exclusive hold(ManualOriginalTrialSaveReason.SUPERSEDED)
            if (stop()) return@exclusive update(ManualOriginalTrialSavePhase.UNCONFIRMED, previous?.uri,
                ManualOriginalTrialSaveReason.STOPPED)
            val owner = publication ?: backend.publisher(requireNotNull(proof)).also { publication = it }
            val result = owner.advance { stop() || !isCurrent() }
            requireMatches(result.render as? ManualOriginalTrialRender ?: throw CaptureJournalConflict())
            result.render.requireFingerprint(result.fingerprint)
            if (result.render != proof) throw CaptureJournalConflict()
            stoppedPublication(stop, result.uri)?.let { return@exclusive it }
            if (hasUnreleasedResources) return@exclusive update(ManualOriginalTrialSavePhase.HOLD, result.uri,
                ManualOriginalTrialSaveReason.RESOURCES_HELD)
            // Resource inspection can cross a cancellation/generation change as well. A durable
            // public row is a receipt, not authority to display this now-stale result as published.
            stoppedPublication(stop, result.uri)?.let { return@exclusive it }
            update(when (result.phase) {
                JpegPublicationPhase.PUBLISHED -> ManualOriginalTrialSavePhase.PUBLISHED
                else -> ManualOriginalTrialSavePhase.UNCONFIRMED
            }, result.uri, if (result.phase == JpegPublicationPhase.UNKNOWN) ManualOriginalTrialSaveReason.PUBLICATION_UNKNOWN else null)
        } catch (failure: Throwable) {
            lastFailure = failure
            if (failure is ConfirmedSaveCancellationException && proof == null && publication == null &&
                failure.suppressed.isEmpty() && failure.cause?.suppressed?.isEmpty() == true &&
                !hasUnreleasedResources && isCurrent()) return@exclusive cancelled()
            if (failure is GpuSaveRenderException) {
                val count = gpuFailure?.count ?: 0
                gpuFailure = GpuSaveFailure(failure.reason, if (count == Long.MAX_VALUE) count else count + 1)
                if (proof == null && publication == null && failure.cleanupConfirmed && failure.suppressed.isEmpty() &&
                    !hasUnreleasedResources && !stop() && isCurrent()) {
                    gpuRetryAvailable = true
                    return@exclusive update(ManualOriginalTrialSavePhase.GPU_FAILED,
                        reason = ManualOriginalTrialSaveReason.OPERATION_FAILED)
                }
            }
            val next = if (proof == null || failure is CaptureJournalConflict) hold(ManualOriginalTrialSaveReason.OPERATION_FAILED)
            else if (hasUnreleasedResources) update(ManualOriginalTrialSavePhase.HOLD, state.value.uri,
                ManualOriginalTrialSaveReason.RESOURCES_HELD)
            else update(ManualOriginalTrialSavePhase.UNCONFIRMED, state.value.uri, ManualOriginalTrialSaveReason.OPERATION_FAILED)
            if (failure !is Exception) throw failure
            next
        }
    }

    /** Cleanup retries keep the same producer/publication owners and never authorize a fresh render. */
    fun releaseResources() = exclusive {
        var failure: Throwable? = null
        try { publication?.releaseResources() } catch (error: Throwable) { failure = error }
        try { backend.releaseResources() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        failure?.let {
            lastFailure = it
            // A later cleanup failure invalidates the earlier positive cancellation evidence.
            if (cancellationRetryAvailable) hold(ManualOriginalTrialSaveReason.OPERATION_FAILED)
            throw it
        }
    }

    private fun requireMatches(render: ManualOriginalTrialRender) {
        val actual = render.request
        if (actual.captureId != request.captureId || actual.generation != request.generation || actual.source != request.source ||
            actual.recipe != request.recipe || render.appliedStrength.toRawBits() != request.strength.toRawBits()) throw CaptureJournalConflict()
    }
    private fun stoppedPublication(stop: () -> Boolean, uri: String?): ManualOriginalTrialSaveState? {
        if (!isCurrent()) return hold(ManualOriginalTrialSaveReason.SUPERSEDED, uri)
        if (stop()) return update(ManualOriginalTrialSavePhase.UNCONFIRMED, uri, ManualOriginalTrialSaveReason.STOPPED)
        return null
    }
    private fun cancelled(): ManualOriginalTrialSaveState {
        cancellationRetryAvailable = true
        return update(ManualOriginalTrialSavePhase.CANCELLED, reason = ManualOriginalTrialSaveReason.STOPPED)
    }
    private fun hold(reason: ManualOriginalTrialSaveReason, uri: String? = state.value.uri): ManualOriginalTrialSaveState {
        terminalHold = true
        return update(ManualOriginalTrialSavePhase.HOLD, uri, reason)
    }
    private fun update(phase: ManualOriginalTrialSavePhase, uri: String? = null, reason: ManualOriginalTrialSaveReason? = null) =
        ManualOriginalTrialSaveState(phase, uri, reason, gpuFailure).also { mutableState.value = it }
    private inline fun <T> exclusive(action: () -> T): T {
        if (!active.compareAndSet(false, true)) throw IOException("Manual trial save already active")
        try { return action() } finally { active.set(false) }
    }
}
