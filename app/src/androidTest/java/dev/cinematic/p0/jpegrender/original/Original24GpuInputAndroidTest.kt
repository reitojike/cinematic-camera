package dev.cinematic.p0.jpegrender.original

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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Reuses the existing native input path. No EGL or user photos, no save/publication. */
@RunWith(AndroidJUnit4::class)
class Original24GpuInputAndroidTest {
    @Test fun inputOnlyEntryLoadsNativeAsShotWithoutPreparingR7() = fixture { input, root, source, geometry ->
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
        // The independent oracle decodes native AS_SHOT RGB16; it never executes a CPU look.
        val expected = reduceOriginal(input, root, geometry.outputWidth, geometry.outputHeight,
            geometry.outputWidth, geometry.outputHeight)
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
        Original24PreviewSession(source, File(root, "next-after-cancel"), {}, geometry) { input.inputStream() }.use {
            val next = it.loadGpuAsShotInput()
            assertEquals(geometry.outputWidth, next.width)
            assertEquals(geometry.outputHeight, next.height)
            assertFalse(it.hasUnreleasedResources)
        }
    }

    @Test fun changedOriginalIsRejectedAtColdInputAndFailedSessionCannotRestart() = fixture { input, root, source, geometry ->
        val originalBytes = input.readBytes()
        val altered = originalBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        input.writeBytes(altered) // Only this test's private synthetic fixture is mutated.
        var reads = 0
        Original24PreviewSession(source, File(root, "changed"), {}, geometry,
            checkPreviewSource = { _, _ -> error("GPU cold input must not trust a metadata-only fence") }) {
            reads++; input.inputStream()
        }.use { session ->
            assertThrows(dev.cinematic.p0.rawcapture.DngContentChanged::class.java) { session.loadGpuAsShotInput() }
            assertFalse(session.hasUnreleasedResources)
            val failedReads = reads
            assertThrows(IllegalStateException::class.java) { session.loadGpuAsShotInput() }
            assertEquals(failedReads, reads)
        }
        assertArrayEquals(altered, input.readBytes()) // Failure must not repair/write the original.
        input.writeBytes(originalBytes)
        Original24PreviewSession(source, File(root, "fresh"), {}, geometry) { input.inputStream() }.use {
            assertEquals(geometry.outputWidth, it.loadGpuAsShotInput().width)
            assertFalse(it.hasUnreleasedResources)
        }
        DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
    }

    @Test fun failedSourceCloseRetainsExactStreamUntilCloseIsAcknowledged() {
        requireGpuInputSafetyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("gpu-source-close-", ".tmp", context.cacheDir)
        assertTrue(root.delete())
        val bytes = byteArrayOf(1, 2, 3)
        val source = SharedOriginalReference("content://example.test/gpu-input/close",
            DngContentVerifier.identify(bytes.size.toLong(), { ByteArrayInputStream(bytes) }), OriginalGrant.USER_SELECTED)
        var failClose = true
        var closes = 0
        var opens = 0
        val stream = object : ByteArrayInputStream(bytes) {
            override fun close() { closes++; if (failClose) throw IOException("synthetic close failure"); super.close() }
        }
        val session = Original24PreviewSession(source, root, {},
            Original24PreflightGeometry(64, 48, 64, 48, 1, 64, 48)) { opens++; stream }
        try {
            assertThrows(IOException::class.java) { session.loadGpuAsShotInput() }
            assertEquals(1, opens)
            assertTrue(session.hasUnreleasedResources)
            assertThrows(IOException::class.java) { session.close() }
            assertTrue(session.hasUnreleasedResources)
            failClose = false
            session.close()
            assertTrue(closes >= 3)
            assertEquals("Retry closes the same stream, never opens a replacement", 1, opens)
            assertFalse(session.hasUnreleasedResources)
            assertFalse(root.exists())
            assertThrows(IllegalStateException::class.java) { session.loadGpuAsShotInput() }
        } finally { failClose = false; session.close() }
    }

