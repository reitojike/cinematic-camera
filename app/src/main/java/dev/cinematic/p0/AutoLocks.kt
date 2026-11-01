package dev.cinematic.p0

data class LockCapabilities(val exposure: Boolean = false, val whiteBalance: Boolean = false)

/** Transient for the current binding; never serialized to CapturePreferences. */
data class AutoLocks(val exposure: Boolean = false, val whiteBalance: Boolean = false) {
    val isEmpty get() = !exposure && !whiteBalance

    fun validate(capabilities: LockCapabilities, manualExposure: Boolean, whiteBalanceMode: Int?): AutoLocks {
        require(!exposure || capabilities.exposure) { "AE lock unsupported by characteristics/request keys" }
        require(!whiteBalance || capabilities.whiteBalance) { "AWB lock unsupported by characteristics/request keys" }
        require(!exposure || !manualExposure) { "AE lock requires AUTO exposure" }
        require(!whiteBalance || whiteBalanceMode == null || whiteBalanceMode == 1) { "AWB lock requires AUTO white balance" }
        return this
    }

    fun forControls(manualExposure: Boolean, whiteBalanceMode: Int?): AutoLocks = copy(
        exposure = exposure && !manualExposure,
        whiteBalance = whiteBalance && (whiteBalanceMode == null || whiteBalanceMode == 1),
    )
}
