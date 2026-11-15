package dev.cinematic.p0.previewcompare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/** Shared zoom/pan is retained when switching equal-sized AHD/PPG images. */
internal class CompareImageView(context: Context) : ImageView(context) {
    private var shape = 0L
    private val transform = Matrix()
    private var zoom = 1f
    private var x = 0f
    private var y = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            transform.postScale(next / zoom, next / zoom, detector.focusX, detector.focusY)
            zoom = next; imageMatrix = transform
            return true
        }
    })
    init { scaleType = ScaleType.MATRIX; contentDescription = "比較画像。ピンチで拡大し、ドラッグで位置を移動できます。" }
    override fun setImageBitmap(bitmap: Bitmap?) {
        val next = bitmap?.let { (it.width.toLong() shl 32) or it.height.toLong() } ?: 0L
        val reset = next != shape
        super.setImageBitmap(bitmap)
        shape = next
        if (reset) fit()
    }
    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0) return
        val factor = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        transform.reset(); transform.postScale(factor, factor)
        transform.postTranslate((width - d.intrinsicWidth * factor) / 2, (height - d.intrinsicHeight * factor) / 2)
        zoom = 1f; imageMatrix = transform
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.x; y = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!scale.isInProgress && event.pointerCount == 1) {
                    transform.postTranslate(event.x - x, event.y - y); imageMatrix = transform
                }
                x = event.x; y = event.y
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