    @Test fun sameSizeCropAndOrientationMismatchCannotProduceGpuInput() = fixture(cropped = true) { input, root, source, geometry ->
        Original24PreviewSession(source, File(root, "matching"), {}, geometry) { input.inputStream() }.use {
            assertEquals(geometry.outputWidth, it.loadGpuAsShotInput().width)
        }
        for ((index, wrong) in listOf(geometry.copy(cropLeft = geometry.cropLeft + 1),
            geometry.copy(orientation = 2)).withIndex()) {
            assertEquals(geometry.outputWidth, wrong.outputWidth)
            assertEquals(geometry.outputHeight, wrong.outputHeight)
            Original24PreviewSession(source, File(root, "mismatch-$index"), {}, wrong) { input.inputStream() }.use {
                val failure = assertThrows(IOException::class.java) { it.loadGpuAsShotInput() }
                assertTrue(failure.message.orEmpty().contains("geometry differs"))
                assertFalse(it.hasUnreleasedResources)
                assertThrows(IllegalStateException::class.java) { it.loadGpuAsShotInput() }
            }
        }
        DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
    }

    @Test fun inputFaultsCannotBecomeOptionalComparisonSuccessOrRestart() = fixture { input, root, source, geometry ->
        val failures = listOf(IOException("synthetic source read"),
            dev.cinematic.p0.rawcapture.DngContentChanged(),
            java.util.concurrent.CancellationException("synthetic cancellation"),
            Original24MemoryUnavailable(), OutOfMemoryError("synthetic allocation"))
        for ((index, primary) in failures.withIndex()) {
            Original24PreviewSession(source, File(root, "fault-$index"), { throw primary }, geometry) {
                input.inputStream()
            }.use { session ->
                val failure = runCatching { session.loadGpuAsShotInput() }.exceptionOrNull()
                if (primary is OutOfMemoryError) {
                    assertTrue(failure is Original24MemoryUnavailable)
                    assertSame(primary, failure?.cause)
                } else assertSame(primary, failure)
                assertFalse(session.hasUnreleasedResources)
                assertThrows(IllegalStateException::class.java) { session.loadGpuAsShotInput() }
            }
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        }
        val sourceFailure = IOException("synthetic provider read")
        Original24PreviewSession(source, File(root, "read-fault"), {}, geometry) { throw sourceFailure }.use {
            assertSame(sourceFailure, runCatching { it.loadGpuAsShotInput() }.exceptionOrNull())
            assertFalse(it.hasUnreleasedResources)
            assertThrows(IllegalStateException::class.java) { it.loadGpuAsShotInput() }
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

    private fun fixture(width: Int = 64, height: Int = 48, cropped: Boolean = false,
        action: (File, File, SharedOriginalReference, Original24PreflightGeometry) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        requireGpuInputSafetyFixture()
        val root = File.createTempFile("gpu-input-synthetic-", ".tmp", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, width, height, 1) }
        if (cropped) input.writeBytes(withIntegerCrop(input.readBytes()))
        val source = SharedOriginalReference("content://example.test/gpu-input/synthetic",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        var succeeded = false
        try { action(input, root, source, geometry); succeeded = true }
        finally { if (succeeded) { assertTrue(input.delete()); assertTrue(root.delete()) } }
    }
    /** Keep original RAW/data offsets and append a new root with integral DefaultCrop tags. */
    private fun withIntegerCrop(original: ByteArray): ByteArray {
        val old = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val count = old.getShort(8).toInt() and 0xffff
        val entries = (0 until count).map { original.copyOfRange(10 + it * 12, 22 + it * 12) }.toMutableList()
        val root = original.size
        val extra = root + 2 + (count + 2) * 12 + 4
        for ((index, tag) in listOf(50719, 50720).withIndex()) entries += ByteBuffer.allocate(12)
            .order(ByteOrder.LITTLE_ENDIAN).putShort(tag.toShort()).putShort(4).putInt(2).putInt(extra + index * 8).array()
        val output = ByteBuffer.allocate(extra + 16).order(ByteOrder.LITTLE_ENDIAN).put(original).putInt(4, root)
        output.position(root)
        output.putShort((count + 2).toShort())
        for (entry in entries.sortedBy { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff }) output.put(entry)
        output.putInt(0).putInt(2).putInt(2).putInt(60).putInt(44)
        return output.array()
    }
}
