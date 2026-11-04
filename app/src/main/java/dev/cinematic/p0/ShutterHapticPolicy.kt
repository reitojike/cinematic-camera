package dev.cinematic.p0

/** State observed immediately before and after handling one shutter press. */
internal data class ShutterPressState(
    val mode: CaptureMode,
    val photoStage: PhotoStage,
    val recordingStage: RecordingStage,
)

/** A pulse acknowledges a request accepted synchronously, not a saved image or recording. */
internal fun shutterPressAccepted(before: ShutterPressState, after: ShutterPressState): Boolean {
    if (before.mode != after.mode) return false
    return when (before.mode) {
        CaptureMode.STILL -> before.photoStage !in setOf(PhotoStage.CAPTURING, PhotoStage.SAVING) &&
            after.photoStage == PhotoStage.CAPTURING
        CaptureMode.CINE -> when (before.recordingStage) {
            RecordingStage.IDLE -> after.recordingStage == RecordingStage.STARTING
            RecordingStage.STARTING, RecordingStage.RECORDING -> after.recordingStage == RecordingStage.STOPPING
            RecordingStage.STOPPING, RecordingStage.FINALIZING -> false
        }
    }
}
