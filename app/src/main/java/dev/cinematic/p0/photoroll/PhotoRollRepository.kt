package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.cinematic.p0.rawcapture.DngVerificationStopped
import dev.cinematic.p0.rawcapture.DngIoResources
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.isDngRowUri

internal class PhotoRollSourceFailure(val userMessage: String) : Exception(userMessage)

/** No write methods: selection does not import or mirror a DNG. */
internal class PhotoRollRepository(private val resolver: ContentResolver, private val packageName: String) {
    fun list(signal: CancellationSignal, query: PhotoRollQuery = PhotoRollQuery()): PhotoRollListing {
        if (Build.VERSION.SDK_INT < 29) return PhotoRollListing(emptyList(), "撮影物の一覧はAndroid 10以降で利用できます。端末内DNGはFilesから選べます")
        val items = mutableListOf<PhotoRollItem>()
        for (video in listOf(false, true)) {
            signal.throwIfCanceled()
            val sql = cameraRollSql(query, video, Build.VERSION.SDK_INT) ?: continue
            val collection = collection(video, MediaStore.VOLUME_EXTERNAL)
            val columns = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
            if (video) columns += MediaStore.Video.VideoColumns.DURATION
            val arguments = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sql.selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, sql.arguments)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, CAMERA_ROLL_SQL_ORDER)
                putInt(ContentResolver.QUERY_ARG_LIMIT, query.pageSize + 1)
            }
            val cursor = resolver.query(collection, columns.toTypedArray(), arguments, signal)
                ?: throw PhotoRollSourceFailure("撮影物の一覧を確認できません。戻って再試行してください")
            cursor.use {
                var count = 0
                while (count < query.pageSize + 1 && it.moveToNext()) {
                    signal.throwIfCanceled()
                    count++
                    val name = it.getString(1) ?: if (video) "動画" else "写真"
                    val mime = it.getString(2)
                    val kind = cameraRollKind(name, mime, video) ?: continue
                    val volume = it.getString(4)?.takeIf(::externalVolume) ?: throw PhotoRollSourceFailure("保存先の参照を確認できません")
                    val id = it.getLong(0)
                    if (id <= 0) throw PhotoRollSourceFailure("撮影物の参照を確認できません")
                    val added = if (it.isNull(3)) 0 else it.getLong(3)
                    val folder = cameraRollFolder(volume, it.getString(5))
                    items += PhotoRollItem(ContentUris.withAppendedId(collection(video, volume), id).toString(), name,
                        kind, mime ?: "application/octet-stream", added,
                        if (video && !it.isNull(6) && it.getLong(6) > 0) it.getLong(6) else null,
                        folder.key, folder.label, PhotoRollPageCursor(added, id, volume, video, it.isNull(3)))
                }
            }
        }
        return cameraRollPage(items, query.pageSize, if (query.includeFolders) folders(signal) else emptyList())
    }

    /** Metadata only: API30+ groups in the provider; API29 deduplicates folder columns, never image bytes. */
    private fun folders(signal: CancellationSignal): List<PhotoRollFolder> {
        val result = linkedMapOf<String, PhotoRollFolder>()
        val columns = arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
        for (video in listOf(false, true)) {
            signal.throwIfCanceled()
            val sql = requireNotNull(cameraRollSql(PhotoRollQuery(), video, Build.VERSION.SDK_INT))
            val arguments = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sql.selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, sql.arguments)
                if (Build.VERSION.SDK_INT >= 30) putStringArray(ContentResolver.QUERY_ARG_GROUP_COLUMNS, columns)
            }
            resolver.query(collection(video, MediaStore.VOLUME_EXTERNAL), columns, arguments, signal)?.use { cursor ->
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    val volume = cursor.getString(0)?.takeIf(::externalVolume) ?: continue
                    cameraRollFolder(volume, cursor.getString(1)).let { result[it.key] = it }
                }
            } ?: throw PhotoRollSourceFailure("フォルダ一覧を確認できません。戻って再試行してください")
        }
        return result.values.sortedBy { it.label }
    }

    private fun collection(video: Boolean, volume: String) = if (video) MediaStore.Video.Media.getContentUri(volume)
        else MediaStore.Images.Media.getContentUri(volume)
    private fun externalVolume(volume: String) = volume !in setOf(MediaStore.VOLUME_EXTERNAL, MediaStore.VOLUME_INTERNAL) &&
        volume.matches(Regex("[a-zA-Z0-9_-]+"))

    /** List metadata is never a durable read grant or proof that a row remains published. */
    fun viewIntent(item: PhotoRollItem, signal: CancellationSignal): Intent {
        if (Build.VERSION.SDK_INT < 29 || item.kind == CameraRollKind.DNG)
            throw PhotoRollSourceFailure("DNGは原本を確認して現像画面で開いてください")
        val uri = Uri.parse(item.uri)
        val expectedCollection = if (item.kind == CameraRollKind.VIDEO) "video" else "images"
        if (uri.scheme != "content" || uri.authority != "media" ||
            uri.pathSegments.size != 4 || !externalVolume(uri.pathSegments[0]) ||
            uri.pathSegments[1] != expectedCollection || uri.pathSegments[2] != "media" ||
            uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } != true) throw PhotoRollSourceFailure("撮影物の参照を確認できません")
        val columns = mutableListOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= 30) columns += MediaStore.MediaColumns.IS_TRASHED
        signal.throwIfCanceled()
        val mime = resolver.query(uri, columns.toTypedArray(), null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(1) || cursor.getInt(1) != 0 ||
                cursor.getString(2) != uri.pathSegments[0] ||
                (Build.VERSION.SDK_INT >= 30 && (cursor.isNull(5) || cursor.getInt(5) != 0))) null
            else (cursor.getString(3) ?: "application/octet-stream").takeIf {
                cameraRollKind(cursor.getString(4) ?: "", it, item.kind == CameraRollKind.VIDEO) == item.kind
            }
        } ?: throw PhotoRollSourceFailure("写真・動画が見つからないか、アクセスできません。一覧に戻って再試行してください")
        signal.throwIfCanceled()
        resolver.openAssetFileDescriptor(uri, "r", signal)?.use { signal.throwIfCanceled() }
            ?: throw PhotoRollSourceFailure("写真・動画を読み取れません。一覧に戻って再試行してください")
        signal.throwIfCanceled()
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).apply {
            clipData = ClipData.newRawUri("撮影物", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun check(uri: Uri, fromFiles: Boolean, shouldStop: () -> Boolean, maxBytes: Long = MAX_SOURCE_BYTES,
        readers: PhotoRollReadResources? = null): PhotoRollOriginal {
        val observed = observe(uri, fromFiles, shouldStop, maxBytes, readers)
        val fingerprint = DngContentVerifier.identify(observed.bytes,
            { openRead(uri, readers = readers) }, shouldStop)
        observed.requireMatches(observe(uri, fromFiles, shouldStop, maxBytes, readers))
        return PhotoRollOriginal(uri, fingerprint, observed.grant, observed)
    }

    /** Preview-only hint, never a replacement fingerprint or an authorization to publish. */
    fun verifyPreview(original: PhotoRollOriginal, shouldStop: () -> Boolean,
        io: DngIoResources? = null, readers: PhotoRollReadResources? = null) {
        val expected = original.previewObservation
        if (expected == null) {
            DngContentVerifier.verify(original.fingerprint, { openRead(original.uri, io, readers) }, shouldStop)
            return
        }
        expected.requireMatches(observe(original.uri, expected.fromFiles, shouldStop,
            MAX_SOURCE_BYTES, readers, io))
        // A successful metadata query alone does not establish that content read permission remains.
        // Open read-only and close without consuming bytes. Failed closes stay with the caller's owner.
        if (expected.modified == null) {
            // Providers may legitimately omit mtime. Keep the conservative compatibility path.
            DngContentVerifier.verify(original.fingerprint, { openRead(original.uri, io, readers) }, shouldStop)
        } else openRead(original.uri, io, readers).use { checkpoint(shouldStop) }
        checkpoint(shouldStop)
    }

    private fun openRead(uri: Uri, io: DngIoResources? = null, readers: PhotoRollReadResources? = null): java.io.InputStream {
        val open = { resolver.openInputStream(uri)
            ?: throw PhotoRollSourceFailure("原本を読み取れません。Filesから選び直してください") }
        return if (readers != null) readers.open(open) else if (io != null) io.input(open()) else open()
    }

    private fun checkpoint(shouldStop: () -> Boolean) { if (shouldStop()) throw DngVerificationStopped() }

    private fun observe(uri: Uri, fromFiles: Boolean, shouldStop: () -> Boolean, maxBytes: Long,
        readers: PhotoRollReadResources? = null, io: DngIoResources? = null): PhotoRollSourceObservation {
        checkpoint(shouldStop)
        require(maxBytes in 1..MAX_SOURCE_BYTES)
        if (uri.scheme != "content") throw PhotoRollSourceFailure("読み取り権限のある端末内DNGを選んでください")
        if (fromFiles) {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (!isLocalDngDocument(uri.authority, id)) throw PhotoRollSourceFailure("Filesの「内部ストレージ」から選んでください。「最近使った項目」や外部SD・クラウドからの選択はまだ扱えません")
        } else {
            if (uri.authority == "media" && uri.pathSegments.size == 4 && externalVolume(uri.pathSegments[0]) &&
                uri.pathSegments[0] != MediaStore.VOLUME_EXTERNAL_PRIMARY)
                throw PhotoRollSourceFailure("外部SD等のDNGは直接現像できません。現在は内部ストレージの原本に対応しています")
            if (Build.VERSION.SDK_INT < 29 || !isDngRowUri(uri.toString()))
                throw PhotoRollSourceFailure("原本の参照を確認できません。Filesから選び直してください")
        }
        var name: String? = null
        var size: Long? = null
        var modified: Long? = null
        var appOwnedVerified = false
        var publishedVerified = false
        var rowMime: String? = null
        val columns = mutableListOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        if (!fromFiles) columns += listOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.VOLUME_NAME)
        val trashIndex = if (!fromFiles && Build.VERSION.SDK_INT >= 30) columns.size.also {
            columns += MediaStore.MediaColumns.IS_TRASHED
        } else null
        val mimeIndex = if (!fromFiles) columns.size.also { columns += MediaStore.MediaColumns.MIME_TYPE } else null
        val modifiedColumn = if (fromFiles) DocumentsContract.Document.COLUMN_LAST_MODIFIED else MediaStore.MediaColumns.DATE_MODIFIED
        columns += modifiedColumn
        fun inspect(cursor: android.database.Cursor) {
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                size = if (cursor.isNull(1)) null else cursor.getLong(1)
                val modifiedIndex = cursor.getColumnIndex(modifiedColumn)
                modified = if (modifiedIndex < 0 || cursor.isNull(modifiedIndex)) null else cursor.getLong(modifiedIndex).takeIf { it > 0 }
                if (!fromFiles) {
                    appOwnedVerified = cursor.getString(2) == packageName
                    publishedVerified = !cursor.isNull(3) && cursor.getInt(3) == 0 &&
                        cursor.getString(4) == MediaStore.VOLUME_EXTERNAL_PRIMARY &&
                        (trashIndex == null || (!cursor.isNull(trashIndex) && cursor.getInt(trashIndex) == 0))
                    rowMime = mimeIndex?.let(cursor::getString)
                }
            }
        }
        resolver.query(uri, columns.toTypedArray(), null, null, null)?.let { cursor ->
            when {
                readers != null -> readers.useOwned(cursor, ::inspect)
                io != null -> io.useOwned(cursor, ::inspect)
                else -> cursor.use(::inspect)
            }
        }
        if (!fromFiles && !publishedVerified) throw PhotoRollSourceFailure("公開済みの原本と確認できないか、アクセス権がありません。一覧から選び直してください")
        if (cameraRollKind(name ?: "", if (fromFiles) resolver.getType(uri) else rowMime, false) != CameraRollKind.DNG)
            throw PhotoRollSourceFailure("DNGを選んでください。拡張子だけでは現像対応は確定しません")
        val bytes = size?.takeIf { it in 1..maxBytes }
            ?: throw PhotoRollSourceFailure("原本のサイズが不明、空、または${maxBytes / (1024 * 1024)} MiBを超えています。この入力はまだ扱えません")
        checkpoint(shouldStop)
        return PhotoRollSourceObservation(fromFiles, bytes, modified,
            if (!fromFiles && appOwnedVerified) OriginalGrant.APP_OWNED else OriginalGrant.USER_SELECTED)
    }

    fun verify(original: PhotoRollOriginal, shouldStop: () -> Boolean) {
        DngContentVerifier.verify(original.fingerprint, { resolver.openInputStream(original.uri)
            ?: throw PhotoRollSourceFailure("原本を読み取れません。Filesから選び直してください") }, shouldStop)
    }

    companion object { const val MAX_SOURCE_BYTES = 256L * 1024 * 1024 }
}
