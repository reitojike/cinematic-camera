package dev.cinematic.p0.photoedit

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sign

/** Per-gesture accumulator: retain sub-integer travel, discard overshoot at bounds and the baseline. */
internal class PhotoEditRulerDrag(
    private val scale: PhotoEditScale,
    startValue: Double,
    private val baseline: Double? = null,
    private val quantization: Double? = null,
) {
    private var raw = startValue
    private var lastEmitted = startValue
    private var held = baseline != null && startValue == baseline
    private var escape = 0.0
    init {
        require(scale.contains(startValue))
        require(baseline == null || scale.contains(baseline))
        require(quantization == null || quantization.isFinite() && quantization > 0)
    }
    fun move(fractionDelta: Double, ownerValue: Double = lastEmitted): Double {
        require(fractionDelta.isFinite() && scale.contains(ownerValue))
        // Keys or accessibility can change the owner while the pointer is still down.
        if (ownerValue != lastEmitted) {
            raw = ownerValue
            held = baseline != null && ownerValue == baseline
            escape = 0.0
        }
        fun emit(value: Double): Double { lastEmitted = value; return value }
        var delta = fractionDelta * scale.span
        if (held) {
            escape += delta
            val hold = scale.tapStep / 3 // 8dp at the common 24dp per step sensitivity.
            if (abs(escape) <= hold) return emit(requireNotNull(baseline))
            delta = escape - sign(escape) * hold
            held = false
            escape = 0.0
        }
        val next = (raw + delta).coerceIn(scale.minimum, scale.maximum)
        val result = quantization?.let { (round(next / it) * it).coerceIn(scale.minimum, scale.maximum) } ?: next
        if (baseline != null && raw != baseline &&
            ((lastEmitted != baseline && result == baseline) || (raw < baseline && next >= baseline) || (raw > baseline && next <= baseline))) {
            raw = baseline
            held = true
            escape = 0.0
            return emit(baseline)
        }
        raw = next
        return emit(result)
    }
}
