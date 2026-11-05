package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.isDngRowUri

internal data class PhotoRollItem(val uri: String, val name: String,
    val kind: CameraRollKind = CameraRollKind.DNG, val mime: String = "image/x-adobe-dng",
    val addedSeconds: Long = 0, val durationMillis: Long? = null)
internal data class PhotoRollListing(val items: List<PhotoRollItem>, val message: String? = null)
internal class PhotoRollSourceFailure(val userMessage: String) : Exception(userMessage)

/** No write methods: selection does not import or mirror a DNG. */
internal class PhotoRollRepository(private val resolver: ContentResolver, private val packageName: String) {
    fun list(signal: CancellationSignal): PhotoRollListing {
        if (Build.VERSION.SDK_INT < 29) return PhotoRollListing(emptyList(), "撮影物の一覧はAndroid 10以降で利用できます。端末内DNGはFilesから選べます")
        val items = mutableListOf<PhotoRollItem>()
        for (video in listOf(false, true)) {
            signal.throwIfCanceled()
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val columns = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED)
            if (video) columns += MediaStore.Video.VideoColumns.DURATION
            val where = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0" +
                if (Build.VERSION.SDK_INT >= 30) " AND ${MediaStore.MediaColumns.IS_TRASHED} = 0" else ""
            val cursor = resolver.query(collection, columns.toTypedArray(), where, arrayOf(packageName),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC", signal)
                ?: throw PhotoRollSourceFailure("撮影物の一覧を確認できません。戻って再試行してください")
            cursor.use {
                var count = 0
                while (count < 200 && it.moveToNext()) {
                    signal.throwIfCanceled()
                    count++
                    val name = it.getString(1) ?: if (video) "動画" else "写真"
                    val mime = it.getString(2)
                    val kind = cameraRollKind(name, mime, video) ?: continue
                    items += PhotoRollItem(ContentUris.withAppendedId(collection, it.getLong(0)).toString(), name,
                        kind, mime ?: "image/x-adobe-dng", if (it.isNull(3)) 0 else it.getLong(3),
                        if (video && !it.isNull(4) && it.getLong(4) > 0) it.getLong(4) else null)
                }
            }
        }
        return PhotoRollListing(newestCameraRollItems(items), "このアプリが保存した写真・動画・DNG（最新200件まで）")
    }

    /** List metadata is never a durable read grant or proof that a row remains published. */
    fun viewIntent(item: PhotoRollItem, signal: CancellationSignal): Intent {
        if (Build.VERSION.SDK_INT < 29 || item.kind == CameraRollKind.DNG)
            throw PhotoRollSourceFailure("DNGは原本を確認して現像画面で開いてください")
        val uri = Uri.parse(item.uri)
        val expectedCollection = if (item.kind == CameraRollKind.VIDEO) "video" else "images"
        if (uri.scheme != "content" || uri.authority != "media" ||
            uri.pathSegments.size != 4 || uri.pathSegments[0] != MediaStore.VOLUME_EXTERNAL_PRIMARY ||
            uri.pathSegments[1] != expectedCollection || uri.pathSegments[2] != "media" ||
            uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } != true) throw PhotoRollSourceFailure("撮影物の参照を確認できません")
        val columns = mutableListOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= 30) columns += MediaStore.MediaColumns.IS_TRASHED
        signal.throwIfCanceled()
        val mime = resolver.query(uri, columns.toTypedArray(), null, null, null, signal)?.use { cursor ->
            if (!cursor.moveToFirst() || cursor.getString(0) != packageName || cursor.isNull(1) || cursor.getInt(1) != 0 ||
                cursor.getString(2) != MediaStore.VOLUME_EXTERNAL_PRIMARY ||
                (Build.VERSION.SDK_INT >= 30 && (cursor.isNull(5) || cursor.getInt(5) != 0))) null
            else cursor.getString(3)?.takeIf {
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
        require(maxBytes in 1..MAX_SOURCE_BYTES)
        if (uri.scheme != "content") throw PhotoRollSourceFailure("読み取り権限のある端末内DNGを選んでください")
        if (fromFiles) {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (!isLocalDngDocument(uri.authority, id)) throw PhotoRollSourceFailure("Filesの「内部ストレージ」から選んでください。「最近使った項目」や外部SD・クラウドからの選択はまだ扱えません")
        } else if (Build.VERSION.SDK_INT < 29 || !isDngRowUri(uri.toString()) ||
            uri.pathSegments.firstOrNull() != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            throw PhotoRollSourceFailure("原本の参照を確認できません。Filesから選び直してください")
        }
        var name: String? = null
        var size: Long? = null
        var appOwnedVerified = false
        val columns = mutableListOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        if (!fromFiles) columns += listOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.VOLUME_NAME)
        val trashIndex = if (!fromFiles && Build.VERSION.SDK_INT >= 30) columns.size.also {
            columns += MediaStore.MediaColumns.IS_TRASHED
        } else null
        fun inspect(cursor: android.database.Cursor) {
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                size = if (cursor.isNull(1)) null else cursor.getLong(1)
                if (!fromFiles) {
                    appOwnedVerified = cursor.getString(2) == packageName && !cursor.isNull(3) && cursor.getInt(3) == 0 &&
                        cursor.getString(4) == MediaStore.VOLUME_EXTERNAL_PRIMARY &&
                        (trashIndex == null || (!cursor.isNull(trashIndex) && cursor.getInt(trashIndex) == 0))
                }
            }
        }
        resolver.query(uri, columns.toTypedArray(), null, null, null)?.let { cursor ->
            if (readers == null) cursor.use(::inspect) else readers.useOwned(cursor, ::inspect)
        }
        if (!fromFiles && !appOwnedVerified) throw PhotoRollSourceFailure("このアプリの公開済み原本と確認できません。Filesから選び直してください")
        if (name?.endsWith(".dng", ignoreCase = true) != true && resolver.getType(uri) != "image/x-adobe-dng")
            throw PhotoRollSourceFailure("DNGを選んでください。拡張子だけでは現像対応は確定しません")
        val bytes = size?.takeIf { it in 1..maxBytes }
            ?: throw PhotoRollSourceFailure("原本のサイズが不明、空、または${maxBytes / (1024 * 1024)} MiBを超えています。この入力はまだ扱えません")
        val openRead = { resolver.openInputStream(uri)
            ?: throw PhotoRollSourceFailure("原本を読み取れません。Filesから選び直してください") }
        val fingerprint = DngContentVerifier.identify(bytes, { readers?.open(openRead) ?: openRead() }, shouldStop)
        return PhotoRollOriginal(uri, fingerprint, if (fromFiles) OriginalGrant.USER_SELECTED else OriginalGrant.APP_OWNED)
    }

    fun verify(original: PhotoRollOriginal, shouldStop: () -> Boolean) {
        DngContentVerifier.verify(original.fingerprint, { resolver.openInputStream(original.uri)
            ?: throw PhotoRollSourceFailure("原本を読み取れません。Filesから選び直してください") }, shouldStop)
    }

    companion object { const val MAX_SOURCE_BYTES = 256L * 1024 * 1024 }
}
