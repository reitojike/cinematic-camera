package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Synthetic input only. Half is a guide, never returned as the session's exact input. */
class InitialHalfSessionAndroidTest {
    @Test fun halfAndFullShareSnapshotButReleaseEachRasterAndReturnExactPixels() = fixture { file, root, source, geometry ->
        val baseline = Original24PreviewSession(source, File(root, "baseline"), {}, geometry) { file.inputStream() }
            .use { it.loadGpuAsShotInput() }
        var escaped: (() -> LinearSrgbImage)? = null
        val events = mutableListOf<Pair<PreviewRasterMode, Boolean>>()
        val scratch = File(root, "guide-full")
        Original24PreviewSession(source, scratch, {}, geometry,
            beforeExactDecode = { fd, _, half ->
                escaped = half
                val before = ParcelFileDescriptor.fromFd(fd).use { android.system.Os.fstat(it.fileDescriptor) }
                val guide = half()
                assertEquals(geometry.outputWidth / 2, guide.width)
                assertEquals(geometry.outputHeight / 2, guide.height)
                assertFalse(File(scratch, "source-linear.rgb16").exists())
                val after = ParcelFileDescriptor.fromFd(fd).use { android.system.Os.fstat(it.fileDescriptor) }
                assertEquals(before.st_ino, after.st_ino)
                assertEquals(before.st_size, after.st_size)
                assertThrows(IllegalStateException::class.java) { half() }
            }, observeDecode = { mode, begin -> events += mode to begin }) { file.inputStream() }.use {
            val exact = it.loadGpuAsShotInput()
            assertEquals(baseline.width, exact.width)
            assertEquals(baseline.height, exact.height)
            assertArrayEquals(baseline.copyPixels(), exact.copyPixels(), 0f)
            assertFalse(it.hasUnreleasedResources)
            assertFalse(scratch.exists())
        }
        assertEquals(listOf(PreviewRasterMode.HALF_CANDIDATE to true, PreviewRasterMode.HALF_CANDIDATE to false,
            PreviewRasterMode.FULL to true, PreviewRasterMode.FULL to false), events)
        assertThrows(IllegalStateException::class.java) { requireNotNull(escaped).invoke() }
        DngContentVerifier.verify(source.fingerprint, { file.inputStream() })
    }

    @Test fun cancellationDuringHalfNeverStartsFullEvenIfCallbackSwallowsTheFailure() = fixture { file, root, source, geometry ->
        val scratch = File(root, "cancel-half")
        val modes = mutableListOf<PreviewRasterMode>()
        lateinit var session: Original24PreviewSession
        session = Original24PreviewSession(source, scratch, {}, geometry,
            beforeExactDecode = { _, _, half -> runCatching { half() }; Unit },
            observeDecode = { mode, begin -> if (begin) { modes += mode; session.requestStop() } }) { file.inputStream() }
        session.use {
            assertNotNull(runCatching { it.loadGpuAsShotInput() }.exceptionOrNull())
            assertEquals(listOf(PreviewRasterMode.HALF_CANDIDATE), modes)
            assertFalse(it.hasUnreleasedResources)
            assertFalse(scratch.exists())
        }
    }

    @Test fun stoppingAfterGuideSuppressesFullAndReclaimsTheSnapshot() = fixture { file, root, source, geometry ->
        val scratch = File(root, "stop-after-guide")
        val modes = mutableListOf<PreviewRasterMode>()
        lateinit var session: Original24PreviewSession
        session = Original24PreviewSession(source, scratch, {}, geometry,
            beforeExactDecode = { _, _, half -> half(); session.requestStop() },
            observeDecode = { mode, begin -> if (begin) modes += mode }) { file.inputStream() }
        session.use {
            assertThrows(CancellationException::class.java) { it.loadGpuAsShotInput() }
            assertEquals(listOf(PreviewRasterMode.HALF_CANDIDATE), modes)
            assertFalse(it.hasUnreleasedResources)
            assertFalse(scratch.exists())
        }
    }

