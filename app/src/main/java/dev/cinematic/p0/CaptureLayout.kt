package dev.cinematic.p0

import kotlin.math.min

/** Available preview excludes a sheet and capture rail; FIT never crops source pixels. */
object CaptureLayout {
    fun fit(width: Float, height: Float, imageWidth: Float, imageHeight: Float): Pair<Float, Float> {
        require(listOf(width, height, imageWidth, imageHeight).all { it.isFinite() && it > 0 })
        val scale = min(width / imageWidth, height / imageHeight)
        return imageWidth * scale to imageHeight * scale
    }
    fun sheetHeight(available: Float): Float = min(344f, (available - 96f - 120f).coerceAtLeast(0f))
}
