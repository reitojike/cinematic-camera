package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.cinematic.p0.rawcapture.DngContentVerifier

internal data class PhotoRollItem(val uri: String, val name: String)
internal data class PhotoRollListing(val items: List<PhotoRollItem>, val message: String? = null)
internal class PhotoRollSourceFailure(val userMessage: String) : Exception(userMessage)

/** No write methods: selection does not import or mirror a DNG. */
internal class PhotoRollRepository(private val resolver: ContentResolver, private val packageName: String) {
    fun list(signal: CancellationSignal): PhotoRollListing {
        if (Build.VERSION.SDK_INT < 29) return PhotoRollListing(emptyList(), "端末内DNGをFilesから選んでください")
        val items = mutableListOf<PhotoRollItem>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        val where = "${MediaStore.Images.Media.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0 AND " +
            "(${MediaStore.Images.Media.MIME_TYPE} = ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?)"
        resolver.query(collection, columns, where,
            arrayOf(packageName, "image/x-adobe-dng", "%.dng"), "${MediaStore.Images.Media.DATE_ADDED} DESC", signal)?.use { cursor ->
            while (items.size < 200 && cursor.moveToNext()) {
                signal.throwIfCanceled()
                items += PhotoRollItem(ContentUris.withAppendedId(collection, cursor.getLong(0)).toString(),
                    cursor.getString(1) ?: "DNG")
            }
        }
        return PhotoRollListing(items, "このアプリが保存したDNG候補（最新200件まで）。他の端末内DNGはFilesから選べます")
    }

    fun check(uri: Uri, fromFiles: Boolean, shouldStop: () -> Boolean): PhotoRollOriginal {
        if (uri.scheme != "content") throw PhotoRollSourceFailure("読み取り権限のある端末内DNGを選んでください")
        if (fromFiles) {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            if (!isLocalDngDocument(uri.authority, id)) throw PhotoRollSourceFailure("Filesの「内部ストレージ」から選んでください。「最近使った項目」や外部SD・クラウドからの選択はまだ扱えません")
        } else if (uri.authority != MediaStore.AUTHORITY || uri.pathSegments.firstOrNull() != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            throw PhotoRollSourceFailure("原本の参照を確認できません。Filesから選び直してください")
        }
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0)
                size = if (cursor.isNull(1)) null else cursor.getLong(1)
            }
        }
        if (name?.endsWith(".dng", ignoreCase = true) != true && resolver.getType(uri) != "image/x-adobe-dng")
            throw PhotoRollSourceFailure("DNGを選んでください。拡張子だけでは現像対応は確定しません")
        val bytes = size?.takeIf { it in 1..MAX_SOURCE_BYTES }
            ?: throw PhotoRollSourceFailure("原本のサイズが不明、空、または256 MiBを超えています。この入力はまだ扱えません")
        val fingerprint = DngContentVerifier.identify(bytes, { resolver.openInputStream(uri)
            ?: throw PhotoRollSourceFailure("原本を読み取れません。Filesから選び直してください") }, shouldStop)
        return PhotoRollOriginal(uri, fingerprint)
    }

    fun verify(original: PhotoRollOriginal, shouldStop: () -> Boolean) {
        DngContentVerifier.verify(original.fingerprint, { resolver.openInputStream(original.uri)
            ?: throw PhotoRollSourceFailure("原本を読み取れません。Filesから選び直してください") }, shouldStop)
    }

    companion object { const val MAX_SOURCE_BYTES = 256L * 1024 * 1024 }
}
