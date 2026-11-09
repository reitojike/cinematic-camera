package dev.cinematic.p0.raw

/** Immutable packed float32 plane in the caller's linear domain. No clipping/transfer. */
internal class FloatPlane private constructor(val width: Int, val height: Int, values: FloatArray, owned: Boolean, checkFinite: Boolean = true) {
    constructor(width: Int, height: Int, values: FloatArray) : this(width, height, values, false)
    private val data: FloatArray

    init {
        require(width > 0 && height > 0 && width.toLong() * height == values.size.toLong()) {
            "Plane dimensions must match nonempty data"
        }
        require(!checkFinite || values.all { it.isFinite() }) { "Plane values must be finite" }
        data = if (owned) values else values.copyOf()
    }

    // This plane owns immutable storage. Share only the successful/failed range result,
    // not a mutable array or a caller-supplied assertion that the values are valid.
    private val unitRange by lazy(LazyThreadSafetyMode.PUBLICATION) { data.all { it in 0f..1f } }

    internal fun requireUnitRange() {
        require(unitRange) { "Skin weight must be within 0..1" }
    }

    internal operator fun get(index: Int): Float = data[index]
    fun copyValues(): FloatArray = data.copyOf()

    companion object {
        /** Only copies already finite values; no arithmetic and no raw-array bypass. */
        internal fun fromChannel(image: LinearSrgbImage, channel: Int): FloatPlane {
            require(channel in 0..2) { "RGB channel must be within 0..2" }
            return FloatPlane(image.width, image.height,
                FloatArray(image.pixelCount) { image[it * 3 + channel] }, true, false)
        }

        /** Transfer only a kernel's fresh result. The caller must never mutate values afterwards. */
        internal fun takeOwnership(width: Int, height: Int, values: FloatArray): FloatPlane =
            FloatPlane(width, height, values, true)
    }
}
