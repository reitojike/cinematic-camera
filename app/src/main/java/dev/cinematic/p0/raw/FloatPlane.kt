package dev.cinematic.p0.raw

/** Immutable packed float32 plane in the caller's linear domain. No clipping/transfer. */
internal class FloatPlane private constructor(val width: Int, val height: Int, values: FloatArray, owned: Boolean) {
    constructor(width: Int, height: Int, values: FloatArray) : this(width, height, values, false)
    private val data: FloatArray

    init {
        require(width > 0 && height > 0 && width.toLong() * height == values.size.toLong()) {
            "Plane dimensions must match nonempty data"
        }
        require(values.all { it.isFinite() }) { "Plane values must be finite" }
        data = if (owned) values else values.copyOf()
    }

    internal operator fun get(index: Int): Float = data[index]
    fun copyValues(): FloatArray = data.copyOf()

    companion object {
        /** Transfer only a kernel's fresh result. The caller must never mutate values afterwards. */
        internal fun takeOwnership(width: Int, height: Int, values: FloatArray): FloatPlane =
            FloatPlane(width, height, values, true)
    }
}
