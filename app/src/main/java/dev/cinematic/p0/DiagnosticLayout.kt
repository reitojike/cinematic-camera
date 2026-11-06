package dev.cinematic.p0

/** Conservative room for fixed sections plus a readable body; wrapped text uses full scrolling. */
internal fun diagnosticNeedsFullScroll(widthDp: Float, heightDp: Float, fontScale: Float): Boolean {
    val scale = fontScale.coerceAtLeast(1f)
    return heightDp < 520f * scale || widthDp < 360f * scale
}
