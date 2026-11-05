package dev.cinematic.p0.photoedit

import dev.cinematic.p0.raw.PhotoAdjustments

/** Initial comparison controls. Warmth is relative, never labelled K. */
internal object PhotoAdjustmentPolicy {
    val scales = PhotoEditScales(.01, PhotoEditScale(-100.0, 100.0, 1.0), PhotoEditScale(-2.0, 2.0, .1))
    val defaults = PhotoEditValues(.5, 0.0, 0.0)

    fun adjustments(values: PhotoEditValues): PhotoAdjustments {
        require(scales.contains(values))
        return PhotoAdjustments(requireNotNull(values.temperature), requireNotNull(values.brightness))
    }
}
