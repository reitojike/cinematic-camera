package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopCoordinator
import dev.cinematic.p0.rawcapture.DevelopExecution

/** Main-thread camera owner. Reserve before CameraX lifecycle resumes; never wait for a worker. */
internal class PhotoRollCameraPriority(
    private val reserve: () -> AutoDevelopCoordinator.CaptureReservation = DevelopExecution::reserveCapture,
    private val release: (AutoDevelopCoordinator.CaptureReservation) -> Unit = DevelopExecution::releaseCapture,
    private val invalidate: () -> Unit = { DevelopExecution.updateConditions(AutoDevelopConditions()) },
) {
    private var reservation: AutoDevelopCoordinator.CaptureReservation? = null
    fun starting() { if (reservation == null) reservation = reserve() }
    fun background() {
        val owned = reservation ?: return
        // Drop the old allowed snapshot BEFORE removing this priority window.
        invalidate()
        release(owned)
        reservation = null
    }
}
