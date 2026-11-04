package dev.cinematic.p0.rawcapture

import android.graphics.ImageDecoder
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** API 28+. Encoded input <=32MiB, validation bitmap <=64x64; no full-resolution pixel allocation. */
internal class AndroidJpegInspector : JpegInspector {
    override fun inspect(openReadOnly: () -> InputStream, shouldStop: () -> Boolean): JpegInspection {
        fun checkContinue() { if (shouldStop()) throw IOException("JPEG inspection stopped") }
        checkContinue()
        val encoded = openReadOnly().use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                checkContinue()
                val count = input.read(chunk)
                if (count < 0) break
                if (count == 0 || count > JpegOutputDescription.MAX_JPEG_BYTES - buffer.size()) throw IOException("Invalid JPEG length")
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }
        checkContinue()
        // Reject missing EOI even when a tolerant codec can recover pixels. This is a completed-output boundary.
        if (encoded.size < 4 || encoded[0] != 0xff.toByte() || encoded[1] != 0xd8.toByte() ||
            encoded[encoded.size - 2] != 0xff.toByte() || encoded.last() != 0xd9.toByte()) throw IOException("Incomplete JPEG")
        val exifOrientation = ByteArrayInputStream(encoded).use {
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
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
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
        val fingerprint = OriginalFingerprint(encoded.size.toLong(), MessageDigest.getInstance("SHA-256").digest(encoded)
            .joinToString("") { "%02x".format(it) })
        checkContinue()
        return JpegInspection(JpegOutputDescription(width, height, 1, "sRGB", fingerprint.bytes), fingerprint)
    }
}
