package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface

internal data class RollDngOrientation(val orientation: Int, val width: Int, val height: Int)

/** RAWは展開せず、既存provider画像へ追加回転してよい限定条件だけを読む。 */
internal fun readRollDngOrientation(signal: CancellationSignal, openRead: () -> ParcelFileDescriptor?): RollDngOrientation? {
    signal.throwIfCanceled()
    return openRead()?.use {
        if (it.statSize !in 1..(64L * 1024 * 1024)) return@use null
        signal.throwIfCanceled()
        val exif = ExifInterface(it.fileDescriptor)
        signal.throwIfCanceled()
        RollDngOrientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0),
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
            exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0))
    }
}

/** 元も返却像も横長の90/270度だけ。回転済み・元縦長・正方形・crop不一致は推測しない。 */
internal fun rollThumbnailRotation(meta: RollDngOrientation?, width: Int, height: Int): Int {
    if (meta == null || meta.orientation !in listOf(6, 8) ||
        meta.width <= meta.height || meta.height <= 0 || width <= height || height <= 0) return 0
    // 縮小時の端数2pxまで。明確な縦横比変更やcropは補正対象外。
    val expectedHeight = width.toDouble() * meta.height / meta.width
    if (width - height <= 4 || width - expectedHeight <= 4 ||
        kotlin.math.abs(height - expectedHeight) > 2.0) return 0
    return if (meta.orientation == 6) 90 else 270
}

/** sourceの所有権を受け取り、成功時だけ返す。追加decodeなし、追加Bitmapは縮小像1枚のみ。 */
internal fun correctRollDngThumbnail(source: Bitmap, signal: CancellationSignal,
    openRead: () -> ParcelFileDescriptor?): Bitmap {
    var owned: Bitmap? = source
    try {
        val meta = try { readRollDngOrientation(signal, openRead) }
        catch (_: java.io.IOException) { signal.throwIfCanceled(); null }
        catch (_: SecurityException) { signal.throwIfCanceled(); null }
        val rotation = rollThumbnailRotation(meta, source.width, source.height)
        signal.throwIfCanceled()
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height,
                Matrix().apply { setRotate(rotation.toFloat()) }, false)
            if (rotated !== source) source.recycle()
            owned = rotated
        }
        signal.throwIfCanceled()
        return requireNotNull(owned).also { owned = null }
    } finally { owned?.recycle() }
}
