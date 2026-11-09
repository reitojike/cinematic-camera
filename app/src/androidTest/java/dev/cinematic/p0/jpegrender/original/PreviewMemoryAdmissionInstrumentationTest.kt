package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Tiny synthetic fixtures and injected observations, not actual OS memory pressure or RAM minima. */
@RunWith(AndroidJUnit4::class)
class PreviewMemoryAdmissionInstrumentationTest {
    @Test fun decodedPreviewCanRenderAndCompareBelowOriginalAllowanceWithoutDecodingAgain() = fixture { input, root, source, geometry ->
        var available = 1024L * 1024 * 1024
        var decodeAdmissions = 0
        val session = Original24PreviewSession(source, File(root, "preview"), { stage ->
            if (!Original24MemoryPolicy.permits(stage, false, available)) throw Original24MemoryUnavailable()
            if (stage == Original24MemoryStage.ORIGINAL_DECODE) {
                decodeAdmissions++
                available = 384L * 1024 * 1024 // Inject lower post-decode availability; allocate no pressure buffer.
            }
        }, geometry) { input.inputStream() }
        try {
            session.render(.5).recycle()
            requireNotNull(session.comparisonBitmap()).recycle()
            session.render(.7).recycle()
            session.render(.7, PhotoAdjustments(25.0, .4)).recycle()
            requireNotNull(session.renderDraft(.8, PhotoAdjustments(30.0, .5))).recycle()
            requireNotNull(session.comparisonBitmap(maxEdge = 256)).recycle()
            assertEquals(1, decodeAdmissions)
            assertFalse(session.hasUnreleasedResources)
            assertFalse(Original24MemoryPolicy.permits(Original24MemoryStage.ORIGINAL_SAVE, false, available))
        } finally { session.close() }
    }

    @Test fun boundedAllocationFailureReleasesSessionAndCanRetryWithANewOwner() = fixture { input, root, source, geometry ->
        var injectOom = false
        var boundedChecks = 0
        val session = Original24PreviewSession(source, File(root, "first"), { stage ->
            if (injectOom && stage == Original24MemoryStage.BOUNDED_PREVIEW && ++boundedChecks == 3)
                throw OutOfMemoryError("synthetic pre-bitmap allocation failure")
        }, geometry) { input.inputStream() }
        val oldDisplay = session.render(.5)
        try {
            injectOom = true
            val failure = assertThrows(Original24MemoryUnavailable::class.java) { session.render(.7) }
            assertTrue(failure.cause is OutOfMemoryError)
            assertFalse(oldDisplay.isRecycled) // The caller still owns the prior display.
            assertFalse(session.hasUnreleasedResources)
            assertEquals(0, session.previewFieldBytes)
            assertThrows(IllegalStateException::class.java) { session.render(.7) }
        } finally { oldDisplay.recycle(); session.close() }
        val retry = Original24PreviewSession(source, File(root, "retry"), {}, geometry) { input.inputStream() }
        try { retry.render(.7).recycle(); assertFalse(retry.hasUnreleasedResources) }
        finally { retry.close() }
    }

    @Test fun coldDecodeStillRejectsInsufficientOriginalAllowanceAndCleansTransientOwners() = fixture { input, root, source, geometry ->
        var decodeChecks = 0
        val session = Original24PreviewSession(source, File(root, "preview"), { stage ->
            if (stage == Original24MemoryStage.ORIGINAL_DECODE) decodeChecks++
            if (!Original24MemoryPolicy.permits(stage, false, 767L * 1024 * 1024)) throw Original24MemoryUnavailable()
        }, geometry) { input.inputStream() }
        try {
            assertThrows(Original24MemoryUnavailable::class.java) { session.render(.5) }
            assertEquals(1, decodeChecks)
            assertFalse(session.hasUnreleasedResources)
            assertEquals(0, session.previewFieldBytes)
        } finally { session.close() }
    }

    @Test fun outerOriginalReadUsesPreviewPressureWithoutWeakeningSaveAdmission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val resources = dev.cinematic.p0.photoroll.PhotoRollResources(true, 0, false, 384L * 1024 * 1024,
            dev.cinematic.p0.photoroll.PhotoRollCameraSnapshot(false, false, false, false), true, true)
        val conditions = resources.developConditions(true)
        assertEquals(false, conditions.memoryBudgetConfirmed)
        assertEquals(true, conditions.previewMemoryAvailable)
        dev.cinematic.p0.rawcapture.DevelopExecution.updateConditions(conditions)
        try {
            repeat(2) { // Completion must release the one shared owner before the next read.
                val value = dev.cinematic.p0.photoroll.PhotoRollOriginalChecks.run(
                    { resources }, CancellationSignal(), preview = true) { readers, stop ->
                    assertFalse(stop())
                    readers.open { java.io.ByteArrayInputStream(byteArrayOf(73)) }.use { it.read() }
                }
                assertEquals(73, value)
            }
            assertThrows(dev.cinematic.p0.photoroll.PhotoRollSourceFailure::class.java) {
                dev.cinematic.p0.photoroll.PhotoRollOriginalChecks.run({ resources }, CancellationSignal()) { _, _ ->
                    fail("Save-budget admission must remain closed")
                }
            }
        } finally {
            dev.cinematic.p0.rawcapture.DevelopExecution.updateConditions(dev.cinematic.p0.rawcapture.AutoDevelopConditions())
        }
    }

    private fun fixture(run: (File, File, SharedOriginalReference, Original24PreflightGeometry) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "preview-memory-admission-${System.nanoTime()}")
        assertTrue(root.mkdir())
        val input = File(root, "synthetic.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
        val source = SharedOriginalReference("content://example.test/preview-memory/admission",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        run(input, root, source, geometry)
        DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        assertTrue(input.delete()); assertTrue(root.delete())
    }
}
