package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor

/** 元画像の画素を縮小してから向きを一度だけ適用する。providerの補正済みBitmapには使わない。 */
internal fun decodeRollDngThumbnail(
    signal: CancellationSignal,
    openRead: () -> ParcelFileDescriptor?,
): Bitmap? {
    signal.throwIfCanceled()
    val fileSize = openRead()?.use { it.statSize } ?: return null
    if (fileSize !in 1..(64L * 1024 * 1024)) return null
    signal.throwIfCanceled()
    val orientation = openRead()?.use {
        ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: return null
    signal.throwIfCanceled()
    // 補正不要のDNGはproviderのキャッシュをそのまま使う。
    if (orientation !in 2..8) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openRead()?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds); Unit } ?: return null
    val sample = rollDngThumbnailSample(bounds.outWidth, bounds.outHeight, bounds.outMimeType) ?: return null
    signal.throwIfCanceled()
    var owned: Bitmap? = openRead()?.use {
        BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } ?: return null
    try {
        signal.throwIfCanceled()
        val decoded = requireNotNull(owned)
        val edge = maxOf(decoded.width, decoded.height)
        if (edge > 256) {
            val smaller = Bitmap.createScaledBitmap(decoded, maxOf(1L, decoded.width.toLong() * 256 / edge).toInt(),
                maxOf(1L, decoded.height.toLong() * 256 / edge).toInt(), true)
            if (smaller !== decoded) decoded.recycle()
            owned = smaller
        }
        val source = requireNotNull(owned)
        val oriented = orientRollThumbnail(source, orientation)
        if (oriented !== source) source.recycle()
        owned = oriented
        signal.throwIfCanceled()
        return oriented.also { owned = null }
    } finally { owned?.recycle() }
}

/** BitmapFactoryはEXIFの向きを画素へ適用しないため、原本から読んだ画素にだけ使用する。 */
internal fun orientRollThumbnail(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return source
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
}





/** RAWの内部作業領域を無制限に増やさない。JPEG previewはcodecが選んだ寸法を用いる。 */
internal fun rollDngThumbnailSample(width: Int, height: Int, mime: String?): Int? {
    if (width <= 0 || height <= 0) return null
    if (mime != "image/jpeg" && width.toLong() * height > 16_000_000L) return null
    var sample = 1
    while (maxOf(width, height) / sample > 256) sample *= 2
    return sample
}
