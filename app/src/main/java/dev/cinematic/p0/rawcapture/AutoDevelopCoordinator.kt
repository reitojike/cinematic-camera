package dev.cinematic.p0.rawcapture

import java.util.Collections

/**
 * Process-local admission state, deliberately not connected to CameraX or a worker.
 * All methods serialize short state transitions; none invokes caller code or performs IO.
 * Integration must keep one coordinator for the process, across camera/activity recreation;
 * replacing a live instance must not bypass an outstanding worker's resource ownership.
 * Future adapters must reserve capture before camera work and check shouldStop before heavy work.
 * Admission is not proof that a native operation can stop immediately or avoid resource contention.
 */
internal class AutoDevelopCoordinator {
    // Identity tokens prevent old/foreign completions from releasing a different owner's slot.
    class Attempt internal constructor()
    class CaptureReservation internal constructor()
    class ForegroundExportRequest internal constructor(internal val owner: Any) {
        internal var stopped = false
    }

    private class Active(val attempt: Attempt, val observed: Boolean = false, var stopRequested: Boolean = false,
        val foregroundExport: Boolean = false, val preview: Boolean = false)

    private var conditions = AutoDevelopConditions()
    private var conditionsObserved = false
    private var active: Active? = null
    // GPU input preparation has one independent owner, alongside an explicit FGS export only.
    private var gpuPreview: Active? = null
    private var pendingExport: ForegroundExportRequest? = null
    private val captures = mutableSetOf<CaptureReservation>()

    @Synchronized
    fun updateConditions(value: AutoDevelopConditions) {
        conditions = value
        conditionsObserved = true
        if (AutoDevelopPolicy.previewContinuationBlockers(value.copy(rendererAvailable = true)).isNotEmpty())
            gpuPreview?.stopRequested = true
        // Job-local renderer availability must not cancel an interactive job with another capability.
        val current = active
        // A started export has its own service-scoped observer. Activity pause/teardown must not
        // replace that observer's current resource facts with an incomplete UI snapshot.
        if (current?.foregroundExport == true) return
        val common = if (current?.observed == true) value.copy(rendererAvailable = true) else value
        val blockers = if (current?.preview == true) AutoDevelopPolicy.previewContinuationBlockers(common)
            else AutoDevelopPolicy.continuationBlockers(common)
        if (blockers.isNotEmpty()) current?.stopRequested = true
    }

    /** Never waits for the admitted worker to stop or close its resources. */
    @Synchronized
    fun reserveCapture(): CaptureReservation {
        val reservation = CaptureReservation()
        captures.add(reservation)
        active?.stopRequested = true
        gpuPreview?.stopRequested = true
        pendingExport?.stopped = true
        return reservation
    }

    /**
     * Release after this capture's priority window ends. Update camera/record conditions first
     * if those operations remain active. A duplicate/foreign release cannot erase another owner.
     */
    @Synchronized
    fun releaseCapture(reservation: CaptureReservation): Boolean = captures.remove(reservation)

    /** Checks current observations and capture reservations in the same critical section. */
    @Synchronized
    fun tryStart(): Attempt? {
        if (active != null || gpuPreview != null || pendingExport != null || captures.isNotEmpty() || AutoDevelopPolicy.blockers(conditions).isNotEmpty()) {
            return null
        }
        return Attempt().also { active = Active(it) }
    }

    /** Interactive caller supplies fresh observations including its own renderer capability.
     * The caller must keep checking observations and cancellation while it owns resources. */
    @Synchronized fun tryStartObserved(observations: AutoDevelopConditions): Attempt? = startObserved(observations, preview = false)

    /** CPU preview keeps the existing exclusive slot and admission policy. */
    @Synchronized fun tryStartPreviewObserved(observations: AutoDevelopConditions): Attempt? = startObserved(observations, preview = true)

    /** GPU evidence, captured before admission under the same short identity lock.
     * Stored blockers use the same job-local renderer normalization as startObserved and apply only
     * when conditionsObserved is true. The snapshot contains no owner identities or live state. */
    data class GpuDiagnosticPreviewSnapshot(
        val freshPreviewBlockers: Set<AutoDevelopBlocker>,
        val storedGlobalPreviewBlockers: Set<AutoDevelopBlocker>,
        val conditionsObserved: Boolean,
        val freshForeground: Boolean?,
        val storedForeground: Boolean?,
        val freshRendererAvailable: Boolean?,
        val hasActiveSlot: Boolean,
        val activeStopRequested: Boolean?,
        val captureCount: Int,
        val hasPendingExport: Boolean,
        // CPU/auto work or an unreleased GPU input owner conflicts; foreground export does not.
        val hasConflictingWork: Boolean,
    )

    class GpuDiagnosticPreviewAdmission(val attempt: Attempt?, val snapshot: GpuDiagnosticPreviewSnapshot)

