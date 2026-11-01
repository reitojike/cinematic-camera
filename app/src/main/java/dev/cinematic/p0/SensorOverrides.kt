package dev.cinematic.p0

data class OverrideCapabilities(
    val whiteBalanceModes: Set<Int> = emptySet(),
    val antiBandingModes: Set<Int> = emptySet(),
    val manualFocusMaximum: Float? = null,
    val focusCalibration: String = "UNKNOWN",
)

enum class OverrideField(val preferenceKey: String) { WHITE_BALANCE("wb"), FOCUS("focus"), ANTI_BANDING("antiBanding") }

/** Null means CameraX owns that control. No manual white-balance gains are supplied. */
data class SensorOverrides(
    val whiteBalance: Int? = null,
    val focusDistance: Float? = null,
    val antiBanding: Int? = null,
) {
    val isEmpty get() = whiteBalance == null && focusDistance == null && antiBanding == null

    /** Persist only the group the user edited; fallback values are not new user requests. */
    fun persistencePatch(field: OverrideField): Map<String, String> = mapOf(field.preferenceKey to when (field) {
        OverrideField.WHITE_BALANCE -> whiteBalance?.toString().orEmpty()
        OverrideField.FOCUS -> focusDistance?.toString().orEmpty()
        OverrideField.ANTI_BANDING -> antiBanding?.toString().orEmpty()
    })

    fun validate(capabilities: OverrideCapabilities): SensorOverrides {
        require(whiteBalance == null || whiteBalance in capabilities.whiteBalanceModes) { "WB preset unsupported: $whiteBalance" }
        require(antiBanding == null || antiBanding in capabilities.antiBandingModes) { "AE anti-banding unsupported: $antiBanding" }
        focusDistance?.let { value ->
            val max = capabilities.manualFocusMaximum
            require(max != null && max.isFinite() && max > 0f) { "Manual focus unavailable or fixed-focus lens" }
            require(value.isFinite() && value >= 0f && value <= max) { "Focus must be finite and in 0..$max lens units" }
        }
        return this
    }

    /** Rebind never applies unsupported saved controls and never rewrites the saved request. */
    fun supportedFallback(capabilities: OverrideCapabilities, rejected: (String) -> Unit): SensorOverrides {
        fun <T> keep(name: String, value: T?, valid: () -> Boolean): T? {
            if (value == null || valid()) return value
            rejected("$name=$value unsupported; CameraX default used; saved request retained")
            return null
        }
        return SensorOverrides(
            keep("WB", whiteBalance) { whiteBalance in capabilities.whiteBalanceModes },
            keep("MF", focusDistance) {
                val max = capabilities.manualFocusMaximum
                focusDistance != null && focusDistance.isFinite() && max != null && max.isFinite() && max > 0f && focusDistance in 0f..max
            },
            keep("anti-banding", antiBanding) { antiBanding in capabilities.antiBandingModes },
        )
    }
}

object SensorModeNames {
    fun whiteBalance(mode: Int) = when (mode) {
        1 -> "AUTO"; 2 -> "INCANDESCENT"; 3 -> "FLUORESCENT"; 4 -> "WARM FLUORESCENT"
        5 -> "DAYLIGHT"; 6 -> "CLOUDY DAYLIGHT"; 7 -> "TWILIGHT"; 8 -> "SHADE"; else -> "UNKNOWN ($mode)"
    }
    fun antiBanding(mode: Int) = when (mode) { 0 -> "OFF"; 1 -> "50 Hz"; 2 -> "60 Hz"; 3 -> "AUTO"; else -> "UNKNOWN ($mode)" }
}
