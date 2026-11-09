package dev.cinematic.p0.rawcapture

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
    private var pendingExport: ForegroundExportRequest? = null
    private val captures = mutableSetOf<CaptureReservation>()

    @Synchronized
    fun updateConditions(value: AutoDevelopConditions) {
        conditions = value
        conditionsObserved = true
        // Job-local renderer availability must not cancel an interactive job with another capability.
        val current = active
        // A started export has its own service-scoped observer. Activity pause/teardown must not
        // replace that observer's current resource facts with an incomplete UI snapshot.
        if (current?.foregroundExport == true) return
        val common = if (current?.observed == true) value.copy(rendererAvailable = true) else value
        val blockers = if (current?.preview == true) AutoDevelopPolicy.previewBlockers(common)
            else AutoDevelopPolicy.blockers(common)
        if (blockers.isNotEmpty()) current?.stopRequested = true
    }

    /** Never waits for the admitted worker to stop or close its resources. */
    @Synchronized
    fun reserveCapture(): CaptureReservation {
        val reservation = CaptureReservation()
        captures.add(reservation)
        active?.stopRequested = true
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
        if (active != null || pendingExport != null || captures.isNotEmpty() || AutoDevelopPolicy.blockers(conditions).isNotEmpty()) {
            return null
        }
        return Attempt().also { active = Active(it) }
    }

    /** Interactive caller supplies fresh observations including its own renderer capability.
     * The caller must keep checking observations and cancellation while it owns resources. */
    @Synchronized fun tryStartObserved(observations: AutoDevelopConditions): Attempt? = startObserved(observations, preview = false)

    /** Preview ownership is distinct from save admission, not a second slot or executor. */
    @Synchronized fun tryStartPreviewObserved(observations: AutoDevelopConditions): Attempt? = startObserved(observations, preview = true)

    private fun startObserved(observations: AutoDevelopConditions, preview: Boolean): Attempt? {
        fun blocked(value: AutoDevelopConditions) = if (preview) AutoDevelopPolicy.previewBlockers(value).isNotEmpty()
            else AutoDevelopPolicy.blockers(value).isNotEmpty()
        if (active != null || pendingExport != null || captures.isNotEmpty() || blocked(observations) ||
            (conditionsObserved && blocked(conditions.copy(rendererAvailable = true)))) return null
        return Attempt().also { active = Active(it, observed = true, preview = preview) }
    }

    /** Only an explicit foreground save may acquire this one slot. No background admission.
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
        if (current.attempt === attempt && current.foregroundExport && exportBlockers(observations).isNotEmpty())
            current.stopRequested = true
    }

    private fun exportBlockers(observations: AutoDevelopConditions) =
        AutoDevelopPolicy.blockers(observations) - AutoDevelopBlocker.FOREGROUND

    /** Stop is latched for the current attempt; it is not resource-release acknowledgement. */
    @Synchronized
    fun requestStop() { active?.stopRequested = true; pendingExport?.stopped = true }

    /** A delayed stop for an old owner must not cancel an unrelated newly admitted attempt. */
    @Synchronized
    fun requestStop(attempt: Attempt) {
        if (active?.attempt === attempt) active?.stopRequested = true
    }

    /** Unknown, completed, and foreign attempts must stop as well. */
    @Synchronized
    fun shouldStop(attempt: Attempt): Boolean {
        val current = active ?: return true
        return current.attempt !== attempt || current.stopRequested
    }

    /**
     * The worker must call this only AFTER its reader/writer resources have actually closed,
     * including on failure. Cancellation alone must not call this. No lease count is persisted.
     * If a worker hangs or cannot confirm closure, its slot stays held while capture remains usable.
     */
    @Synchronized
    fun completeAfterResourcesClosed(attempt: Attempt): Boolean {
        if (active?.attempt !== attempt) return false
        active = null
        return true
    }
}
