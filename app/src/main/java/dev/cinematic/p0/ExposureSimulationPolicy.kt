package dev.cinematic.p0

import java.math.BigInteger
import kotlin.math.ln
import kotlin.math.ln1p

enum class SimulationStage { COMPENSATED, LONGER_PREVIEW }

data class SimulationPlan(
    val capture: ManualExposure,
    val preview: ManualExposure,
    val stage: SimulationStage,
    val fastExposureNs: Long,
    /** log2((preview ISO * time) / (capture ISO * time)); positive means a larger nominal product. */
    val nominalErrorEv: Double,
)

enum class SimulationBlockReason {
    MISSING_LIMITS,
    INVALID_LIMITS,
    INVALID_FAST_EXPOSURE,
    INVALID_CAPTURE,
    CAPTURE_OUT_OF_RANGE,
    CAPTURE_EXCEEDS_FRAME_DURATION,
    NO_LEGAL_PREVIEW,
}

sealed interface SimulationPlanning {
    data class Ready(val plan: SimulationPlan) : SimulationPlanning
    data class Blocked(val reason: SimulationBlockReason) : SimulationPlanning
}

/**
 * Plans nominal ISO * exposure-time compensation at constant aperture and common gain.
 * Neither the product nor the predicted exposure period guarantees displayed brightness or FPS.
 * This policy does not apply requests or interpret sensor results.
 */
object ExposureSimulationPolicy {
    fun plan(
        capture: ManualExposure,
        isoRange: IntRange?,
        exposureRange: LongRange?,
        maxFrameDurationNs: Long?,
        fastExposureNs: Long = 33_333_333L,
    ): SimulationPlanning {
        if (isoRange == null || exposureRange == null || maxFrameDurationNs == null) {
            return blocked(SimulationBlockReason.MISSING_LIMITS)
        }
        if (isoRange.isEmpty() || isoRange.first <= 0 || exposureRange.isEmpty() ||
            exposureRange.first <= 0L || maxFrameDurationNs <= 0L ||
            exposureRange.first > maxFrameDurationNs
        ) {
            return blocked(SimulationBlockReason.INVALID_LIMITS)
        }
        if (fastExposureNs <= 0L) return blocked(SimulationBlockReason.INVALID_FAST_EXPOSURE)
        if (capture.iso <= 0 || capture.exposureNs <= 0L) {
            return blocked(SimulationBlockReason.INVALID_CAPTURE)
        }
        if (capture.iso !in isoRange || capture.exposureNs !in exposureRange) {
            return blocked(SimulationBlockReason.CAPTURE_OUT_OF_RANGE)
        }
        if (capture.exposureNs > maxFrameDurationNs) {
            return blocked(SimulationBlockReason.CAPTURE_EXCEEDS_FRAME_DURATION)
        }

        val captureProduct = product(capture)
        val fastTime = minOf(capture.exposureNs, fastExposureNs)
        val fastFitsSensor = fastTime >= exposureRange.first
        val fastFitsIso = captureProduct <= integer(isoRange.last) * integer(fastTime)
        val stage: SimulationStage
        val preview: ManualExposure
        if (fastFitsSensor && fastFitsIso) {
            stage = SimulationStage.COMPENSATED
            preview = ManualExposure(nearest(captureProduct, integer(fastTime)).toInt(), fastTime)
        } else {
            stage = SimulationStage.LONGER_PREVIEW
            val atMaxIsoTime = ceiling(captureProduct, integer(isoRange.last)).longValueExact()
            val previewTime = maxOf(exposureRange.first, atMaxIsoTime)
            // When the sensor minimum dominates, max ISO would needlessly enlarge the product.
            val previewIso = if (exposureRange.first > atMaxIsoTime) {
                nearest(captureProduct, integer(previewTime)).toInt()
            } else {
                isoRange.last
            }
            preview = ManualExposure(previewIso, previewTime)
        }
        if (preview.iso !in isoRange || preview.exposureNs !in exposureRange ||
            preview.exposureNs > maxFrameDurationNs
        ) {
            return blocked(SimulationBlockReason.NO_LEGAL_PREVIEW)
        }

        // Subtract exactly first so even tiny errors near Long.MAX_VALUE remain observable.
        val relativeError = (product(preview) - captureProduct).toDouble() / captureProduct.toDouble()
        return SimulationPlanning.Ready(SimulationPlan(
            capture, preview, stage, fastExposureNs, ln1p(relativeError) / ln(2.0),
        ))
    }

    private fun blocked(reason: SimulationBlockReason) = SimulationPlanning.Blocked(reason)
    private fun integer(value: Int): BigInteger = BigInteger.valueOf(value.toLong())
    private fun integer(value: Long): BigInteger = BigInteger.valueOf(value)
    private fun product(exposure: ManualExposure): BigInteger = integer(exposure.iso) * integer(exposure.exposureNs)

    /** Positive integer division, rounding a half upward. */
    private fun nearest(numerator: BigInteger, denominator: BigInteger): BigInteger {
        val (whole, remainder) = numerator.divideAndRemainder(denominator)
        return if (remainder.shiftLeft(1) >= denominator) whole + BigInteger.ONE else whole
    }

    private fun ceiling(numerator: BigInteger, denominator: BigInteger): BigInteger {
        val (whole, remainder) = numerator.divideAndRemainder(denominator)
        return if (remainder.signum() == 0) whole else whole + BigInteger.ONE
    }
}
