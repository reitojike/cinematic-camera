package dev.cinematic.p0

import android.view.GestureDetector
import android.view.MotionEvent
import androidx.camera.view.PreviewView

/** Standard tap recognition avoids treating a scroll, drag or long press as a focus tap. */
fun PreviewView.installFocusTap(onCenter: () -> Unit, onTap: (Float, Float) -> Unit) {
    var singleTap = false
    val clickPolicy = PreviewClickPolicy()
    val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            singleTap = true
            onTap(event.x, event.y)
            return true
        }
    })
    isClickable = true
    contentDescription = "Live preview. Tap within the image for AF/AE when idle. Activate to focus and meter at the center."
    setOnClickListener { if (clickPolicy.shouldFocusCenter) onCenter() }
    setOnTouchListener { view, event ->
        singleTap = false
        val handled = gestures.onTouchEvent(event)
        if (singleTap) clickPolicy.notifyTapClick { view.performClick() }
        handled
    }
}
