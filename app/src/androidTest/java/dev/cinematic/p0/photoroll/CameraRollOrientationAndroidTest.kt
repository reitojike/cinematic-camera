package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraRollOrientationAndroidTest {
    private val cache get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private fun bitmap(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.BLACK); setPixel(0, 0, Color.RED); setPixel(width - 1, 0, Color.GREEN)
        setPixel(0, height - 1, Color.BLUE); setPixel(width - 1, height - 1, Color.WHITE)
    }
    @Test fun rotatesOnlyUnrotatedLandscapeAndPreservesEveryPixel() {
        val file = File(cache, "orientation-assert-fixture.dng")
        try {
            for (orientation in listOf(6, 8)) {
                file.writeBytes(syntheticRollDng(256, 192, orientation))
                val before = digest(file)
                val source = bitmap(256, 192)
                val expected = IntArray(256 * 192)
                for (y in 0 until 192) for (x in 0 until 256) {
                    val dx = if (orientation == 6) 191 - y else y
                    val dy = if (orientation == 6) x else 255 - x
                    expected[dy * 192 + dx] = source.getPixel(x, y)
                }
                val descriptors = mutableListOf<ParcelFileDescriptor>()
                val result = correctRollDngThumbnail(source, CancellationSignal()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
                }
                try {
                    assertTrue(source.isRecycled)
                    assertEquals(192, result.width); assertEquals(256, result.height)
                    val actual = IntArray(expected.size)
                    result.getPixels(actual, 0, 192, 0, 0, 192, 256)
                    assertArrayEquals(expected, actual)
                    assertArrayEquals(before, digest(file))
                    assertTrue(descriptors.all { !it.fileDescriptor.valid() })
                } finally { result.recycle() }
            }
        } finally { check(file.delete()) }
    }
    @Test fun alreadyRotatedPortraitSourceSquareAndHalfTurnRemainUntouched() {
        val file = File(cache, "orientation-unchanged.dng")
        try {
            // 元寸法、Orientation、provider寸法。向き不明のケースは修正しない。
            val cases = listOf(intArrayOf(256,192,6,192,256), intArrayOf(192,256,6,256,192),
                intArrayOf(192,256,8,192,256), intArrayOf(256,256,6,256,256),
                intArrayOf(256,192,3,256,192), intArrayOf(256,192,7,256,192),
                intArrayOf(256,192,6,256,150), intArrayOf(256,255,6,256,255))
            for (c in cases) {
                file.writeBytes(syntheticRollDng(c[0], c[1], c[2]))
                val before = digest(file)
                val source = bitmap(c[3], c[4])
                val result = correctRollDngThumbnail(source, CancellationSignal()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                try { assertSame(source, result); assertFalse(result.isRecycled); assertArrayEquals(before, digest(file)) }
                finally { result.recycle() }
            }
        } finally { check(file.delete()) }
    }
    @Test fun cancellationReleasesOwnedBitmapAndDescriptors() {
        val file = File(cache, "orientation-cancel.dng")
        try {
            file.writeBytes(syntheticRollDng(256, 192, 6))
            val before = digest(file)
            for (cancelAtOpen in listOf(false, true)) {
                val signal = CancellationSignal()
                if (!cancelAtOpen) signal.cancel()
                val source = bitmap(256, 192)
                val descriptors = mutableListOf<ParcelFileDescriptor>()
                try {
                    correctRollDngThumbnail(source, signal) {
                        signal.cancel()
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
                    }
                    fail("取消が伝播すること")
                } catch (_: android.os.OperationCanceledException) { }
                assertTrue(source.isRecycled)
                assertTrue(descriptors.all { !it.fileDescriptor.valid() })
                assertArrayEquals(before, digest(file))
            }
        } finally { check(file.delete()) }
    }
    @Test fun oversizedBrokenOrUnavailableMetadataKeepsProviderImage() {
        val file = File(cache, "orientation-limits.dng")
        try {
            for (oversized in listOf(true, false)) {
                if (oversized) java.io.RandomAccessFile(file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
                else file.writeBytes(byteArrayOf(1, 2, 3, 4))
                val source = bitmap(256,192)
                val result = correctRollDngThumbnail(source, CancellationSignal()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                try { assertSame(source, result) } finally { result.recycle() }
            }
            val source = bitmap(256,192)
            val result = correctRollDngThumbnail(source, CancellationSignal()) { null }
            try { assertSame(source, result) } finally { result.recycle() }
        } finally { check(file.delete()) }
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
