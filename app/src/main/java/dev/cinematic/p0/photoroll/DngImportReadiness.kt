package dev.cinematic.p0.photoroll

import dev.cinematic.p0.gpu.preview.GPU_INPUT_READINESS_TIMEOUT_MS
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.rawcapture.AutoDevelopBlocker
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Wait for post-resume observation and the narrowly classified initial camera callback sample.
 * Confirmed blockers are not retried. Admission remains authoritative.
 * Reuses the existing bounded input-preparation budget; owns no URI, stream or admission slot. */
internal suspend fun awaitDngImportObservation(publications: StateFlow<GpuInputPublication>,
    timeoutMillis: Long = GPU_INPUT_READINESS_TIMEOUT_MS, stillSelected: () -> Boolean = { true }): Boolean {
    val initial = publications.value
    val observed = withTimeout(timeoutMillis) {
        publications.first { event ->
            val value = event.observation
            val floor = initial.observation
            val newer = value.registrationGeneration > floor.registrationGeneration ||
                value.registrationGeneration == floor.registrationGeneration && value.foregroundEpoch > floor.foregroundEpoch
            val same = value.registrationGeneration == floor.registrationGeneration && value.foregroundEpoch == floor.foregroundEpoch
            // Registration can publish before its initial availability callbacks are sampled.
            // This is observation preparation, not permission to ignore a camera/resource blocker.
            val initialCameraPending = !value.initialCameraCallbacksComplete && !value.cameraUnavailableObserved &&
                AutoDevelopPolicy.blockers(value.conditions) == setOf(AutoDevelopBlocker.CAMERA_SESSION)
            event.sequence >= initial.sequence && (newer || same && value.resourceObserved &&
                value.conditions.foreground == true && !initialCameraPending)
        }
    }
    currentCoroutineContext().ensureActive()
    // A short pause/resume may be conflated by Compose; epoch identity still invalidates the request.
    return observed.observation.registrationGeneration == initial.observation.registrationGeneration &&
        observed.observation.foregroundEpoch == initial.observation.foregroundEpoch && stillSelected()
}
