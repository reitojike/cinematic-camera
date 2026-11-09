package dev.cinematic.p0.rawcapture

/** Common process admission, using the existing coordinator and queue. No second executor/semaphore.
 * Release through this facade so interactive completion also wakes queued manual/auto work. */
internal object DevelopExecution {
    val coordinator = AutoDevelopCoordinator()
    private val manualStop = DevelopStopHook()
    @Volatile private var queue: AutoDevelopQueue? = null
    @Synchronized fun attach(owner: AutoDevelopQueue) {
        check(queue == null || queue === owner) { "Retain the live process queue" }
        queue = owner
    }
    fun updateConditions(observations: AutoDevelopConditions) {
        if (AutoDevelopPolicy.blockers(observations).isNotEmpty()) {
            PrivateTrialReuseRegistry.invalidateAll()
        }
        if (AutoDevelopPolicy.previewBlockers(observations).isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 29)
            AndroidManualOriginalTrialSaves.invalidatePreviews()
        val owner = queue
        if (owner == null) coordinator.updateConditions(observations) else owner.updateConditions(observations)
        manualStop.stopIfRequested(coordinator)
    }
    fun tryStartObserved(observations: AutoDevelopConditions) = coordinator.tryStartObserved(observations)
    fun tryStartPreviewObserved(observations: AutoDevelopConditions) = coordinator.tryStartPreviewObserved(observations)
    fun reserveForegroundExport(owner: Any, observations: AutoDevelopConditions) = coordinator.reserveForegroundExport(owner, observations)
    fun tryStartForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest, owner: Any,
        observations: AutoDevelopConditions) = coordinator.tryStartForegroundExport(request, owner, observations)
    fun stopForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest) = coordinator.stopForegroundExport(request)
    fun abandonForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest) {
        coordinator.abandonForegroundExport(request); queue?.wake()
    }
    fun updateExportConditions(attempt: AutoDevelopCoordinator.Attempt, observations: AutoDevelopConditions) {
        coordinator.updateExportConditions(attempt, observations)
        manualStop.stopIfRequested(coordinator)
    }
    fun shouldStop(attempt: AutoDevelopCoordinator.Attempt) = coordinator.shouldStop(attempt)
    fun requestStop(attempt: AutoDevelopCoordinator.Attempt) {
        coordinator.requestStop(attempt); queue?.stopObserved(attempt)
        manualStop.stopIfRequested(coordinator)
    }
    fun registerStop(attempt: AutoDevelopCoordinator.Attempt, stop: () -> Unit) {
        manualStop.bind(attempt, stop)
        manualStop.stopIfRequested(coordinator) // Includes a capture reserved before registration.
    }
    fun completeAfterResourcesClosed(attempt: AutoDevelopCoordinator.Attempt): Boolean {
        manualStop.clear(attempt) // Clear before admitting a new identity, after caller's positive close.
        return coordinator.completeAfterResourcesClosed(attempt).also { if (it) queue?.wake() }
    }
    fun reserveCapture() = run {
        PrivateTrialReuseRegistry.invalidateAll()
        if (android.os.Build.VERSION.SDK_INT >= 29) AndroidManualOriginalTrialSaves.invalidatePreviews()
        (queue?.reserveCapture() ?: coordinator.reserveCapture()).also { manualStop.stopIfRequested(coordinator) }
    }
    fun releaseCapture(reservation: AutoDevelopCoordinator.CaptureReservation) {
        val owner = queue
        if (owner == null) coordinator.releaseCapture(reservation) else owner.releaseCapture(reservation)
    }
}
