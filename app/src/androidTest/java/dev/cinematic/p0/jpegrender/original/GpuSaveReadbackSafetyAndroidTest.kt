package dev.cinematic.p0.jpegrender.original

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Ordinary GPU counterpart of the private pipeline readback guarantee; no publication. */
class GpuSaveReadbackSafetyAndroidTest {
    @Test fun bothOrientationsAndStrengthsKeepSameFileProofOriginalAndReleasedOwners() {
        requireGpuSaveSafetyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "gpu-readback-${UUID.randomUUID()}")
        check(root.mkdir())
        // Keep private evidence on failure; this test never removes user-owned media.
        for (orientation in listOf(1, 6)) {
            val input = File(root, "input-$orientation.dng")
            val bytes = input.outputStream().use { writeSyntheticTrialDng(it, 64, 48, orientation) }
            val fingerprint = DngContentVerifier.identify(bytes, { input.inputStream() })
            val source = SharedOriginalReference("content://example.test/gpu-readback/$orientation",
                fingerprint, OriginalGrant.USER_SELECTED)
            val hashes = mutableListOf<String>()
            for ((index, strength) in listOf(0.0, .5).withIndex()) {
                val output = File(root, "output-$orientation-$index.jpg")
                val io = object : AutoRenderIo {
                    override fun openOriginal() = input.inputStream()
                    override fun openOriginalDescriptor(): AutoReadOnlyDescriptor =
                        error("Only verified immutable attempt snapshots may enter native input")
                    override fun openOutput() = Files.newOutputStream(output.toPath(),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    override fun openOutputRead() = output.inputStream()
                }
                val attempt = Original24Attempt(context,
                    Original24Request("synthetic_readback", index + 1L, source, strength),
                    io, File(root, "scratch-$orientation-$index"), developEngine = DevelopEngine.GPU)
                var primary: Throwable? = null
                try {
                    val result = attempt.render { false }
                    val width = if (orientation == 6) 48 else 64
                    val height = if (orientation == 6) 64 else 48
                    assertEquals(width, result.output.width)
                    assertEquals(height, result.output.height)
                    assertTrue(result.geometry.fullCoverage)
                    assertEquals(strength, result.request.strength, 0.0)
                    assertEquals(RenderedFaceState.NOT_RUN, result.face)
                    assertEquals(RenderedExposureState.DIAGNOSTIC, result.exposure)
                    assertEquals(AutoInputProfile.UNKNOWN, result.profile)
                    val readback = AndroidJpegInspector().inspect({ output.inputStream() }, output.length()) { false }
                    assertEquals(result.fingerprint, readback.fingerprint)
                    val bitmap = requireNotNull(BitmapFactory.decodeFile(output.path,
                        BitmapFactory.Options().apply { inSampleSize = 1; inScaled = false }))
                    try { assertEquals(width, bitmap.width); assertEquals(height, bitmap.height) }
                    finally { bitmap.recycle() }
                    DngContentVerifier.verify(fingerprint, { input.inputStream() })
                    hashes.add(result.fingerprint.sha256)
                } catch (failure: Throwable) { primary = failure; throw failure }
                finally {
                    try { attempt.close() }
                    catch (close: Throwable) {
                        val failure = primary
                        if (failure == null) throw close
                        if (close !== failure) failure.addSuppressed(close)
                    }
                }
                assertFalse(attempt.hasUnreleasedResources)
            }
            assertNotEquals("Strength must affect the real GPU JPEG", hashes[0], hashes[1])
        }
    }
}
