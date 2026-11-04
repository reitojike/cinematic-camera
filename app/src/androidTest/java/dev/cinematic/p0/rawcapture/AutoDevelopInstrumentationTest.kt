package dev.cinematic.p0.rawcapture

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic adapter only. Does not assert real DNG decode/profile/face/quality or Pixel performance. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AutoDevelopInstrumentationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun isolatedOnly() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") && Build.VERSION.SDK_INT >= 29)
    }
    private fun fingerprint(bytes: ByteArray) = OriginalFingerprint(bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    @Test fun successfulNativeAttemptClosesIoAndPublishesThroughExistingBoundary() {
        Fixture("valid").use { f ->
            assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
            assertEquals(1, f.starts)
            assertFalse(f.backend.hasUnreleasedResources)
            assertFalse(f.runner.hasUnreleasedResources)
            val record = f.publication().read()!!
            assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
            assertEquals(1, MediaStoreJpegRows(context).find(record.target).size)
            assertEquals(AutoRunStatus.PUBLISHED, f.runner.advance().status)
            assertEquals(1, f.starts)
            f.assertOriginal()
        }
    }
    @Test fun successWithoutOutputIsInvalidNotResourceLeak() {
        assertInvalidOutput("missing")
    }
    @Test fun producerFailureIsInvalidAfterAllHandlesActuallyClose() {
        assertInvalidOutput("producer-fault")
    }
    private fun assertInvalidOutput(mode: String) {
        Fixture(mode).use { f ->
            assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
            assertEquals(AutoJobReason.INVALID_OUTPUT, f.journal.read()!!.reason)
            assertFalse(f.backend.hasUnreleasedResources)
            assertFalse(f.runner.hasUnreleasedResources)
            assertNull(f.publication().read())
            val another = f.admission.tryStart()
            assertNotNull(another) // Invalid output must not permanently block unrelated jobs.
            f.admission.completeAfterResourcesClosed(another!!)
            f.assertOriginal()
        }
    }
    @Test fun previewAdapterCannotStartOrPublishAnAutomaticFinalRequest() {
        Fixture("preview").use { f ->
            assertEquals(AutoRunStatus.HELD, f.runner.advance().status)
            assertEquals(0, f.starts)
            assertNull(f.publication().read())
            f.assertOriginal()
        }
    }
    @Test fun autoAndManualCannotOpenTwoPublishersForTheSameOperation() {
        Fixture("valid").use { f ->
            val manual = AndroidRenderedJpegOperation.get(context, f.request.captureId, 1)
            manual.createOutput().use { it.write(f.jpeg) }
            manual.publisher(f.request.completed(JpegOutputDescription(16, 12, 1, "sRGB", f.jpeg.size.toLong())),
                setOf(f.request.contract), { true })
            assertThrows(CaptureJournalConflict::class.java) { f.runner.advance() }
            assertEquals(AutoJobPhase.PUBLISHING, f.journal.read()!!.phase)
            assertNull(f.publication().read())
            assertFalse(f.runner.hasUnreleasedResources)
            f.assertOriginal()
        }
    }

    private inner class Fixture(private val mode: String) : AutoCloseable {
        private val id = "auto_smoke_${UUID.randomUUID().toString().replace("-", "")}"
        private val original = "synthetic source bytes, not a decodable DNG".toByteArray()
        private val originalTarget = DngPublicationTarget(id, UUID.randomUUID().toString().replace("-", ""))
        private val sourceRows = MediaStoreDngPendingRows(context)
        private val originalUri = requireNotNull(sourceRows.insert(originalTarget))
        private val root = File(context.noBackupFilesDir, "raw-captures")
        private val generation = RenderedJpegFiles(root, id, 1).generationRoot()
        val jpeg: ByteArray = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                bitmap.eraseColor(Color.rgb(70, 120, 150))
                ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }.toByteArray()
            } finally { bitmap.recycle() }
        }
        val request = AutoDevelopRequest(id, 1, SharedOriginalReference(originalUri, fingerprint(original), OriginalGrant.APP_OWNED),
            RenderRecipeSnapshot("synthetic-auto-v1", "synthetic-only"), "synthetic-final-adapter-v1", 16, 12,
            AutoRenderQuality.FINAL_ORIGINAL, RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED, AutoInputProfile.SUPPORTED)
        val journal = AutoDevelopJournal(id, 1, AndroidAtomicJournalStorage(requireNotNull(generation.parentFile), "1", CaptureJournalFile.AUTO_DEVELOP))
        val admission = AutoDevelopCoordinator()
        var starts = 0
        private val adapter = object : RendererAdapter {
            override val contract get() = request.contract
            override val quality = if (mode == "preview") AutoRenderQuality.PREVIEW else AutoRenderQuality.FINAL_ORIGINAL
            override fun supports(request: AutoDevelopRequest) = request == this@Fixture.request
            override fun createAttempt(request: AutoDevelopRequest, attemptId: String, io: AutoRenderIo) = object : AutoRenderAttempt {
                override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                    starts++
                    check(!shouldStop())
                    assertArrayEquals(original, io.openOriginal().use { it.readBytes() })
                    assertTrue(io.openOriginalDescriptor().fd >= 0) // Deliberately left to the backend owner to close.
                    if (mode != "missing") {
                        val output = io.openOutput() // Also deliberately left open: close acknowledgement owns it.
                        output.write(jpeg)
                        if (mode == "producer-fault") assertThrows(IndexOutOfBoundsException::class.java) { output.write(byteArrayOf(1), 0, 2) }
                    }
                    return AutoRenderReceipt(request, JpegOutputDescription(16, 12, 1, "sRGB", jpeg.size.toLong()))
                }
                override fun close() {}
            }
        }
        val backend = AndroidAutoDevelopBackend(context, request, AutoRendererCatalog(listOf(adapter)))
        val runner = AutoDevelopRunner(journal, admission, backend) { true }
        init {
            sourceRows.openOutput(originalTarget, originalUri).use { it.write(original) }
            sourceRows.publish(originalTarget, originalUri)
            journal.prepare(request)
            admission.updateConditions(AutoDevelopConditions(true, false, false, false, true, true, true, true))
        }
        fun publication() = JpegPublicationJournal(id, 1,
            AndroidAtomicJournalStorage(requireNotNull(generation.parentFile), "1", CaptureJournalFile.JPEG_PUBLICATION))
        fun assertOriginal() = DngContentVerifier.verify(fingerprint(original), {
            requireNotNull(context.contentResolver.openInputStream(Uri.parse(originalUri)))
        })
        override fun close() {
            publication().read()?.let { row -> row.uri?.let { context.contentResolver.delete(Uri.parse(it), null, null) } }
            context.contentResolver.delete(Uri.parse(originalUri), null, null)
            val ownDirectory = File(root, id)
            check(ownDirectory.canonicalFile.parentFile == root.canonicalFile && ownDirectory.name == id)
            ownDirectory.deleteRecursively() // Only this test's generated ID and synthetic data.
        }
    }
}
