package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import dev.cinematic.p0.testing.syntheticCropDng
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Small synthetic private files only. Explicit ordinary-debug opt-in on an emulator. */
@RunWith(AndroidJUnit4::class)
class OddCropGeometryProbeTest {
    @Test fun oddCropPreservesPixelsAndOrientation() = withRoot { root ->
        for (pattern in 0..3) for ((x, y) in listOf(4 to 5, 5 to 4, 5 to 5)) {
            val oracle = decode(root, syntheticCropDng(x, y, pattern = pattern, isolatedCrop = true))
            for (orientation in 1..8) {
                val actual = decode(root, syntheticCropDng(x, y, orientation = orientation, pattern = pattern))
                assertArrayEquals("pattern=$pattern origin=$x,$y orientation=$orientation",
                    rotate(oracle, 48, 32, orientation), actual)
            }
        }
    }

    @Test fun evenCropAndActiveAreaBoundariesPreservePixels() = withRoot { root ->
        for (pattern in 0..3) for (orientation in 1..8) {
            assertArrayEquals(decode(root, syntheticCropDng(4, 4, orientation = orientation, pattern = pattern, isolatedCrop = true)),
                decode(root, syntheticCropDng(4, 4, orientation = orientation, pattern = pattern)))
        }
        for ((x, y, w, h) in listOf(listOf(0, 0, 72, 52), listOf(24, 20, 48, 32),
            listOf(23, 19, 49, 33), listOf(0, 1, 72, 51), listOf(1, 0, 71, 52))) {
            assertArrayEquals(decode(root, syntheticCropDng(x, y, w, h, isolatedCrop = true)),
                decode(root, syntheticCropDng(x, y, w, h)))
        }
    }

    @Test fun invalidGeometryRemainsRejected() = withRoot { root ->
        val invalid = listOf(syntheticCropDng(25, 20), syntheticCropDng(24, 21),
            syntheticCropDng(cropWidth = 0), syntheticCropDng(activeTop = 7), syntheticCropDng(activeLeft = 5))
        for (bytes in invalid) {
            val source = File.createTempFile("invalid-", ".dng", root).apply { writeBytes(bytes) }
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                assertThrows(IOException::class.java) { NativeOriginalRaster.preflight24(fd.fd, CancellationSignal()) }
            }
        }
    }

    @Test fun cancellationKeepsOriginalAndAllowsNextDecode() = withRoot { root ->
        val bytes = syntheticCropDng()
        assertThrows(IOException::class.java) { decode(root, bytes, CancellationSignal().apply { cancel() }) }
        assertArrayEquals(decode(root, syntheticCropDng(isolatedCrop = true)), decode(root, bytes))
    }

    private fun decode(root: File, bytes: ByteArray, signal: CancellationSignal = CancellationSignal()): ByteArray {
        val source = File.createTempFile("source-", ".dng", root).apply { writeBytes(bytes) }
        val output = File.createTempFile("raster-", ".rgb16", root)
        val before = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        var failure: Throwable? = null
        try {
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                val geometry = NativeOriginalRaster.preflight24(input.fd, CancellationSignal())
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE).use { sink ->
                    val report = JSONObject(NativeOriginalRaster.decode24(input.fd, sink.fd, signal))
                    assertEquals(geometry[6], report.getInt("width"))
                    assertEquals(geometry[7], report.getInt("height"))
                    assertEquals(geometry[6].toLong() * geometry[7] * 6, output.length())
                }
            }
            return output.readBytes()
        } catch (t: Throwable) { failure = t; throw t }
        finally {
            try { assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(source.readBytes())) }
            catch (t: Throwable) { if (failure != null) failure.addSuppressed(t) else throw t }
        }
    }

    private fun rotate(input: ByteArray, width: Int, height: Int, orientation: Int): ByteArray {
        val output = ByteArray(input.size)
        val outWidth = if (orientation >= 5) height else width
        for (y in 0 until height) for (x in 0 until width) {
            val (ox, oy) = when (orientation) {
                1 -> x to y; 2 -> width - 1 - x to y; 3 -> width - 1 - x to height - 1 - y
                4 -> x to height - 1 - y; 5 -> y to x; 6 -> height - 1 - y to x
                7 -> height - 1 - y to width - 1 - x; else -> y to width - 1 - x
            }
            input.copyInto(output, (oy * outWidth + ox) * 6, (y * width + x) * 6, (y * width + x + 1) * 6)
        }
        return output
    }

    private fun withRoot(action: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireGpuInputSafetyFixture()
        val root = File(context.cacheDir, "odd-crop-${System.nanoTime()}").apply { check(mkdir()) }
        var failure: Throwable? = null
        try { action(root) } catch (t: Throwable) { failure = t; throw t }
        finally {
            try { check(root.deleteRecursively()) }
            catch (t: Throwable) { if (failure != null) failure.addSuppressed(t) else throw t }
        }
    }
}
