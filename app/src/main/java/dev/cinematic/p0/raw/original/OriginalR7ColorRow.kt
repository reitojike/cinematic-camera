package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7Base

/** Existing kernels and Float boundaries; only the two intermediate file round-trips disappear. */
internal fun originalR7ColorRow(width: Int, ownedRow: FloatArray, stop: () -> Boolean): FloatArray {
    originalCheckpoint(stop)
    var current = R7Base.vibrance(LinearSrgbImage.takeOwnership(width, 1, ownedRow))
    originalCheckpoint(stop)
    current = R7Base.crisp(current)
    originalCheckpoint(stop)
    current = R7Base.skin(current)
    originalCheckpoint(stop)
    return current.copyPixels()
}
