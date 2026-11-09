package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.DngContentChanged
import dev.cinematic.p0.rawcapture.OriginalGrant

/** Ephemeral provider metadata, never persisted or used as content identity.
 * MediaStore mtime is seconds; DocumentsProvider mtime is milliseconds. Compare only within the
 * same URI/selection origin. Same-size/same-mtime replacement is deliberately not detectable here. */
internal data class PhotoRollSourceObservation(
    val fromFiles: Boolean,
    val bytes: Long,
    val modified: Long?,
    val grant: OriginalGrant,
) {
    init { require(bytes > 0 && (modified == null || modified > 0)) }
    fun requireMatches(current: PhotoRollSourceObservation) {
        if (this != current) throw DngContentChanged()
    }
}
