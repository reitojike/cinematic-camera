package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.ImageDecoder
import java.nio.ByteBuffer
import android.os.CancellationSignal
import android.os.Looper
import dev.cinematic.p0.rawcapture.EncodedJpegSnapshot
import dev.cinematic.p0.rawcapture.AndroidJpegInspector
import java.io.IOException
import java.io.InputStream

/** Bounded, software sRGB guide image. No original-size Bitmap and no automatic EXIF rotation.
 * The supplied stream must reopen the SAME immutable, fingerprint-checked encoded snapshot.
 * Call only after the native extraction owner has returned/released its snapshot. */
internal object InitialPreviewJpeg {
    const val MAX_EDGE = 1024
    const val MAX_BITMAP_BYTES = 4L * MAX_EDGE * MAX_EDGE

    fun decodeSaved(snapshot: EncodedJpegSnapshot, width: Int, height: Int,
        signal: CancellationSignal): Bitmap? {
        // Inspector rejects incomplete streams, non-sRGB and non-physical EXIF orientation.
        AndroidJpegInspector().inspectSnapshot(snapshot) { signal.isCanceled }
        return decode(snapshot::openRead, width, height, 1, 8192, 24_000_000, signal)
    }

    fun decodeEmbedded(bytes: ByteArray, width: Int, height: Int, orientation: Int,
        signal: CancellationSignal): Bitmap? {
        require(bytes.size in 4..8 * 1024 * 1024)
        if (bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
            bytes[bytes.lastIndex - 1] != 0xff.toByte() || bytes.last() != 0xd9.toByte()) return null
        // Reject codec-reported partial failures. Recoverable entropy warnings may still succeed;
        // this is bounded temporary display, not a proof of complete JPEG entropy.
        // Validation pixels are discarded; ImageDecoder EXIF rotation is NOT applied to the output.
        check(Looper.myLooper() != Looper.getMainLooper()) { "Guide decode requires an IO owner" }
        signal.throwIfCanceled()
        val validation = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                signal.throwIfCanceled()
                if (info.mimeType != "image/jpeg" || info.colorSpace?.isSrgb != true ||
                    info.size.width !in 1..4096 || info.size.height !in 1..4096 ||
                    info.size.width.toLong() * info.size.height > 4_000_000) throw IOException("Unsupported guide JPEG")
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false }
                val scale = minOf(1.0, 64.0 / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
            }
        } catch (_: IOException) { signal.throwIfCanceled(); return null }
        try { signal.throwIfCanceled() } finally { validation.recycle() }
        // Native owns/copies this array exclusively into the result; caller must not mutate it.
        return decode({ bytes.inputStream() }, width, height, orientation, 4096, 4_000_000, signal)
    }

    private fun decode(open: () -> InputStream, width: Int, height: Int, orientation: Int,
        maxEdge: Int, maxPixels: Long, signal: CancellationSignal): Bitmap? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Guide decode requires an IO owner" }
        signal.throwIfCanceled()
        if (width !in 1..maxEdge || height !in 1..maxEdge || width.toLong() * height > maxPixels ||
            orientation !in 1..8) return null
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
        var oriented: Bitmap? = null
        try {
            decoded = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            signal.throwIfCanceled()
            if (!valid(decoded)) return null
            oriented = if (orientation == 1) decoded else Bitmap.createBitmap(decoded, 0, 0,
                decoded.width, decoded.height, orientationMatrix(orientation), false)
            signal.throwIfCanceled()
            if (!valid(oriented)) return null
            val result = oriented
            if (decoded !== result) decoded.recycle()
            decoded = null
            oriented = null
            return result
        } finally {
            if (oriented !== decoded) oriented?.recycle()
            decoded?.recycle()
        }
    }

    private fun valid(bitmap: Bitmap) = bitmap.width in 1..MAX_EDGE && bitmap.height in 1..MAX_EDGE &&
        bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.colorSpace?.isSrgb == true &&
        bitmap.allocationByteCount <= MAX_BITMAP_BYTES

    internal fun orientationMatrix(orientation: Int) = Matrix().apply {
        // Linear EXIF mappings; createBitmap normalizes the translated destination rectangle.
        val values = when (orientation) {
            1 -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            2 -> floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            3 -> floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            4 -> floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 1f)
            5 -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            6 -> floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            7 -> floatArrayOf(0f, -1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            8 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
            else -> throw IOException("Unknown guide orientation")
        }
        setValues(values)
    }
}
