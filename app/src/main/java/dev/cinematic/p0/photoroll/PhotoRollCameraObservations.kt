package dev.cinematic.p0.photoroll

/** Application work is distinct from CameraManager's hardware availability. */
internal data class PhotoRollCameraSnapshot(
    val recordingOrFinalizing: Boolean? = null,
    val rebindInProgress: Boolean? = null,
    val cameraMayStart: Boolean? = null,
    val captureInProgress: Boolean? = null,
)

/** Read on the camera/UI thread. Each old owner keeps its own late-finalize/unknown state. */
internal class PhotoRollCameraOwners {
    private val readers = linkedMapOf<Owner, () -> PhotoRollCameraSnapshot>()

    inner class Owner internal constructor() {
        /** Drop the Activity closure while preserving an unconfirmed old owner. */
        fun holdUnknown() { if (readers.containsKey(this)) readers[this] = { PhotoRollCameraSnapshot() } }
        /** Only after this owner's application work has actually finished. */
        fun close() { readers.remove(this) }
    }

    fun register(read: () -> PhotoRollCameraSnapshot): Owner = Owner().also { readers[it] = read }

    /** Separate from the UI ticket: cancellation/recreation cannot erase actual capture work. */
    fun holdCaptureUntil(closed: () -> Boolean) {
        lateinit var owner: Owner
        owner = register {
            if (closed()) { owner.close(); PhotoRollCameraSnapshot(false, false, false, false) }
            else PhotoRollCameraSnapshot(false, false, false, true)
        }
    }

    fun snapshot(): PhotoRollCameraSnapshot {
        val values = readers.values.toList().map { read -> try { read() } catch (_: Exception) { PhotoRollCameraSnapshot() } }
        fun combine(axis: (PhotoRollCameraSnapshot) -> Boolean?): Boolean? = when {
            values.any { axis(it) == true } -> true
            values.isEmpty() || values.any { axis(it) == null } -> null
            else -> false
        }
        return PhotoRollCameraSnapshot(combine { it.recordingOrFinalizing }, combine { it.rebindInProgress },
            combine { it.cameraMayStart }, combine { it.captureInProgress })
    }
}
