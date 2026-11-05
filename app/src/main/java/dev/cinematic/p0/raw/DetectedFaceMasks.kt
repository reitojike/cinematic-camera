package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException
import kotlin.math.hypot
import kotlin.math.max

/** Caller-declared box in the target raster; no detection, coordinate mapping or foundation ROI. */
internal data class DetectedFaceBox(val x: Int, val y: Int, val width: Int, val height: Int) {
    init {
        require(x in -4096..4096 && y in -4096..4096 && width in 1..4096 && height in 1..4096) {
            "Face box is outside the supported diagnostic domain"
        }
    }
}

internal object DetectedFaceMasks {
    const val MAX_BOXES = 64
    const val MAX_MASKED = 6

    /** PC face_area geometry only. The caller supplies fixed boxes; this does not verify faces. */
    fun fromBoxes(width: Int, height: Int, boxes: List<DetectedFaceBox>, shouldStop: () -> Boolean = { false }): FaceMask {
        fun checkpoint() { if (shouldStop()) throw CancellationException("Face mask stopped") }
        checkpoint()
        require(width in 1..1024 && height in 1..1024 && boxes.size <= MAX_BOXES)
        val selected = boxes.sortedByDescending { it.width.toLong() * it.height }.take(MAX_MASKED)
        checkpoint()
        val values = FloatArray(width * height)
        for (box in selected) {
            val cx = (box.x + box.width / 2.0).toFloat()
            val cy = (box.y + box.height * .62).toFloat()
            val rx = max(box.width * .58, 1.0).toFloat()
            val ry = max(box.height * .72, 1.0).toFloat()
            for (y in 0 until height) {
                checkpoint()
                for (x in 0 until width) {
                    val radius = hypot((x + .5f - cx) / rx, (y + .5f - cy) / ry)
                    val weight = ((1.25f - radius) / .35f).coerceIn(0f, 1f)
                    val index = y * width + x
                    values[index] = max(values[index], weight)
                }
            }
        }
        val mask = FaceMask(width, height, values)
        checkpoint()
        return mask
    }
}
