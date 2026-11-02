package dev.cinematic.p0

import kotlin.math.abs

/** Sensor degrees are clockwise device orientation, not Surface rotation constants. */
data class CaptureOrientation(val sensorQuarter: Int = 0, val uiAngle: Float = 0f) {
    val surfaceRotation: Int get() = when (sensorQuarter) { 90 -> 3; 180 -> 2; 270 -> 1; else -> 0 }
    val horizontal get() = sensorQuarter == 90 || sensorQuarter == 270
    fun observe(sensorDegrees: Int): CaptureOrientation {
        if (sensorDegrees !in 0..359) return this // ORIENTATION_UNKNOWN preserves last reliable orientation.
        val distance = abs(((sensorDegrees - sensorQuarter + 540) % 360) - 180)
        if (distance <= 57) return this // 45 degree boundary + 12 degree hysteresis.
        val quarter = ((sensorDegrees + 45) / 90 * 90) % 360
        val target = ((360 - quarter) % 360).toFloat()
        val delta = ((target - (uiAngle % 360) + 540) % 360) - 180
        return CaptureOrientation(quarter, uiAngle + delta)
    }
}

/** Compute a proposed update; caller commits applied only after both public setters succeed. */
data class OutputOrientation(val latest: Int = 0, val applied: Int? = null) {
    fun observe(rotation: Int): OutputOrientation {
        require(rotation in 0..3)
        return copy(latest = rotation)
    }
    fun target(photoActive: Boolean, recordingActive: Boolean): Int? =
        if (photoActive || recordingActive) null else latest
}
