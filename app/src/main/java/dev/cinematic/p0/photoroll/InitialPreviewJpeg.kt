package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.CancellationSignal
import android.os.Looper
import dev.cinematic.p0.rawcapture.EncodedJpegSnapshot
import dev.cinematic.p0.rawcapture.AndroidJpegInspector
import java.io.InputStream

/** Bounded, software sRGB guide image. No original-size Bitmap and no automatic EXIF rotation.
 * The supplied stream must reopen the same immutable, fingerprint-checked saved JPEG snapshot. */
internal object InitialPreviewJpeg {
    const val MAX_EDGE = 1024
    const val MAX_BITMAP_BYTES = 4L * MAX_EDGE * MAX_EDGE

    fun decodeSaved(snapshot: EncodedJpegSnapshot, width: Int, height: Int,
        signal: CancellationSignal): Bitmap? {
        signal.throwIfCanceled()
        // Inspector rejects incomplete streams, non-sRGB and non-physical EXIF orientation.
        AndroidJpegInspector().inspectSnapshot(snapshot) { signal.isCanceled }
        return decode(snapshot::openRead, width, height, signal)
    }

    private fun decode(open: () -> InputStream, width: Int, height: Int, signal: CancellationSignal): Bitmap? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Guide decode requires an IO owner" }
        signal.throwIfCanceled()
        if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > 24_000_000) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        signal.throwIfCanceled()
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth != width || bounds.outHeight != height ||
            bounds.outColorSpace?.isSrgb != true) return null
        var sample = 1
        while ((width + sample - 1) / sample > MAX_EDGE || (height + sample - 1) / sample > MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            inScaled = false
            inMutable = false
        }
        var decoded: Bitmap? = null
        try {
            decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            signal.throwIfCanceled()
            if (!valid(decoded)) return null
            val result = decoded
            decoded = null
            return result
        } finally {
            decoded?.recycle()
        }
    }

    private fun valid(bitmap: Bitmap) = bitmap.width in 1..MAX_EDGE && bitmap.height in 1..MAX_EDGE &&
        bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb == true &&
        bitmap.allocationByteCount <= MAX_BITMAP_BYTES
}
