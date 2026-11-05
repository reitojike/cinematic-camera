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
        return (minimum + fraction.coerceIn(0.0, 1.0) * span).coerceIn(minimum, maximum)
    }
}

internal data class PhotoEditBarUpdate(val value: Double? = null, val consume: Boolean = false)

/**
 * One pointer, relative horizontal drag, or a single +/- tap beside the thumb. A tap never seeks.
 * A vertical scroll, consumed event, extra pointer or caller invalidation cancels the gesture.
 * Shared by the Compose adapter and pure input tests; it owns no photo, job or persistent state.
 */
internal class PhotoEditBarGesture(
    private val scale: PhotoEditScale,
    private val startValue: Double,
    private val downX: Float,
    private val downY: Float,
    private val trackLeft: Float,
    private val trackWidth: Float,
    private val touchSlop: Float,
    private val thumbRadius: Float,
) {
    private var dragging = false
    private var previousX = downX
    private var draggedValue = startValue
    var ended = false
        private set

    init {
        require(scale.contains(startValue))
        require(listOf(downX, downY, trackLeft, trackWidth, touchSlop, thumbRadius).all { it.isFinite() })
        require(trackWidth > 0f && touchSlop >= 0f && thumbRadius >= 0f)
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
            // Starting anywhere on the bar preserves the current value, rather than jumping to the finger.
            // Discard outward motion at an endpoint so reversing immediately changes the value again.
            val fraction = scale.fraction(draggedValue) + (x.toDouble() - previousX) / trackWidth
            previousX = x
            draggedValue = scale.atFraction(fraction)
            return PhotoEditBarUpdate(draggedValue, consume = true)
        }
        if (pressed) return PhotoEditBarUpdate()
        val thumbX = trackLeft + scale.fraction(startValue) * trackWidth
        val direction = when {
            downX < thumbX - thumbRadius -> -1
            downX > thumbX + thumbRadius -> 1
            else -> 0
        }
        return PhotoEditBarUpdate(scale.step(startValue, direction), consume = true)
    }
}
