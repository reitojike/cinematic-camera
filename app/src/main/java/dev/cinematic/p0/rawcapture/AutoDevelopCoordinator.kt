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

    private class Active(val attempt: Attempt, var stopRequested: Boolean = false)

    private var conditions = AutoDevelopConditions()
    private var active: Active? = null
    private val captures = mutableSetOf<CaptureReservation>()

    @Synchronized
    fun updateConditions(value: AutoDevelopConditions) {
        conditions = value
        if (AutoDevelopPolicy.blockers(value).isNotEmpty()) active?.stopRequested = true
    }

    /** Never waits for the admitted worker to stop or close its resources. */
    @Synchronized
    fun reserveCapture(): CaptureReservation {
        val reservation = CaptureReservation()
        captures.add(reservation)
        active?.stopRequested = true
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
        if (active != null || captures.isNotEmpty() || AutoDevelopPolicy.blockers(conditions).isNotEmpty()) {
            return null
        }
        return Attempt().also { active = Active(it) }
    }

    /** Stop is latched for the current attempt; it is not resource-release acknowledgement. */
    @Synchronized
    fun requestStop() { active?.stopRequested = true }

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
