package dev.cinematic.p0.photoroll

import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.rawcapture.SharedOriginalReference

/** A checked selection, not authority to save, publish, or change the exact input. */
internal data class InitialPreviewIdentity(
    val source: SharedOriginalReference,
    val geometry: Original24PreflightGeometry,
    val canonicalRecipe: String,
) {
    init { require(canonicalRecipe.isNotBlank()) }
}

internal enum class InitialPreviewKind {
    SAVED_CACHE, SAVED_JPEG, HALF_GUIDE;
    val edited get() = this == SAVED_CACHE || this == SAVED_JPEG
}

/** Priority is independent of availability. Missing/unsafe candidates are skipped, not invented. */
internal object InitialPreviewPolicy {
    val priority = InitialPreviewKind.entries.toList()
    fun eligible(request: InitialPreviewIdentity, candidate: InitialPreviewIdentity,
        kind: InitialPreviewKind, recipeVerified: Boolean, orientationVerified: Boolean): Boolean =
        request.source == candidate.source && request.geometry == candidate.geometry &&
            orientationVerified && (!kind.edited ||
                (recipeVerified && request.canonicalRecipe == candidate.canonicalRecipe))
}

/** UI-thread gate. Exact wins permanently, including over a late result for the same selection.
 * This owns identity only; discarded bitmaps remain the caller's responsibility to close. */
internal class InitialPreviewGate {
    data class Ticket internal constructor(val owner: Any, val epoch: Long, val identity: InitialPreviewIdentity)
    private val owner = Any()
    private var epoch = 0L
    private var ticket: Ticket? = null
    private var exact = false
    private var best: InitialPreviewKind? = null
    fun begin(identity: InitialPreviewIdentity): Ticket {
        invalidate()
        return Ticket(owner, epoch, identity).also { ticket = it }
    }
    fun accept(candidate: Ticket, kind: InitialPreviewKind): Boolean {
        if (candidate != ticket || candidate.owner !== owner || exact ||
            best?.let { it.ordinal <= kind.ordinal } == true) return false
        best = kind
        return true
    }
    fun exact(candidate: Ticket) { if (candidate == ticket && candidate.owner === owner) exact = true }
    fun invalidate() {
        check(epoch < Long.MAX_VALUE)
        epoch++
        ticket = null
        exact = false
        best = null
    }
}
