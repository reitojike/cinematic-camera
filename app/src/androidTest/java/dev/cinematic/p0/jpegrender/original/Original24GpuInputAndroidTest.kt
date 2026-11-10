package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reuses the existing native input path. No EGL or user photos, no save/publication. */
@RunWith(AndroidJUnit4::class)
class Original24GpuInputAndroidTest {
    @Test fun inputOnlyEntryMatchesCpuSessionAsShotWithoutPreparingR7() = fixture { input, root, source, geometry ->
        fun session(name: String) = Original24PreviewSession(source, File(root, name), {}, geometry) { input.inputStream() }
        val diagnostic = session("gpu-input")
        val direct = try {
            diagnostic.loadAsShotInput().also {
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
            assertThrows(java.util.concurrent.CancellationException::class.java) { session.loadAsShotInput { true } }
            assertFalse(session.hasUnreleasedResources)
            assertThrows(IllegalStateException::class.java) { session.loadAsShotInput() }
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        }
    }

    private fun fixture(action: (File, File, SharedOriginalReference, Original24PreflightGeometry) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        val root = File.createTempFile("gpu-input-synthetic-", ".tmp", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
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
