package dev.cinematic.p0

import java.math.BigDecimal
import java.math.RoundingMode

data class ManualExposure(val iso: Int, val exposureNs: Long)
data class ManualExposureInput(val iso: String, val milliseconds: String)

object Exposure {
    /** Only a complete accepted pair may be restored; an input draft is never a fallback. */
    fun savedManual(acceptedIso: String, acceptedMs: String, isoRange: IntRange?, nsRange: LongRange?): ManualExposureInput {
        require(acceptedIso.isNotBlank() && acceptedMs.isNotBlank()) { "Saved MANUAL pair is incomplete; use AUTO reset or apply valid values" }
        parse(acceptedIso, acceptedMs, isoRange, nsRange)
        return ManualExposureInput(acceptedIso, acceptedMs)
    }

    /** Reciprocal of one reported sensor period, not preview-rendered or encoded-video FPS. */
    fun sensorPeriodFps(frameDurationNs: Long?): Double? =
        frameDurationNs?.takeIf { it > 0 }?.let { 1_000_000_000.0 / it }

    fun millisecondsToNs(text: String): Long = BigDecimal(text.trim())
        .multiply(BigDecimal(1_000_000)).setScale(0, RoundingMode.UNNECESSARY).longValueExact()

    fun parse(iso: String, milliseconds: String, isoRange: IntRange?, nsRange: LongRange?): ManualExposure {
        require(isoRange != null && nsRange != null) { "ISO / exposure range unavailable" }
        val value = ManualExposure(iso.trim().toInt(), millisecondsToNs(milliseconds))
        require(value.iso in isoRange) { "ISO must be in $isoRange" }
        require(value.exposureNs in nsRange) { "Exposure must be in $nsRange ns" }
        return value
    }

    fun manualBlockReason(manualSensor: Boolean, aeOff: Boolean, requestKeys: Boolean,
        isoRange: IntRange?, nsRange: LongRange?): String? = when {
        !manualSensor -> "Camera2 MANUAL_SENSOR unsupported"
        !aeOff -> "CONTROL_AE_MODE_OFF unavailable"
        !requestKeys -> "Required CaptureRequest keys unavailable"
        isoRange == null || nsRange == null -> "ISO / exposure range unavailable"
        else -> null
    }
}
