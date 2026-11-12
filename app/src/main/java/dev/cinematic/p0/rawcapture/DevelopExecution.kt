package dev.cinematic.p0.rawcapture

/** Common process admission; GPU input has a separate bounded owner alongside foreground export.
 * Release through this facade so interactive completion also wakes queued manual/auto work. */
internal object DevelopExecution {
    val coordinator = AutoDevelopCoordinator()
    private val manualStop = DevelopStopHook()
    private val gpuPreviewStop = DevelopStopHook()
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
        stopRequestedOwners()
    }
    fun tryStartObserved(observations: AutoDevelopConditions) = coordinator.tryStartObserved(observations)
    fun tryStartPreviewObserved(observations: AutoDevelopConditions) = coordinator.tryStartPreviewObserved(observations)
    fun tryStartGpuDiagnosticPreviewObserved(observations: AutoDevelopConditions) =
        coordinator.tryStartGpuDiagnosticPreviewObserved(observations)
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
        stopRequestedOwners()
    }
    fun registerStop(attempt: AutoDevelopCoordinator.Attempt, stop: () -> Unit) {
        manualStop.bind(attempt, stop)
        manualStop.stopIfRequested(coordinator) // Includes a capture reserved before registration.
    }
    fun registerGpuPreviewStop(attempt: AutoDevelopCoordinator.Attempt, stop: () -> Unit) {
        gpuPreviewStop.bind(attempt, stop)
        gpuPreviewStop.stopIfRequested(coordinator) // Includes stop/capture before registration.
    }
    fun completeAfterResourcesClosed(attempt: AutoDevelopCoordinator.Attempt): Boolean {
        manualStop.clear(attempt) // Clear before admitting a new identity, after caller's positive close.
        gpuPreviewStop.clear(attempt)
        return coordinator.completeAfterResourcesClosed(attempt).also { if (it) queue?.wake() }
    }
    fun reserveCapture() = run {
        PrivateTrialReuseRegistry.invalidateAll()
        if (android.os.Build.VERSION.SDK_INT >= 29) AndroidManualOriginalTrialSaves.invalidatePreviews()
        (queue?.reserveCapture() ?: coordinator.reserveCapture()).also { stopRequestedOwners() }
    }
    /** A failing native cancellation must not prevent requesting stop from the other owner. */
    private fun stopRequestedOwners() {
        var failure: Throwable? = null
        try { manualStop.stopIfRequested(coordinator) } catch (error: Throwable) { failure = error }
        try { gpuPreviewStop.stopIfRequested(coordinator) }
        catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        failure?.let { throw it }
    }
    fun releaseCapture(reservation: AutoDevelopCoordinator.CaptureReservation) {
        val owner = queue
        if (owner == null) coordinator.releaseCapture(reservation) else owner.releaseCapture(reservation)
    }
}
