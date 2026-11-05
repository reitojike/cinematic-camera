package dev.cinematic.p0.raw.original

/** Count only mask observations; the former RGB pass did not use any of its raster samples.
 * Keep every callback and row validation, including NONE and all rows after reaching 50 pixels. */
internal fun countOriginalMaskPixels(width: Int, height: Int, faces: OriginalMask, stop: () -> Boolean): Int {
    validateOriginalSize(width, height)
    val row = FloatArray(width)
    var count = 0 // At most 24,000,000 admitted pixels, safely below Int.MAX_VALUE.
    repeat(height) { y ->
        originalCheckpoint(stop)
        faces.row(y, row)
        require(row.all { it.isFinite() && it in 0f..1f })
        row.forEach { if (it > .5f) count++ }
    }
    return count
}
