package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import dev.cinematic.p0.rawcapture.EncodedJpegSnapshot
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Generated colors only; no Activity, provider, saved original, or GPU owner. */
class InitialPreviewJpegAndroidTest {
    private fun jpeg(width: Int = 64, height: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y,
                if (y < height / 2) { if (x < width / 2) Color.RED else Color.GREEN }
                else { if (x < width / 2) Color.BLUE else Color.WHITE })
            return ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }.toByteArray()
        } finally { bitmap.recycle() }
    }
    private fun assertColor(expected: Int, actual: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) < 12)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) < 12)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) < 12)
    }
    @Test fun allEightOrientationsApplyExactlyOnceToRealJpegPixels() {
        val bytes = jpeg()
        val r = Color.RED; val g = Color.GREEN; val b = Color.BLUE; val w = Color.WHITE
        val corners = listOf(listOf(r,g,b,w), listOf(g,r,w,b), listOf(w,b,g,r), listOf(b,w,r,g),
            listOf(r,b,g,w), listOf(b,r,w,g), listOf(w,g,b,r), listOf(g,w,r,b))
        for (orientation in 1..8) {
            val bitmap = requireNotNull(InitialPreviewJpeg.decodeEmbedded(bytes, 64, 32, orientation, CancellationSignal()))
            try {
                assertEquals(if (orientation >= 5) 32 else 64, bitmap.width)
                assertEquals(if (orientation >= 5) 64 else 32, bitmap.height)
                val actual = listOf(bitmap.getPixel(4,4), bitmap.getPixel(bitmap.width-5,4),
                    bitmap.getPixel(4,bitmap.height-5), bitmap.getPixel(bitmap.width-5,bitmap.height-5))
                corners[orientation-1].zip(actual).forEach { (expected, observed) -> assertColor(expected, observed) }
            } finally { bitmap.recycle() }
        }
    }
    @Test fun sampleDecodeNeverAllocatesAnOriginalSizeResult() {
        val bytes = jpeg(2048, 1024)
        val bitmap = requireNotNull(InitialPreviewJpeg.decodeEmbedded(bytes, 2048, 1024, 6, CancellationSignal()))
        try {
            assertEquals(512, bitmap.width); assertEquals(1024, bitmap.height)
            assertTrue(bitmap.allocationByteCount <= InitialPreviewJpeg.MAX_BITMAP_BYTES)
        } finally { bitmap.recycle() }
    }
    @Test fun structuralErrorsAreRejectedAndRecoverableEntropyStaysBounded() {
        val bytes = jpeg()
        assertNull(InitialPreviewJpeg.decodeEmbedded(bytes, 64, 32, 0, CancellationSignal()))
        assertNull(InitialPreviewJpeg.decodeEmbedded(bytes, 32, 64, 1, CancellationSignal()))
        assertNull(InitialPreviewJpeg.decodeEmbedded(bytes.copyOf(bytes.size-1), 64, 32, 1, CancellationSignal()))
        assertNull(InitialPreviewJpeg.decodeEmbedded(bytes, 4096, 4096, 1, CancellationSignal()))
        // Keep SOI/EOI and all headers, but remove most entropy bytes after the SOS header.
        val scan = (0 until bytes.size - 3).first { bytes[it] == 0xff.toByte() && bytes[it+1] == 0xda.toByte() }
        val headerEnd = scan + 2 + ((bytes[scan+2].toInt() and 255) shl 8) + (bytes[scan+3].toInt() and 255)
        val broken = bytes.copyOf(headerEnd + 2) + byteArrayOf(0xff.toByte(), 0xd9.toByte())
        // Updated owner contract: codec-recoverable entropy is allowed for a temporary guide.
        // This exact fixture failed the former strict-rejection contract (9 pass / 1 fail).
        val recovered = InitialPreviewJpeg.decodeEmbedded(broken, 64, 32, 1, CancellationSignal())
        recovered?.let { try { assertTrue(it.allocationByteCount <= InitialPreviewJpeg.MAX_BITMAP_BYTES) }
            finally { it.recycle() } }
    }
    @Test fun cancellationPropagatesAndSavedSnapshotIsInspectedBeforeDecode() {
        val bytes = jpeg()
        val cancelled = CancellationSignal().apply { cancel() }
        assertTrue(runCatching { InitialPreviewJpeg.decodeEmbedded(bytes, 64, 32, 1, cancelled) }.exceptionOrNull() is OperationCanceledException)
        val snapshot = EncodedJpegSnapshot.read({ bytes.inputStream() }, bytes.size.toLong()) { false }
        val bitmap = requireNotNull(InitialPreviewJpeg.decodeSaved(snapshot, 64, 32, CancellationSignal()))
        try { assertEquals(64, bitmap.width); assertEquals(32, bitmap.height) } finally { bitmap.recycle() }
    }
}
