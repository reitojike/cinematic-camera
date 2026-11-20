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
    @Test fun savedGuideDecodeNeverAllocatesAnOriginalSizeResult() {
        val bytes = jpeg(2048, 1024)
        val snapshot = EncodedJpegSnapshot.read({ bytes.inputStream() }, bytes.size.toLong()) { false }
        val bitmap = requireNotNull(InitialPreviewJpeg.decodeSaved(snapshot, 2048, 1024, CancellationSignal()))
        try {
            assertEquals(1024, bitmap.width)
            assertEquals(512, bitmap.height)
            assertTrue(bitmap.allocationByteCount <= InitialPreviewJpeg.MAX_BITMAP_BYTES)
        } finally { bitmap.recycle() }
    }
    @Test fun cancellationPropagatesAndSavedSnapshotIsInspectedBeforeDecode() {
        val bytes = jpeg()
        val cancelled = CancellationSignal().apply { cancel() }
        val snapshot = EncodedJpegSnapshot.read({ bytes.inputStream() }, bytes.size.toLong()) { false }
        assertTrue(runCatching { InitialPreviewJpeg.decodeSaved(snapshot, 64, 32, cancelled) }.exceptionOrNull() is OperationCanceledException)
        assertNull(InitialPreviewJpeg.decodeSaved(snapshot, 32, 64, CancellationSignal()))
        val bitmap = requireNotNull(InitialPreviewJpeg.decodeSaved(snapshot, 64, 32, CancellationSignal()))
        try { assertEquals(64, bitmap.width); assertEquals(32, bitmap.height) } finally { bitmap.recycle() }
    }
}
