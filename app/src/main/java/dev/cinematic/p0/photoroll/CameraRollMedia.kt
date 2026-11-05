package dev.cinematic.p0.photoroll

internal enum class CameraRollKind(val label: String) { PHOTO("写真"), DNG("DNG"), RAW("RAW"), VIDEO("動画") }
internal enum class PhotoRollKindFilter { ALL, IMAGES, RAW, VIDEOS }
internal data class PhotoRollPageCursor(val addedSeconds: Long, val rowId: Long, val volume: String,
    val video: Boolean, val dateMissing: Boolean = false)
internal data class PhotoRollQuery(val folderKey: String? = null, val kindFilter: PhotoRollKindFilter = PhotoRollKindFilter.ALL,
    val pageSize: Int = 60, val after: PhotoRollPageCursor? = null, val includeFolders: Boolean = true) {
    init { require(pageSize in 1..200) }
}
internal data class PhotoRollFolder(val key: String, val label: String)
internal data class PhotoRollItem(val uri: String, val name: String,
    val kind: CameraRollKind = CameraRollKind.DNG, val mime: String = "image/x-adobe-dng",
    val addedSeconds: Long = 0, val durationMillis: Long? = null, val folderKey: String? = null,
    val folderLabel: String? = null, val pageCursor: PhotoRollPageCursor? = null)
internal data class PhotoRollListing(val items: List<PhotoRollItem>, val message: String? = null,
    val folders: List<PhotoRollFolder> = emptyList(), val hasMore: Boolean = false,
    val nextCursor: PhotoRollPageCursor? = null)

internal val cameraRollRawExtensions = listOf("dng", "arw", "cr2", "cr3", "nef", "nrw", "orf", "raf", "rw2", "pef", "srw", "raw", "rwl", "3fr", "fff", "iiq", "x3f")
internal val cameraRollRawMimes = listOf("image/x-adobe-dng", "image/x-canon-cr2", "image/x-canon-cr3", "image/x-canon-crw",
    "image/x-nikon-nef", "image/x-nikon-nrw", "image/x-sony-arw", "image/x-sony-sr2", "image/x-sony-srf",
    "image/x-fuji-raf", "image/x-fujifilm-raf", "image/x-olympus-orf", "image/x-panasonic-rw2", "image/x-pentax-pef",
    "image/x-samsung-srw", "image/x-sigma-x3f", "image/x-hasselblad-3fr", "image/x-phaseone-iiq", "image/x-raw")

/** Main-thread authority: finish requested is already an exit, even before lifecycle ON_PAUSE. */
internal class CameraRollNavigation {
    private var leaving = false
    fun exit() { leaving = true }
    fun canOpenViewer(foreground: Boolean, cancelled: Boolean) = !leaving && foreground && !cancelled
}

internal fun cameraRollKind(name: String, mime: String?, videoCollection: Boolean): CameraRollKind? = when {
    videoCollection -> if (mime?.startsWith("video/", true) == true) CameraRollKind.VIDEO else null
    name.endsWith(".dng", ignoreCase = true) || mime.equals("image/x-adobe-dng", true) -> CameraRollKind.DNG
    cameraRollRawExtensions.any { name.endsWith(".$it", true) } || cameraRollRawMimes.any { mime.equals(it, true) } -> CameraRollKind.RAW
    mime?.startsWith("image/", true) == true -> CameraRollKind.PHOTO
    else -> null
}

