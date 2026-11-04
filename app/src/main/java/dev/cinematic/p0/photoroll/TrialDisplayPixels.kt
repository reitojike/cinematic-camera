package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.LinearSrgbImage
import java.util.concurrent.CancellationException
import kotlin.math.pow
import kotlin.math.roundToInt

/** Display encoding only: no exposure, WB, R7 or look. The input remains scene-linear. */
internal object TrialDisplayPixels {
    fun encode(image: LinearSrgbImage, maxEdge: Int, shouldStop: () -> Boolean): IntArray {
        require(maxEdge in 1..1024 && image.width in 1..maxEdge && image.height in 1..maxEdge)
        if (shouldStop()) throw CancellationException("Display conversion cancelled")
        val output = IntArray(image.width * image.height)
        for (y in 0 until image.height) {
            if (shouldStop()) throw CancellationException("Display conversion cancelled")
            for (x in 0 until image.width) {
                val pixel = y * image.width + x
                val channel = pixel * 3
                output[pixel] = (0xff shl 24) or (byte(image[channel]) shl 16) or
                    (byte(image[channel + 1]) shl 8) or byte(image[channel + 2])
            }
        }
        if (shouldStop()) throw CancellationException("Display conversion cancelled")
        return output
    }

    private fun byte(linear: Float): Int {
        val value = linear.toDouble().coerceIn(0.0, 1.0)
        val encoded = if (value <= 0.0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - 0.055
        return (encoded * 255).roundToInt().coerceIn(0, 255)
    }
}
