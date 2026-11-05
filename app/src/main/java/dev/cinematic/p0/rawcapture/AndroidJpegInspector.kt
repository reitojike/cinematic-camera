package dev.cinematic.p0.rawcapture

import android.graphics.ImageDecoder
import android.media.ExifInterface
import java.io.IOException
import java.io.InputStream

/** API 28+. Encoded input <=32MiB, validation bitmap <=64x64; no full-resolution pixel allocation. */
internal class AndroidJpegInspector : JpegInspector {
    override fun inspect(openReadOnly: () -> InputStream, shouldStop: () -> Boolean): JpegInspection =
        inspectSnapshot(EncodedJpegSnapshot.read(openReadOnly, shouldStop = shouldStop), shouldStop)

    override fun inspect(openReadOnly: () -> InputStream, expectedBytes: Long, shouldStop: () -> Boolean): JpegInspection =
        inspectSnapshot(EncodedJpegSnapshot.read(openReadOnly, expectedBytes, shouldStop), shouldStop)

    fun inspectSnapshot(encoded: EncodedJpegSnapshot, shouldStop: () -> Boolean): JpegInspection {
        fun checkContinue() { if (shouldStop()) throw IOException("JPEG inspection stopped") }
        checkContinue()
        // Reject missing EOI even when a tolerant codec can recover pixels. This is a completed-output boundary.
        if (!encoded.hasCompleteMarkers()) throw IOException("Incomplete JPEG")
        val exifOrientation = encoded.openRead().use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, -1)
        }
        // Framework ExifInterface inserts UNDEFINED (0) when the JPEG has no orientation tag.
        // Both unspecified and NORMAL need no viewer transform; all rotation/flip values are refused.
        if (exifOrientation !in setOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)) {
            throw IOException("JPEG pixels are not physically oriented")
        }
        checkContinue()
        var width = 0
        var height = 0
        val bitmap = ImageDecoder.decodeBitmap(encoded.source()) { decoder, info, _ ->
            checkContinue()
            if (info.mimeType != "image/jpeg" || info.colorSpace?.isSrgb != true) throw IOException("Unsupported JPEG color definition")
            width = info.size.width
            height = info.size.height
            if (width !in 1..32768 || height !in 1..32768 || width.toLong() * height > 100_000_000) throw IOException("Unsupported JPEG dimensions")
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setOnPartialImageListener { false }
            val scale = minOf(1.0, 64.0 / maxOf(width, height))
            decoder.setTargetSize(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
        }
        try { checkContinue() } finally { bitmap.recycle() }
        val fingerprint = encoded.fingerprint
        checkContinue()
        return JpegInspection(JpegOutputDescription(width, height, 1, "sRGB", fingerprint.bytes), fingerprint)
    }
}
