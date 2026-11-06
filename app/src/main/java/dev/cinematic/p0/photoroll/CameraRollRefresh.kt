package dev.cinematic.p0.photoroll

/** Retain only tiles from the same authorized collection while refreshing its first page. */
internal fun canKeepCameraRollTiles(previous: PhotoRollQuery?, previousAccessEpoch: Int,
    next: PhotoRollQuery, accessEpoch: Int): Boolean = previous != null && previousAccessEpoch == accessEpoch &&
    previous.copy(after = null, includeFolders = true) == next.copy(after = null, includeFolders = true)
