package dev.cinematic.p0.rawcapture

/** Caller-supplied observations; null means unconfirmed, never permission to start. */
internal data class AutoDevelopConditions(
    val foreground: Boolean? = null,
    val cameraSessionInUse: Boolean? = null,
    val recordingOrFinalizing: Boolean? = null,
    val rebindInProgress: Boolean? = null,
    val thermalAllowed: Boolean? = null,
    val storageAllowed: Boolean? = null,
    val rendererAvailable: Boolean? = null,
    val memoryBudgetConfirmed: Boolean? = null,
    // Separate OS-pressure observation for preview work. Full decode still has its own gate.
    val previewMemoryAvailable: Boolean? = null,
)

internal enum class AutoDevelopBlocker {
    FOREGROUND, CAMERA_SESSION, RECORDING_OR_FINALIZING, REBIND, THERMAL, STORAGE, RENDERER, MEMORY,
}

/** Admission only. This does not measure resources, run a renderer, or publish images. */
internal object AutoDevelopPolicy {
    fun previewConditions(conditions: AutoDevelopConditions) =
        conditions.copy(memoryBudgetConfirmed = conditions.previewMemoryAvailable)

    fun previewBlockers(conditions: AutoDevelopConditions) = blockers(previewConditions(conditions))
    fun blockers(conditions: AutoDevelopConditions): Set<AutoDevelopBlocker> = buildSet {
        if (conditions.foreground != true) add(AutoDevelopBlocker.FOREGROUND)
        if (conditions.cameraSessionInUse != false) add(AutoDevelopBlocker.CAMERA_SESSION)
        if (conditions.recordingOrFinalizing != false) add(AutoDevelopBlocker.RECORDING_OR_FINALIZING)
        if (conditions.rebindInProgress != false) add(AutoDevelopBlocker.REBIND)
        if (conditions.thermalAllowed != true) add(AutoDevelopBlocker.THERMAL)
        if (conditions.storageAllowed != true) add(AutoDevelopBlocker.STORAGE)
        if (conditions.rendererAvailable != true) add(AutoDevelopBlocker.RENDERER)
        if (conditions.memoryBudgetConfirmed != true) add(AutoDevelopBlocker.MEMORY)
    }
}