    @Test fun inputStageTimingDoesNotChangePixelsOrOriginalAndDoesNotClaimPresentation() = fixture { file, root, source, geometry ->
        val baseline = Original24PreviewSession(source, File(root, "timing-baseline"), {}, geometry) { file.inputStream() }
            .use { it.loadGpuAsShotInput() }
        var records = emptyList<dev.cinematic.p0.metrics.GpuPreviewTimingRecord>()
        val timing = requireNotNull(dev.cinematic.p0.metrics.GpuPreviewTiming.start(22, { records = it }))
        timing.pixels(geometry.outputWidth, geometry.outputHeight)
        val measured = Original24PreviewSession(source, File(root, "timing-measured"), {}, geometry, timing = timing) {
            file.inputStream()
        }.use { session -> session.loadGpuAsShotInput().also { assertFalse(session.hasUnreleasedResources) } }
        timing.finish(dev.cinematic.p0.metrics.GpuPreviewTimingOutcome.CANCELLED) // Input-only test, never presented.
        assertArrayEquals(baseline.copyPixels(), measured.copyPixels(), 0f)
        for (stage in listOf(dev.cinematic.p0.metrics.GpuPreviewTimingStage.SNAPSHOT,
            dev.cinematic.p0.metrics.GpuPreviewTimingStage.NATIVE_DECODE,
            dev.cinematic.p0.metrics.GpuPreviewTimingStage.BOX_REDUCE,
            dev.cinematic.p0.metrics.GpuPreviewTimingStage.VERIFY_AFTER)) {
            val record = records.single { it.stage == stage }
            assertEquals(dev.cinematic.p0.metrics.GpuPreviewTimingOutcome.COMPLETED, record.outcome)
            assertTrue(requireNotNull(record.nanos) >= 0)
        }
        assertEquals(dev.cinematic.p0.metrics.GpuPreviewTimingOutcome.NOT_REACHED,
            records.single { it.stage == dev.cinematic.p0.metrics.GpuPreviewTimingStage.GPU_FIRST_FRAME }.outcome)
        assertFalse(records.joinToString("") { it.line() }.contains(source.uri))
        DngContentVerifier.verify(source.fingerprint, { file.inputStream() })
    }

    @Test fun optionalSyntheticHalfCostProbeSeparatesGuideReadyAndExactDecodeStart() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("initialHalfTiming") == "true")
        fixture(2048, 1536) { file, root, source, geometry ->
            // Exploratory ABBA, not a device-performance or draw-time guarantee.
            listOf(false, true, true, false).forEachIndexed { index, guideEnabled ->
                val start = System.nanoTime()
                var guideReady = -1L
                var exactStart = -1L
                val hook: ((Int, CancellationSignal, () -> LinearSrgbImage) -> Unit)? =
                    if (guideEnabled) { _, _, half -> half(); guideReady = System.nanoTime() - start; Unit } else null
                Original24PreviewSession(source, File(root, "timing-$index"), {}, geometry,
                    beforeExactDecode = hook,
                    observeDecode = { mode, begin -> if (begin && mode == PreviewRasterMode.FULL) exactStart = System.nanoTime() - start }) {
                    file.inputStream()
                }.use { session ->
                    session.loadGpuAsShotInput()
                    val exactReady = System.nanoTime() - start
                    assertTrue(exactStart >= 0 && exactReady >= exactStart)
                    if (guideEnabled) assertTrue(guideReady >= 0 && exactStart >= guideReady)
                    println("INITIAL_HALF_DECODE index=$index guide=$guideEnabled guideReadyNs=$guideReady exactStartNs=$exactStart exactReadyNs=$exactReady")
                    assertFalse(session.hasUnreleasedResources)
                }
            }
        }
    }

    private fun fixture(width: Int = 64, height: Int = 48,
        action: (File, File, SharedOriginalReference, Original24PreflightGeometry) -> Unit) {
        requireGpuInputSafetyFixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("initial-half-synthetic-", ".tmp", context.cacheDir)
        check(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, width, height, 6) }
        val source = SharedOriginalReference("content://example.test/initial-half/synthetic",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        var success = false
        try { action(input, root, source, geometry); success = true }
        finally { if (success) { check(input.delete()); check(root.delete()) } }
    }

}
