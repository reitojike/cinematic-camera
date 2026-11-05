package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ManualOriginalTrialSavePhase { READY, SAVING, PUBLISHED, UNCONFIRMED, HOLD }
internal enum class ManualOriginalTrialSaveReason {
    STOPPED, SUPERSEDED, GENERATION_STARTED, RESOURCES_HELD, PUBLICATION_UNKNOWN, OPERATION_FAILED,
}
internal data class ManualOriginalTrialSaveState(
    val phase: ManualOriginalTrialSavePhase,
    val uri: String? = null,
    val reason: ManualOriginalTrialSaveReason? = null,
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
    var lastFailure: Throwable? = null
        private set
    val hasUnreleasedResources get() = backend.hasUnreleasedResources || publication?.hasUnreleasedResources == true

    init { ManualOriginalTrialRequestCodec.encode(request) }
    fun matches(source: SharedOriginalReference) = request.source == source

    fun advance(stop: () -> Boolean = { false }): ManualOriginalTrialSaveState = exclusive {
        if (terminalHold) return@exclusive state.value
        try {
            if (!isCurrent()) return@exclusive hold(ManualOriginalTrialSaveReason.SUPERSEDED)
            val previous = backend.readPublication()
            previous?.let { record ->
                val recovered = record.render as? ManualOriginalTrialRender ?: throw CaptureJournalConflict()
                requireMatches(recovered)
                recovered.requireFingerprint(record.fingerprint)
                if (proof != null && proof != recovered) throw CaptureJournalConflict()
                proof = recovered
                if (record.phase == JpegPublicationPhase.UNKNOWN) {
                    return@exclusive update(ManualOriginalTrialSavePhase.UNCONFIRMED, record.uri,
                        ManualOriginalTrialSaveReason.PUBLICATION_UNKNOWN)
                }
            }
            if (hasUnreleasedResources) return@exclusive update(ManualOriginalTrialSavePhase.HOLD, previous?.uri,
                ManualOriginalTrialSaveReason.RESOURCES_HELD)
            if (stop()) return@exclusive update(if (proof == null) ManualOriginalTrialSavePhase.READY else
                ManualOriginalTrialSavePhase.UNCONFIRMED, previous?.uri, ManualOriginalTrialSaveReason.STOPPED)
            update(ManualOriginalTrialSavePhase.SAVING, previous?.uri)
            if (proof == null) {
                if (renderStarted || attemptStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES) != null)
                    return@exclusive hold(ManualOriginalTrialSaveReason.GENERATION_STARTED)
                // Intent is durable and read back BEFORE admission to any heavy source/render IO.
                renderStarted = true
                val intent = ManualOriginalTrialRequestCodec.encode(request)
                attemptStorage.replace(intent)
                if (attemptStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)?.contentEquals(intent) != true)
                    throw IOException("Manual trial generation intent unconfirmed")
                if (stop() || !isCurrent()) return@exclusive hold(ManualOriginalTrialSaveReason.STOPPED)
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
        failure?.let { lastFailure = it; throw it }
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
    private fun hold(reason: ManualOriginalTrialSaveReason, uri: String? = state.value.uri): ManualOriginalTrialSaveState {
        terminalHold = true
        return update(ManualOriginalTrialSavePhase.HOLD, uri, reason)
    }
    private fun update(phase: ManualOriginalTrialSavePhase, uri: String? = null, reason: ManualOriginalTrialSaveReason? = null) =
        ManualOriginalTrialSaveState(phase, uri, reason).also { mutableState.value = it }
    private inline fun <T> exclusive(action: () -> T): T {
        if (!active.compareAndSet(false, true)) throw IOException("Manual trial save already active")
        try { return action() } finally { active.set(false) }
    }
}
