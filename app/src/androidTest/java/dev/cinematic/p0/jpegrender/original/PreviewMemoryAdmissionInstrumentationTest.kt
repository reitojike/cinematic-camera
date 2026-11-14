package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
    @Test fun gpuInputContinuesWhenInjectedPostDecodeAdmissionPermits() = fixture { input, root, source, geometry ->
        var available = 128L * 1024 * 1024
        var lowMemory = false
        var decodeAdmissions = 0
        var boundedChecks = 0
        val session = Original24PreviewSession(source, File(root, "preview"), { stage ->
            if (stage == Original24MemoryStage.ORIGINAL_DECODE) {
                if (!Original24MemoryPolicy.permits(stage, lowMemory, available)) throw Original24MemoryUnavailable()
                decodeAdmissions++
                available = 64L * 1024 * 1024
                lowMemory = true // Observation only; do not allocate artificial pressure.
            } else if (stage == Original24MemoryStage.BOUNDED_PREVIEW) {
                boundedChecks++ // Injected callback only; this does not test the OS-backed Resources default.
                assertTrue(lowMemory)
            }
        }, geometry) { input.inputStream() }
        withSession(session) {
            val image = session.loadGpuAsShotInput()
            assertEquals(geometry.outputWidth, image.width)
            assertEquals(geometry.outputHeight, image.height)
            assertEquals(1, decodeAdmissions)
            assertEquals(1, boundedChecks)
            assertFalse(session.hasUnreleasedResources)
            val bits = image.copyPixels().map { it.toRawBits() }.toIntArray()
            session.close()
            assertArrayEquals(bits, image.copyPixels().map { it.toRawBits() }.toIntArray())
            assertFalse(Original24MemoryPolicy.permits(Original24MemoryStage.ORIGINAL_SAVE, lowMemory, available))
        }
    }

    @Test fun boundedAllocationFailureReleasesSessionAndCanRetryWithANewOwner() = fixture { input, root, source, geometry ->
        var decodeChecks = 0
        var boundedChecks = 0
        val primary = OutOfMemoryError("synthetic post-decode reduction allocation failure")
        val session = Original24PreviewSession(source, File(root, "first"), { stage ->
            if (stage == Original24MemoryStage.ORIGINAL_DECODE) decodeChecks++
            if (stage == Original24MemoryStage.BOUNDED_PREVIEW) {
                boundedChecks++
                assertEquals(1, decodeChecks)
                assertTrue(File(root, "first/source-linear.rgb16").length() > 0L)
                throw primary
            }
        }, geometry) { input.inputStream() }
        withSession(session) {
            val failure = assertThrows(Original24MemoryUnavailable::class.java) { session.loadGpuAsShotInput() }
            assertSame(primary, failure.cause)
            assertEquals(1, boundedChecks)
            assertFalse(session.hasUnreleasedResources)
            assertFalse(File(root, "first").exists())
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
            assertThrows(IllegalStateException::class.java) { session.loadGpuAsShotInput() }
            assertEquals(1, decodeChecks)
        }
        val retry = Original24PreviewSession(source, File(root, "retry"), {}, geometry) { input.inputStream() }
        withSession(retry) {
            val image = retry.loadGpuAsShotInput()
            assertEquals(geometry.outputWidth, image.width)
            assertEquals(geometry.outputHeight, image.height)
            assertFalse(retry.hasUnreleasedResources)
        }
    }

    @Test fun coldGpuDecodeRejectsOsLowMemoryAndCleansTransientOwners() = fixture { input, root, source, geometry ->
        var decodeChecks = 0
        val session = Original24PreviewSession(source, File(root, "preview"), { stage ->
            if (stage == Original24MemoryStage.ORIGINAL_DECODE) decodeChecks++
            if (!Original24MemoryPolicy.permits(stage, true, 1024L * 1024 * 1024)) throw Original24MemoryUnavailable()
        }, geometry) { input.inputStream() }
        withSession(session) {
            assertThrows(Original24MemoryUnavailable::class.java) { session.loadGpuAsShotInput() }
            assertEquals(1, decodeChecks)
            assertFalse(session.hasUnreleasedResources)
            assertFalse(File(root, "preview").exists())
            assertThrows(IllegalStateException::class.java) { session.loadGpuAsShotInput() }
        }
    }

    @Test fun outerOriginalReadUsesOsPressureForBothPreviewAndSaveAdmission() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val resources = dev.cinematic.p0.photoroll.PhotoRollResources(true, 0, false, 384L * 1024 * 1024,
            dev.cinematic.p0.photoroll.PhotoRollCameraSnapshot(false, false, false, false), true, true)
        val conditions = resources.developConditions(true)
        assertEquals(true, conditions.memoryBudgetConfirmed)
        assertEquals(true, conditions.previewMemoryAvailable)
        dev.cinematic.p0.rawcapture.DevelopExecution.updateConditions(conditions)
        var primary: Throwable? = null
        try {
            for (preview in listOf(true, false, true, false)) { // Each read must release the shared owner.
                val value = dev.cinematic.p0.photoroll.PhotoRollOriginalChecks.run(
                    { resources }, CancellationSignal(), preview = preview) { readers, stop ->
                    assertFalse(stop())
                    readers.open { java.io.ByteArrayInputStream(byteArrayOf(73)) }.use { it.read() }
                }
                assertEquals(73, value)
            }
            val pressured = resources.copy(lowMemory = true)
            dev.cinematic.p0.rawcapture.DevelopExecution.updateConditions(pressured.developConditions(true))
            for (preview in listOf(true, false)) {
                assertThrows(dev.cinematic.p0.photoroll.PhotoRollSourceFailure::class.java) {
                    dev.cinematic.p0.photoroll.PhotoRollOriginalChecks.run({ pressured }, CancellationSignal(), preview = preview) { _, _ ->
                        fail("OS low-memory admission must remain closed")
                    }
                }
            }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            preserveSafetyCleanup(primary, {
                dev.cinematic.p0.rawcapture.DevelopExecution.updateConditions(dev.cinematic.p0.rawcapture.AutoDevelopConditions())
            })
        }
    }

    private fun withSession(session: Original24PreviewSession, action: () -> Unit) {
        var primary: Throwable? = null
        try { action() }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally { preserveSafetyCleanup(primary, { session.close() }) }
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
        var primary: Throwable? = null
        try { run(input, root, source, geometry) }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            preserveSafetyCleanup(primary, { DngContentVerifier.verify(source.fingerprint, { input.inputStream() }) })
        }
        // Keep the synthetic fixture as evidence on assertion/cleanup failure.
        assertTrue(input.delete()); assertTrue(root.delete())
    }
}
