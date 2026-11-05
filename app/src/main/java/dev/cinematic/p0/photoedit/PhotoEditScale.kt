package dev.cinematic.p0.photoedit

import kotlin.math.abs

/** UI coordinates only. The caller supplies the agreed range/step; this is not a render recipe. */
internal data class PhotoEditScale(val minimum: Double, val maximum: Double, val tapStep: Double) {
    val span get() = maximum - minimum

    init {
        require(minimum.isFinite() && maximum.isFinite() && minimum < maximum && span.isFinite())
        require(tapStep.isFinite() && tapStep > 0.0 && tapStep <= span)
        require(minimum + tapStep > minimum && maximum - tapStep < maximum)
    }

    fun contains(value: Double) = value.isFinite() && value in minimum..maximum

    fun step(value: Double, direction: Int): Double? {
        if (!contains(value) || direction !in listOf(-1, 1)) return null
        val next = (value + direction * tapStep).coerceIn(minimum, maximum)
        return next.takeIf { it != value }
    }

    fun fraction(value: Double): Double {
        require(contains(value))
        return ((value - minimum) / span).coerceIn(0.0, 1.0)
    }

    fun atFraction(fraction: Double): Double {
        require(fraction.isFinite())
        if (fraction <= 0.0) return minimum
        if (fraction >= 1.0) return maximum
        return (minimum + fraction.coerceIn(0.0, 1.0) * span).coerceIn(minimum, maximum)
    }
}

internal sealed interface PhotoEditBarInput {
    data class RelativeDrag(val fractionDelta: Double) : PhotoEditBarInput
    data class Step(val direction: Int) : PhotoEditBarInput
    data class SetValue(val value: Double) : PhotoEditBarInput
}

internal data class PhotoEditBarUpdate(val input: PhotoEditBarInput? = null, val consume: Boolean = false)

/**
 * One pointer, relative horizontal drag, or a single +/- tap beside the thumb. A tap never seeks.
 * A vertical scroll, consumed event, extra pointer or caller invalidation cancels the gesture.
 * Shared by the Compose adapter and pure input tests; it owns no photo, job or persistent state.
 */
internal class PhotoEditBarGesture(
    scale: PhotoEditScale,
    startValue: Double,
    private val downX: Float,
    private val downY: Float,
    trackLeft: Float,
    private val trackWidth: Float,
    private val touchSlop: Float,
    thumbRadius: Float,
) {
    private var dragging = false
    private var previousX = downX
    private val tapDirection: Int
    var ended = false
        private set

    init {
        require(scale.contains(startValue))
        require(listOf(downX, downY, trackLeft, trackWidth, touchSlop, thumbRadius).all { it.isFinite() })
        require(trackWidth > 0f && touchSlop >= 0f && thumbRadius >= 0f)
        val thumbX = trackLeft + scale.fraction(startValue) * trackWidth
        tapDirection = when {
            downX < thumbX - thumbRadius -> -1
            downX > thumbX + thumbRadius -> 1
            else -> 0
        }
    }

    fun update(x: Float, y: Float, pressed: Boolean, allowed: Boolean): PhotoEditBarUpdate {
        if (ended) return PhotoEditBarUpdate()
        if (!allowed || !x.isFinite() || !y.isFinite()) {
            ended = true
            return PhotoEditBarUpdate()
        }
        val dx = x.toDouble() - downX
        val dy = y.toDouble() - downY
        if (!dragging && abs(dy) > touchSlop && abs(dy) >= abs(dx)) {
            ended = true
            return PhotoEditBarUpdate()
        }
        if (!dragging && abs(dx) > touchSlop) dragging = true
        if (!pressed) ended = true
        if (dragging) {
            // Only pointer motion is retained. The dispatcher applies each delta to the actual owner's draft.
            // Advance even at an endpoint, so outward motion is discarded rather than retraced on reversal.
            val fractionDelta = (x.toDouble() - previousX) / trackWidth
            previousX = x
            val input = fractionDelta.takeIf { it != 0.0 }?.let { PhotoEditBarInput.RelativeDrag(it) }
            return PhotoEditBarUpdate(input, consume = true)
        }
        if (pressed) return PhotoEditBarUpdate()
        return PhotoEditBarUpdate(tapDirection.takeIf { it != 0 }?.let { PhotoEditBarInput.Step(it) }, consume = true)
    }
}
