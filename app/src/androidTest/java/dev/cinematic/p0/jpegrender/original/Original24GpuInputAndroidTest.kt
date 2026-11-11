package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.GpuPreviewInputSize
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import dev.cinematic.p0.raw.original.OriginalFields
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import org.json.JSONObject

/** Reuses the existing native input path. No EGL or user photos, no save/publication. */
@RunWith(AndroidJUnit4::class)
class Original24GpuInputAndroidTest {
    @Test fun inputOnlyEntryMatchesCpuSessionAsShotWithoutPreparingR7() = fixture { input, root, source, geometry ->
        fun session(name: String) = Original24PreviewSession(source, File(root, name), {}, geometry) { input.inputStream() }
        val diagnostic = session("gpu-input")
        val direct = try {
            diagnostic.loadGpuAsShotInput().also {
                assertNull(diagnostic.previewExternalEv)
                assertNull(diagnostic.previewDecisions)
                assertEquals(0, diagnostic.previewFieldBuilds)
                assertFalse(diagnostic.hasUnreleasedResources)
            }
        } finally { diagnostic.close() }
        var cpuInput: LinearSrgbImage? = null
        session("cpu-preview").use { cpu ->
            cpu.render(.5).recycle()
            cpu.comparisonBitmap(createBitmap = { image, _ ->
                cpuInput = image
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            })?.recycle()
            assertFalse(cpu.hasUnreleasedResources)
        }
        val expected = requireNotNull(cpuInput)
        assertEquals(expected.width, direct.width)
        assertEquals(expected.height, direct.height)
        repeat(expected.pixelCount * 3) { i -> assertEquals("channel $i", expected[i].toRawBits(), direct[i].toRawBits()) }
        DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
    }

    @Test fun cancelledInputEntryIsTerminalAndRetainsNoTransientFiles() = fixture { input, root, source, geometry ->
        Original24PreviewSession(source, File(root, "cancelled"), {}, geometry) { input.inputStream() }.use { session ->
            assertThrows(java.util.concurrent.CancellationException::class.java) { session.loadGpuAsShotInput { true } }
            assertFalse(session.hasUnreleasedResources)
            assertThrows(IllegalStateException::class.java) { session.loadGpuAsShotInput() }
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        }
    }

    @Test fun gpuInputReducesOriginalOnceToPixelLimitedDimensions() {
        for ((width, height, expectedWidth, expectedHeight) in listOf(
            listOf(1152, 1152, 886, 886), listOf(1024, 769, 1023, 768))) {
            fixture(width, height) { input, root, source, geometry ->
                assertEquals(width, geometry.outputWidth)
                assertEquals(height, geometry.outputHeight)
                val direct = Original24PreviewSession(source, File(root, "gpu-input"), {}, geometry) {
                    input.inputStream()
                }.use { session ->
                    session.loadGpuAsShotInput().also {
                        assertEquals(expectedWidth, it.width)
                        assertEquals(expectedHeight, it.height)
                        GpuPreviewInputSize.checkSupported(it.width, it.height)
                        assertNull(session.previewExternalEv)
                        assertEquals(0, session.previewFieldBuilds)
                        assertFalse(session.hasUnreleasedResources)
                    }
                }
                // Independent source decode -> one BOX reduction, never a second resize of CPU's 1024 input.
                val expected = reduceOriginal(input, root, width, height, expectedWidth, expectedHeight)
                repeat(expected.pixelCount * 3) { i ->
                    if (expected[i].toRawBits() != direct[i].toRawBits()) fail("RGB16 BOX channel $i differs")
                }
                println("GPU input RGB16 BOX $width x $height -> ${direct.width} x ${direct.height}: bit-exact")
                DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
            }
        }
    }

    private fun reduceOriginal(input: File, root: File, width: Int, height: Int,
        reducedWidth: Int, reducedHeight: Int): LinearSrgbImage {
        val file = File(root, "reference.rgb16")
        assertTrue(file.createNewFile())
        try {
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                    val metadata = JSONObject(NativeOriginalRaster.decode24(source.fd, output.fd, CancellationSignal()))
                    assertEquals(width, metadata.getInt("width"))
                    assertEquals(height, metadata.getInt("height"))
                }
            }
            val raster = OriginalRasterFile(file, width, height, 2)
            try {
                return OriginalFields.reduce(raster, reducedWidth, reducedHeight, 3, { false }) { rgb, output ->
                    rgb.copyInto(output)
                }.reducedRgb()
            } finally { raster.closePending() }
        } finally { assertTrue(file.delete()) }
    }

    private fun fixture(width: Int = 64, height: Int = 48,
        action: (File, File, SharedOriginalReference, Original24PreflightGeometry) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        val root = File.createTempFile("gpu-input-synthetic-", ".tmp", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, width, height, 1) }
        val source = SharedOriginalReference("content://example.test/gpu-input/synthetic",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        var succeeded = false
        try { action(input, root, source, geometry); succeeded = true }
        finally { if (succeeded) { assertTrue(input.delete()); assertTrue(root.delete()) } }
    }
}