    @Synchronized
    fun tryStartGpuDiagnosticPreviewObserved(observations: AutoDevelopConditions): GpuDiagnosticPreviewAdmission {
        val snapshot = GpuDiagnosticPreviewSnapshot(
            freshPreviewBlockers = Collections.unmodifiableSet(AutoDevelopPolicy.previewBlockers(observations)),
            storedGlobalPreviewBlockers = Collections.unmodifiableSet(
                AutoDevelopPolicy.previewBlockers(conditions.copy(rendererAvailable = true))),
            conditionsObserved = conditionsObserved,
            freshForeground = observations.foreground,
            storedForeground = conditions.foreground,
            freshRendererAvailable = observations.rendererAvailable,
            hasActiveSlot = active != null || gpuPreview != null,
            activeStopRequested = active?.stopRequested ?: gpuPreview?.stopRequested,
            captureCount = captures.size,
            hasPendingExport = pendingExport != null,
            hasConflictingWork = gpuPreview != null || active?.foregroundExport == false,
        )
        val attempt = if (snapshot.hasConflictingWork || captures.isNotEmpty() ||
            snapshot.freshPreviewBlockers.isNotEmpty() ||
            (conditionsObserved && snapshot.storedGlobalPreviewBlockers.isNotEmpty())) null
        else Attempt().also { gpuPreview = Active(it, observed = true, preview = true) }
        return GpuDiagnosticPreviewAdmission(attempt, snapshot)
    }

    private fun startObserved(observations: AutoDevelopConditions, preview: Boolean): Attempt? {
        fun blocked(value: AutoDevelopConditions) = if (preview) AutoDevelopPolicy.previewBlockers(value).isNotEmpty()
            else AutoDevelopPolicy.blockers(value).isNotEmpty()
        if (active != null || gpuPreview != null || pendingExport != null || captures.isNotEmpty() || blocked(observations) ||
            (conditionsObserved && blocked(conditions.copy(rendererAvailable = true)))) return null
        return Attempt().also { active = Active(it, observed = true, preview = preview) }
    }

    /** Only an explicit foreground save may acquire the existing export slot. No background admission.
     * It may coexist with the separate GPU input owner, never with CPU preview/auto work.
     * Subsequent resource updates must come from the service observer for this exact attempt.
     * Capture reservations still stop it immediately and closure is still positively acknowledged.
     */
    @Synchronized fun reserveForegroundExport(owner: Any, observations: AutoDevelopConditions): ForegroundExportRequest? {
        if (!conditionsObserved || AutoDevelopPolicy.blockers(conditions.copy(rendererAvailable = true)).isNotEmpty() ||
            active != null || pendingExport != null || captures.isNotEmpty() || AutoDevelopPolicy.blockers(observations).isNotEmpty()) return null
        return ForegroundExportRequest(owner).also { pendingExport = it }
    }

    @Synchronized fun tryStartForegroundExport(request: ForegroundExportRequest, owner: Any,
        observations: AutoDevelopConditions): Attempt? {
        if (pendingExport !== request || request.owner !== owner || request.stopped || active != null ||
            captures.isNotEmpty() || exportBlockers(observations).isNotEmpty()) return null
        pendingExport = null
        return Attempt().also { active = Active(it, observed = true, foregroundExport = true) }
    }

    @Synchronized fun stopForegroundExport(request: ForegroundExportRequest) {
        if (pendingExport === request) request.stopped = true
    }
    @Synchronized fun abandonForegroundExport(request: ForegroundExportRequest) {
        if (pendingExport === request) pendingExport = null
    }

    @Synchronized fun updateExportConditions(attempt: Attempt, observations: AutoDevelopConditions) {
        val current = active ?: return
        if (current.attempt === attempt && current.foregroundExport && (exportBlockers(observations) - AutoDevelopBlocker.MEMORY).isNotEmpty())
            current.stopRequested = true
    }

    private fun exportBlockers(observations: AutoDevelopConditions) =
        AutoDevelopPolicy.blockers(observations) - AutoDevelopBlocker.FOREGROUND

    /** Stop is latched for the current attempt; it is not resource-release acknowledgement. */
    @Synchronized
    fun requestStop() { active?.stopRequested = true; gpuPreview?.stopRequested = true; pendingExport?.stopped = true }

    /** A delayed stop for an old owner must not cancel an unrelated newly admitted attempt. */
    @Synchronized
    fun requestStop(attempt: Attempt) {
        if (active?.attempt === attempt) active?.stopRequested = true
        if (gpuPreview?.attempt === attempt) gpuPreview?.stopRequested = true
    }

    /** Unknown, completed, and foreign attempts must stop as well. */
    @Synchronized
    fun shouldStop(attempt: Attempt): Boolean {
        val current = active?.takeIf { it.attempt === attempt }
            ?: gpuPreview?.takeIf { it.attempt === attempt } ?: return true
        return current.stopRequested
    }

    /**
     * The worker must call this only AFTER its reader/writer resources have actually closed,
     * including on failure. Cancellation alone must not call this. No lease count is persisted.
     * If a worker hangs or cannot confirm closure, its slot stays held while capture remains usable.
     */
    @Synchronized
    fun completeAfterResourcesClosed(attempt: Attempt): Boolean {
        when (attempt) {
            active?.attempt -> active = null
            gpuPreview?.attempt -> gpuPreview = null
            else -> return false
        }
        return true
    }
}
