package dev.cinematic.p0.photoroll

/** Keeping pixels is presentation-only; it does not retain save ownership or accept a result. */
internal fun retainDevelopPreview(hasPreview: Boolean, source: String?, previousSource: String?,
    foreground: Boolean): Boolean =
    hasPreview && source != null && source == previousSource && foreground
