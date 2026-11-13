package dev.cinematic.p0.gpu.preview

import dev.cinematic.p0.photoroll.PhotoRollObservationMetadata
import dev.cinematic.p0.photoroll.PhotoRollObservedCamera
import dev.cinematic.p0.rawcapture.AutoDevelopBlocker
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopCoordinator
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout

internal const val GPU_INPUT_READINESS_TIMEOUT_MS = 10_000L
internal enum class GpuInputWaitReason { CAMERA_UNKNOWN, CAMERA_IN_USE, OTHER_PREPARATION }
internal class GpuInputReadinessTimeout(val reason: GpuInputWaitReason) : IOException("GPU input readiness timed out")
internal class GpuInputDeadlineElapsed : IOException("GPU input admission deadline elapsed")

/** One GPU request, not a queue or process admission. Only the existing short coordinator acquire
 * may run inside tryAdmit. No resource snapshot, IO, main/GL wait or caller notification runs there.
 * The deadline is checked after entering this guard, immediately before the short acquire decision.
 * A positive decision wins over timeout waiting on this guard; native work never holds the guard.
 * The decision and timeout/cancel are serialized, so actual admission cannot become a later timeout.
 */
internal class GpuInputRequestDeadline(
    timeoutMs: Long = GPU_INPUT_READINESS_TIMEOUT_MS,
    private val nowMillis: () -> Long,
) {
    private val deadline = nowMillis() + timeoutMs.also { require(it > 0) }
    private var admittedAt: Long? = null
    private var cancelled = false
    private var expired = false

    fun remainingMillis(): Long = (deadline - nowMillis()).coerceAtLeast(0)
    @Synchronized fun isAdmitted(): Boolean = admittedAt != null
    /** Post-acquire guard commit timestamp, not a native-start or device-performance measurement. */
    @Synchronized fun admittedAtMillis(): Long? = admittedAt
    @Synchronized fun cancel() { cancelled = true }
    @Synchronized fun expire(): Boolean {
        if (cancelled) throw CancellationException("GPU input cancelled")
        if (admittedAt != null) return false
        expired = true
        return true
    }
    @Synchronized fun <T : Any> tryAdmit(acquire: () -> T?): T? {
        if (cancelled) throw CancellationException("GPU input cancelled")
        if (expired || remainingMillis() == 0L) { expired = true; throw GpuInputDeadlineElapsed() }
        check(admittedAt == null) { "GPU input was already admitted" }
        return acquire()?.also { admittedAt = nowMillis() }
    }
}

/** Only this slot-before-owner rejection may return to camera observation. Never wrap native errors. */
internal class GpuInputAdmissionRefused(val snapshot: AutoDevelopCoordinator.GpuDiagnosticPreviewSnapshot) :
    IOException("GPU input admission unavailable") {
    val canWaitForCamera: Boolean get() {
        val blockers = snapshot.freshPreviewBlockers +
            if (snapshot.conditionsObserved) snapshot.storedGlobalPreviewBlockers else emptySet()
        return !snapshot.hasConflictingWork && snapshot.captureCount == 0 &&
            blockers == setOf(AutoDevelopBlocker.CAMERA_SESSION)
    }
}

/** GPU-only event identity. Equal metadata at a NEW existing publication is still an observation. */
internal data class GpuInputPublication(val sequence: Long = 0,
    val observation: PhotoRollObservationMetadata = PhotoRollObservationMetadata(
        AutoDevelopConditions(), 0, 0, false, false, false, false))
internal data class GpuInputAdmissionResult(val failure: Throwable?)

/** The single 10s preparation bound matches local diagnostic readiness tests, not native decode time.
 * StateFlow retains the latest event, including publication between IO refusal and main resumption.
 * The observer's existing cadence is unchanged; no GPU poll, delay or speculative acquire is added.
 */
internal suspend fun awaitGpuInputReadiness(
    publications: StateFlow<GpuInputPublication>,
    surfaceAttached: StateFlow<Boolean>,
    request: GpuInputRequestDeadline,
    checkMemoryAdmission: () -> Unit = {},
    start: (GpuInputPublication) -> Deferred<GpuInputAdmissionResult>,
) {
    var rejectedAfter: Long? = null
    fun timeout(): GpuInputReadinessTimeout {
        val latest = publications.value
        val reason = when (latest.observation.cameraState) {
            PhotoRollObservedCamera.IN_USE -> GpuInputWaitReason.CAMERA_IN_USE
            PhotoRollObservedCamera.UNKNOWN -> GpuInputWaitReason.CAMERA_UNKNOWN
            PhotoRollObservedCamera.AVAILABLE -> if (latest.sequence == rejectedAfter) GpuInputWaitReason.CAMERA_UNKNOWN
                else GpuInputWaitReason.OTHER_PREPARATION
        }
        return GpuInputReadinessTimeout(reason)
    }
    try {
        withTimeout(request.remainingMillis()) {
            while (true) {
                val ready = combine(publications, surfaceAttached) { publication, attached ->
                    if (attached && publication.observation.conditions.previewMemoryAvailable == false)
                        checkMemoryAdmission()
                    publication.takeIf { attached && it.sequence != rejectedAfter &&
                        it.observation.cameraState == PhotoRollObservedCamera.AVAILABLE &&
                        AutoDevelopPolicy.previewBlockers(it.observation.conditions).isEmpty() }
                }.first { it != null }!!
                currentCoroutineContext().ensureActive()
                val result = start(ready).await()
                val failure = result.failure
                if (failure == null) { check(request.isAdmitted()); return@withTimeout }
                if (failure is GpuInputAdmissionRefused &&
                    AutoDevelopBlocker.MEMORY in (failure.snapshot.freshPreviewBlockers +
                        if (failure.snapshot.conditionsObserved) failure.snapshot.storedGlobalPreviewBlockers else emptySet())) {
                    // A newer IO observation can reject after a ready UI publication.
                    // Classify only a current OS rejection; if recovered, preserve the original refusal.
                    checkMemoryAdmission()
                }
                if (failure !is GpuInputAdmissionRefused || !failure.canWaitForCamera) throw failure
                // Consume only the observation that started this attempt. A publication can arrive
                // after coordinator refusal but before its callback; it must remain eligible.
                rejectedAfter = ready.sequence
            }
        }
    } catch (cancelled: CancellationException) {
        if (cancelled is TimeoutCancellationException && currentCoroutineContext().isActive) {
            // IO may have admitted before the deadline, but Main resumed after the timeout fired.
            // Its native future is independent of this wait and must continue in that case.
            if (!request.expire()) return
            throw timeout()
        }
        request.cancel()
        throw cancelled
    } catch (_: GpuInputDeadlineElapsed) {
        currentCoroutineContext().ensureActive()
        throw timeout()
    }
}
