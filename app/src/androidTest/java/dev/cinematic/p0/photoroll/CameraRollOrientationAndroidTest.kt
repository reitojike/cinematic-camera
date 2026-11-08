package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraRollOrientationAndroidTest {
    @Test fun inspectSyntheticStandardOrientation() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        for (extension in listOf("dng", "jpg")) for (orientation in listOf(1, 6, 8)) {
            val file = File(cache, "orientation-fixture-$orientation.$extension")
            try {
                if (extension == "dng") file.writeBytes(syntheticRollDng(256, 192, orientation))
                else {
                    val source = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)
                    for (y in 0 until source.height) for (x in 0 until source.width) {
                        source.setPixel(x, y, if (x < 128) { if (y < 96) Color.RED else Color.BLUE }
                            else { if (y < 96) Color.GREEN else Color.YELLOW })
                    }
                    file.outputStream().use { check(source.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
                    source.recycle()
                    ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                }
                val before = digest(file)
                runCatching { decodeRollDngThumbnail(CancellationSignal()) { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) } }
                    .onSuccess { println("ORIENTATION type=$extension candidate=$orientation size=${it?.width}x${it?.height}"); it?.recycle() }
                    .onFailure { println("ORIENTATION type=$extension candidate=$orientation error=${it.javaClass.simpleName}:${it.message}") }
                val factory = BitmapFactory.decodeFile(file.path)
                println("ORIENTATION type=$extension bitmapFactory=$orientation size=${factory?.width}x${factory?.height}")
                factory?.recycle()
                val exif = ExifInterface(file)
                println("ORIENTATION type=$extension fixture=$orientation exif=${exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)} thumbnail=${exif.hasThumbnail()}")
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                        println("ORIENTATION type=$extension decoderInfo=$orientation mime=${info.mimeType} size=${info.size}")
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setTargetSampleSize(2)
                    }
                }.onSuccess { println("ORIENTATION type=$extension imageDecoder=$orientation size=${it.width}x${it.height}"); it.recycle() }
                    .onFailure { println("ORIENTATION type=$extension imageDecoder=$orientation error=${it.javaClass.simpleName}:${it.message}") }
                runCatching { ThumbnailUtils.createImageThumbnail(file, Size(256, 256), CancellationSignal()) }
                    .onSuccess { println("ORIENTATION type=$extension thumbnail=$orientation size=${it.width}x${it.height}"); it.recycle() }
                    .onFailure { println("ORIENTATION type=$extension thumbnail=$orientation error=${it.javaClass.simpleName}:${it.message}") }
                assertArrayEquals(before, digest(file))
            } finally { check(file.delete()) }
        }
    }

    @Test fun directDngCandidateMatchesAllExifOrientationsAndLeavesSourceUnchanged() = verifyOrientations(false)
    @Test fun embeddedThumbnailDngHasNoDoubleRotation() = verifyOrientations(true)

    private fun verifyOrientations(embedded: Boolean) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(cache, "orientation-assert-fixture.dng")
        var base: Bitmap? = null
        try {
            file.writeBytes(if (embedded) syntheticRollDngWithThumbnail(1) else syntheticRollDng(256, 192, 1))
            if (embedded) assertTrue("IFD1 thumbnailあり", ExifInterface(file).hasThumbnail())
            base = BitmapFactory.decodeFile(file.path)
            assertNotNull("標準デコーダが合成DNGに対応することが前提", base)
            val original = requireNotNull(base)
            val width = original.width
            val height = original.height
            for (orientation in 0..9) {
                file.writeBytes(if (embedded) syntheticRollDngWithThumbnail(orientation) else syntheticRollDng(256, 192, orientation))
                val before = digest(file)
                val descriptors = mutableListOf<ParcelFileDescriptor>()
                val result = decodeRollDngThumbnail(CancellationSignal()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
                }
                if (orientation !in 2..8) {
                    assertNull("補正不要はproviderを維持", result)
                    assertArrayEquals(before, digest(file))
                    assertTrue(descriptors.all { !it.fileDescriptor.valid() })
                    continue
                }
                assertNotNull("orientation=$orientation", result)
                val image = requireNotNull(result)
                try {
                    val swaps = orientation in 5..8
                    val outWidth = if (swaps) height else width
                    val outHeight = if (swaps) width else height
                    assertEquals(outWidth, image.width)
                    assertEquals(outHeight, image.height)
                    val expected = IntArray(width * height)
                    for (y in 0 until height) for (x in 0 until width) {
                        val (dx, dy) = when (orientation) {
                            2 -> width - 1 - x to y
                            3 -> width - 1 - x to height - 1 - y
                            4 -> x to height - 1 - y
                            5 -> y to x
                            6 -> height - 1 - y to x
                            7 -> height - 1 - y to width - 1 - x
                            8 -> y to width - 1 - x
                            else -> x to y
                        }
                        expected[dy * outWidth + dx] = original.getPixel(x, y)
                    }
                    val actual = IntArray(expected.size)
                    image.getPixels(actual, 0, outWidth, 0, 0, outWidth, outHeight)
                    assertArrayEquals("orientation=$orientation", expected, actual)
                    assertArrayEquals(before, digest(file))
                    assertTrue(descriptors.all { !it.fileDescriptor.valid() })
                } finally { image.recycle() }
            }
        } finally { base?.recycle(); check(file.delete()) }
    }

    @Test fun candidateReleasesDescriptorsWhenCancelledAndRejectsBrokenInput() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "orientation-cancel.dng")
        try {
            file.writeBytes(syntheticRollDng(256, 192, 6))
            val before = digest(file)
            for (cancelAt in 0..4) {
                val signal = CancellationSignal()
                if (cancelAt == 0) signal.cancel()
                var opens = 0
                val descriptors = mutableListOf<ParcelFileDescriptor>()
                try {
                    decodeRollDngThumbnail(signal) {
                        opens++
                        if (opens == cancelAt) signal.cancel()
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
                    }?.recycle()
                    fail("取消が伝播すること")
                } catch (_: android.os.OperationCanceledException) { }
                assertTrue(descriptors.all { !it.fileDescriptor.valid() })
                assertArrayEquals(before, digest(file))
            }
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            assertNull(decodeRollDngThumbnail(CancellationSignal()) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            })
            assertNull(decodeRollDngThumbnail(CancellationSignal()) { null })
        } finally { check(file.delete()) }
    }
    @Test fun rejectsOversizedSourceBeforeReadingMetadata() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "orientation-limit.dng")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            var opens = 0
            assertNull(decodeRollDngThumbnail(CancellationSignal()) {
                opens++
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            })
            assertEquals(1, opens)
        } finally { check(file.delete()) }
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
