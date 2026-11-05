package dev.cinematic.p0.raw

/** Row-major RGB float32, linear sRGB primaries / D65. No implicit clipping or transfer. */
internal class LinearSrgbImage private constructor(val width: Int, val height: Int, pixels: FloatArray, owned: Boolean) {
    constructor(width: Int, height: Int, pixels: FloatArray) : this(width, height, pixels, false)
    private val data: FloatArray

    init {
        require(width > 0 && height > 0 && width.toLong() * height * 3 == pixels.size.toLong()) {
            "RGB dimensions must match a nonempty packed image"
        }
        require(pixels.all { it.isFinite() }) { "RGB values must be finite" }
        data = if (owned) pixels else pixels.copyOf()
    }

    internal val pixelCount: Int get() = data.size / 3
    internal operator fun get(channel: Int): Float = data[channel]
    fun copyPixels(): FloatArray = data.copyOf()
    internal fun scaled(factor: Double): LinearSrgbImage =
        takeOwnership(width, height, FloatArray(data.size) { (data[it] * factor).toFloat() })

    companion object {
        /** Only for a kernel's fresh, exclusively owned result. Never write to pixels after transfer.
         * Use the copying constructor for external arrays or arrays that will be modified later. */
        internal fun takeOwnership(width: Int, height: Int, pixels: FloatArray): LinearSrgbImage =
            LinearSrgbImage(width, height, pixels, true)
    }
}

/** Aligned soft face mask from a separate detector; this class does not find faces. */
internal class FaceMask(val width: Int, val height: Int, weights: FloatArray) {
    private val data: FloatArray

    init {
        require(width > 0 && height > 0 && width.toLong() * height == weights.size.toLong()) {
            "Face dimensions must match a nonempty mask"
        }
        require(weights.all { it.isFinite() && it in 0f..1f }) { "Face weights must be within 0..1" }
        data = weights.copyOf()
    }

    internal operator fun get(pixel: Int): Float = data[pixel]
    fun copyWeights(): FloatArray = data.copyOf()
    internal fun requireAligned(image: LinearSrgbImage) {
        require(width == image.width && height == image.height) { "Face mask must align with RGB" }
    }
}
