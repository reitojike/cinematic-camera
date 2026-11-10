package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import java.util.concurrent.atomic.AtomicLong

internal enum class PhotoRollObservedCamera { UNKNOWN, IN_USE, AVAILABLE }

/** Metadata only. No camera identifiers, admission permission or new publication cadence. */
internal data class PhotoRollObservationMetadata(
    val conditions: AutoDevelopConditions,
    val registrationGeneration: Long,
    val foregroundEpoch: Long,
    val resourceObserved: Boolean,
    val initialCameraCallbacksComplete: Boolean,
    val cameraUnavailableObserved: Boolean,
    val cameraSampleConsistent: Boolean,
) {
    val cameraState: PhotoRollObservedCamera get() = when {
        !resourceObserved || !cameraSampleConsistent -> PhotoRollObservedCamera.UNKNOWN
        conditions.cameraSessionInUse == true && (cameraUnavailableObserved || initialCameraCallbacksComplete) ->
            PhotoRollObservedCamera.IN_USE
        conditions.cameraSessionInUse == false && initialCameraCallbacksComplete && !cameraUnavailableObserved ->
            PhotoRollObservedCamera.AVAILABLE
        else -> PhotoRollObservedCamera.UNKNOWN
    }
}

/** CameraManager callbacks use one main Handler. Only an opted-in observer creates this sampler.
 * A callback during the unchanged resource snapshot invalidates its metadata, never its conditions.
 */
internal class PhotoRollCameraObservationSampler {
    data class Sample(val generation: Long, val revision: Long, val complete: Boolean, val unavailable: Boolean)
    private val revision = AtomicLong()

    fun change(writeAvailability: () -> Unit) {
        revision.incrementAndGet()
        try { writeAvailability() } finally { revision.incrementAndGet() }
    }

    fun capture(generation: Long, registered: Boolean, ids: Set<String>?, available: Map<String, Boolean>): Sample {
        val before = revision.get()
        val known = ids?.takeIf { registered && it.isNotEmpty() }
        return Sample(generation, before, known?.all { available.containsKey(it) } == true,
            known?.any { available[it] == false } == true)
    }

    fun consistent(sample: Sample, generation: Long): Boolean =
        sample.generation == generation && sample.revision % 2L == 0L && sample.revision == revision.get()
}

/** Null listener returns the original function itself. Observation failure cannot change publication. */
internal fun photoRollPublicationWithMetadata(
    publish: (AutoDevelopConditions) -> Unit,
    listener: ((PhotoRollObservationMetadata) -> Unit)?,
    observation: (AutoDevelopConditions) -> PhotoRollObservationMetadata?,
): (AutoDevelopConditions) -> Unit {
    if (listener == null) return publish
    return { conditions ->
        publish(conditions)
        try { observation(conditions)?.let(listener) } catch (_: Throwable) { /* Metadata only. */ }
    }
}
