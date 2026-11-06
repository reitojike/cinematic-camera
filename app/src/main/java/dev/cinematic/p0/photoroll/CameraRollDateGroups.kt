package dev.cinematic.p0.photoroll

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class CameraRollDateGroup(val label: String, val items: List<PhotoRollItem>)

/** Keep repository order; counts describe only loaded items, not the whole provider. */
internal fun cameraRollDateGroups(items: List<PhotoRollItem>, zone: ZoneId = ZoneId.systemDefault()): List<CameraRollDateGroup> {
    val format = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.US)
    return items.groupBy { item ->
        if (item.addedSeconds <= 0) "日付不明"
        else runCatching { format.format(Instant.ofEpochSecond(item.addedSeconds).atZone(zone)).uppercase(Locale.US) }.getOrDefault("日付不明")
    }.map { (label, values) -> CameraRollDateGroup(label, values) }
}

