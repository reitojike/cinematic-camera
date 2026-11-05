package dev.cinematic.p0.rawcapture

/** Common process admission, using the existing coordinator and queue. No second executor/semaphore.
 * Release through this facade so interactive completion also wakes queued manual/auto work. */
internal object DevelopExecution {
    val coordinator = AutoDevelopCoordinator()
    @Volatile private var queue: AutoDevelopQueue? = null
    @Synchronized fun attach(owner: AutoDevelopQueue) {
        check(queue == null || queue === owner) { "Retain the live process queue" }
        queue = owner
    }
    fun updateConditions(observations: AutoDevelopConditions) {
        if (AutoDevelopPolicy.blockers(observations).isNotEmpty()) PrivateTrialReuseRegistry.invalidateAll()
        val owner = queue
        if (owner == null) coordinator.updateConditions(observations) else owner.updateConditions(observations)
    }
    fun tryStartObserved(observations: AutoDevelopConditions) = coordinator.tryStartObserved(observations)
    fun shouldStop(attempt: AutoDevelopCoordinator.Attempt) = coordinator.shouldStop(attempt)
    fun requestStop(attempt: AutoDevelopCoordinator.Attempt) { coordinator.requestStop(attempt); queue?.stopObserved(attempt) }
    fun completeAfterResourcesClosed(attempt: AutoDevelopCoordinator.Attempt): Boolean =
        coordinator.completeAfterResourcesClosed(attempt).also { released -> if (released) queue?.wake() }
    fun reserveCapture() = run {
        PrivateTrialReuseRegistry.invalidateAll()
        queue?.reserveCapture() ?: coordinator.reserveCapture()
    }
    fun releaseCapture(reservation: AutoDevelopCoordinator.CaptureReservation) {
        val owner = queue
        if (owner == null) coordinator.releaseCapture(reservation) else owner.releaseCapture(reservation)
    }
}