internal fun cameraRollDuration(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

internal val cameraRollOrder = Comparator<PhotoRollItem> { a, b ->
    val x = a.pageCursor ?: PhotoRollPageCursor(a.addedSeconds, a.uri.substringAfterLast('/').toLongOrNull() ?: 0, a.uri, a.kind == CameraRollKind.VIDEO)
    val y = b.pageCursor ?: PhotoRollPageCursor(b.addedSeconds, b.uri.substringAfterLast('/').toLongOrNull() ?: 0, b.uri, b.kind == CameraRollKind.VIDEO)
    compareValues(x.dateMissing, y.dateMissing).takeIf { it != 0 }
        ?: compareValues(y.addedSeconds, x.addedSeconds).takeIf { it != 0 }
        ?: compareValues(y.rowId, x.rowId).takeIf { it != 0 }
        ?: compareValues(x.volume, y.volume).takeIf { it != 0 }
        ?: compareValues(x.video, y.video)
}

/** Both collection queries use the same strict cursor; no per-collection offset is advanced. */
internal fun cameraRollPage(items: List<PhotoRollItem>, size: Int, folders: List<PhotoRollFolder>) : PhotoRollListing {
    val sorted = items.distinctBy { it.uri }.sortedWith(cameraRollOrder)
    val page = sorted.take(size)
    val more = sorted.size > size
    return PhotoRollListing(page, folders = folders, hasMore = more,
        nextCursor = if (more) requireNotNull(page.last().pageCursor) else null)
}

internal fun cameraRollFolder(volume: String, relativePath: String?) = PhotoRollFolder("$volume|${relativePath ?: ""}",
    "${relativePath?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: "ストレージ直下"} (${if (volume == "external_primary") "内部ストレージ" else volume})")

internal data class CameraRollSql(val selection: String, val arguments: Array<String>)
internal const val CAMERA_ROLL_SQL_ORDER = "date_added DESC, _id DESC, volume_name ASC"

/** SQL values are bound, including the opaque folder path. Kind and page boundaries precede LIMIT. */
internal fun cameraRollSql(query: PhotoRollQuery, video: Boolean, api: Int): CameraRollSql? {
    if (video && query.kindFilter in setOf(PhotoRollKindFilter.IMAGES, PhotoRollKindFilter.RAW) ||
        !video && query.kindFilter == PhotoRollKindFilter.VIDEOS) return null
    val clauses = mutableListOf("is_pending = 0")
    val values = mutableListOf<String>()
    if (api >= 30) clauses += "is_trashed = 0"
    if (video) clauses += "LOWER(mime_type) LIKE 'video/%'"
    else {
        val raw = "(LOWER(COALESCE(mime_type, '')) IN (${cameraRollRawMimes.joinToString { "?" }}) OR " +
            cameraRollRawExtensions.joinToString(" OR ") { "LOWER(COALESCE(_display_name, '')) LIKE ?" } + ")"
        clauses += when (query.kindFilter) {
            PhotoRollKindFilter.RAW -> raw
            PhotoRollKindFilter.IMAGES -> "(LOWER(mime_type) LIKE 'image/%' AND NOT $raw)"
            else -> "(LOWER(mime_type) LIKE 'image/%' OR $raw)"
        }
        values += cameraRollRawMimes
        values += cameraRollRawExtensions.map { "%.$it" }
    }
    query.folderKey?.let { key ->
        val split = key.indexOf('|')
        require(split > 0)
        clauses += "(volume_name = ? AND (relative_path = ? OR (relative_path IS NULL AND ? = '')))"
        values += key.substring(0, split)
        values += key.substring(split + 1)
        values += key.substring(split + 1)
    }
    query.after?.let { after ->
        require(after.rowId > 0 && after.volume.isNotBlank())
        val tie = "(_id < ? OR (_id = ? AND (volume_name > ? OR (volume_name = ? AND ${if (video && !after.video) "1" else "0"}))))"
        if (after.dateMissing) clauses += "(date_added IS NULL AND $tie)"
        else {
            clauses += "(date_added IS NULL OR date_added < ? OR (date_added = ? AND $tie))"
            values += after.addedSeconds.toString(); values += after.addedSeconds.toString()
        }
        values += after.rowId.toString(); values += after.rowId.toString()
        values += after.volume; values += after.volume
    }
    return CameraRollSql(clauses.joinToString(" AND ") { "($it)" }, values.toTypedArray())
}
