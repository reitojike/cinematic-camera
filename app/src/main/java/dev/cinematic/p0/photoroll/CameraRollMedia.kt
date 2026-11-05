package dev.cinematic.p0.photoroll

internal enum class CameraRollKind(val label: String) { PHOTO("写真"), DNG("DNG"), VIDEO("動画") }

/** Main-thread authority: finish requested is already an exit, even before lifecycle ON_PAUSE. */
internal class CameraRollNavigation {
    private var leaving = false
    fun exit() { leaving = true }
    fun canOpenViewer(foreground: Boolean, cancelled: Boolean) = !leaving && foreground && !cancelled
}

internal fun cameraRollKind(name: String, mime: String?, videoCollection: Boolean): CameraRollKind? = when {
    videoCollection -> if (mime?.startsWith("video/") == true) CameraRollKind.VIDEO else null
    name.endsWith(".dng", ignoreCase = true) || mime == "image/x-adobe-dng" -> CameraRollKind.DNG
    mime?.startsWith("image/") == true -> CameraRollKind.PHOTO
    else -> null
}

internal fun cameraRollDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

internal fun newestCameraRollItems(items: List<PhotoRollItem>) = items.sortedWith(
    compareByDescending<PhotoRollItem> { it.addedSeconds }.thenByDescending { it.uri }
).take(200)
