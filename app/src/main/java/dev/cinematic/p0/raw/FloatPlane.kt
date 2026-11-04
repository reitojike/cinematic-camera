package dev.cinematic.p0.raw

/** Immutable packed float32 plane in the caller's linear domain. No clipping/transfer. */
internal class FloatPlane(val width: Int, val height: Int, values: FloatArray) {
    private val data: FloatArray

    init {
        require(width > 0 && height > 0 && width.toLong() * height == values.size.toLong()) {
            "Plane dimensions must match nonempty data"
        }
        require(values.all { it.isFinite() }) { "Plane values must be finite" }
        data = values.copyOf()
    }

    internal operator fun get(index: Int): Float = data[index]
    fun copyValues(): FloatArray = data.copyOf()
}
