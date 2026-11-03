package dev.cinematic.p0

/** Explicit reconnection is available only after control confirmation failed without a capture owner. */
data class ControlRecoveryState(
    val epoch: Int,
    val bound: Boolean,
    val foreground: Boolean,
    val closed: Boolean,
    val busy: Boolean,
    val recordingBusy: Boolean,
    val captureActive: Boolean,
    val ownsCapture: Boolean,
    val controlPhase: ControlPhase,
    val stillPhase: StillExposurePhase?,
) {
    fun canReconnect(expectedEpoch: Int): Boolean = expectedEpoch == epoch && bound && foreground &&
        !closed && !busy && !recordingBusy && !captureActive && !ownsCapture &&
        (controlPhase == ControlPhase.UNKNOWN || stillPhase == StillExposurePhase.UNKNOWN)
}
