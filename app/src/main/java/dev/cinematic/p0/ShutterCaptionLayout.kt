package dev.cinematic.p0

data class ShutterCaptionPlacement(val x: Int, val y: Int, val width: Int, val height: Int,
    val childX: Int, val childY: Int)

/** The measured caption overlays the fixed rail. Quarter-turn size is part of layout, not guessed. */
fun shutterCaptionPlacement(width: Int, height: Int, textWidth: Int, textHeight: Int,
    horizontal: Boolean, left: Boolean): ShutterCaptionPlacement {
    require(width >= 0 && height >= 0 && textWidth >= 0 && textHeight >= 0)
    val rotatedWidth = if (horizontal) textHeight else textWidth
    val rotatedHeight = if (horizontal) textWidth else textHeight
    require(rotatedWidth <= width && rotatedHeight <= height)
    val x = if (horizontal) { if (left) 0 else width - rotatedWidth } else (width - rotatedWidth) / 2
    val y = if (horizontal) (height - rotatedHeight) / 2 else height - rotatedHeight
    return ShutterCaptionPlacement(x, y, rotatedWidth, rotatedHeight,
        x + (rotatedWidth - textWidth) / 2, y + (rotatedHeight - textHeight) / 2)
}
