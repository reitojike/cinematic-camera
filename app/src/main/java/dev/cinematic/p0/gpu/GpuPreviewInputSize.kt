package dev.cinematic.p0.gpu

import kotlin.math.floor
import kotlin.math.sqrt

/** Preview-only input admission and dimensions, chosen before the original RGB16 BOX reduction. */
internal object GpuPreviewInputSize {
    const val MAX_LONG_EDGE = 1024
    const val MAX_PIXELS = 1024L * 768

    data class Size(val width: Int, val height: Int)

    /** A single uniform scale; already admitted images keep both dimensions exactly. */
    fun fit(width: Int, height: Int): Size {
        require(width > 0 && height > 0) { "GPU preview dimensions must be positive" }
        val pixels = width.toLong() * height
        if (maxOf(width, height) <= MAX_LONG_EDGE && pixels <= MAX_PIXELS) return Size(width, height)
        val edgeScale = minOf(1.0, MAX_LONG_EDGE.toDouble() / maxOf(width, height))
        // Preserve the old arithmetic for camera ratios, including floating-point floor boundaries.
        // Their edge-limited dimensions already satisfy the pixel limit mathematically.
        val cameraRatio = width.toLong() * 3 == height.toLong() * 4 ||
            width.toLong() * 4 == height.toLong() * 3 ||
            width.toLong() * 9 == height.toLong() * 16 ||
            width.toLong() * 16 == height.toLong() * 9
        val scale = if (cameraRatio) edgeScale else minOf(edgeScale, sqrt(MAX_PIXELS.toDouble() / pixels))
        // A very narrow input still needs a nonempty raster. Original24 keeps its stricter >=4 check.
        return Size(floor(width * scale).toInt().coerceAtLeast(1),
            floor(height * scale).toInt().coerceAtLeast(1)).also {
            checkSupported(it.width, it.height)
        }
    }

    /** Reject at the GPU boundary. Never resize an input that has already been reduced. */
    fun checkSupported(width: Int, height: Int) {
        check(width > 0 && height > 0 && maxOf(width, height) <= MAX_LONG_EDGE &&
            width.toLong() * height <= MAX_PIXELS) {
            "GPU preview input must have a long edge <= $MAX_LONG_EDGE and <= $MAX_PIXELS pixels"
        }
    }
}
