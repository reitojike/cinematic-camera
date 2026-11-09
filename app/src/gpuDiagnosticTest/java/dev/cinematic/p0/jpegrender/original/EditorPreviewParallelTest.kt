package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic integration only, not an editor presentation or physical-device speed claim. */
@RunWith(AndroidJUnit4::class)
class EditorPreviewParallelTest {
    @Before fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("previewParallelProbe") == "true")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(BuildConfig.EDITOR_PREVIEW_DIAGNOSTIC)
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "dev.cinematic.p0.gpudiagnostic")
    }

    @Test fun exactAndDraftMatchAcrossStrengthWarmthAndEvEdits() = fixture { input, root, source ->
        val serial = session(input, root, source, "serial")
        val parallel = session(input, root, source, "parallel")
        try {
            for ((workers, current) in listOf(1 to serial, 2 to parallel)) {
                assertNull(current.renderDiagnostic(.5, PhotoAdjustments.NONE, true, workers) { false })
                assertNull(current.previewDecisions)
                assertEquals(0, current.previewFieldBuilds)
            }
            val edits = listOf(.2 to PhotoAdjustments.NONE, .8 to PhotoAdjustments.NONE,
                .8 to PhotoAdjustments(35.0, .4), .8 to PhotoAdjustments(35.0, -.6),
                .5 to PhotoAdjustments(-20.0, -.6))
            for ((index, edit) in edits.withIndex()) {
                val (strength, adjustments) = edit
                val order = listOf(1 to serial, 2 to parallel).let { if (index % 2 == 0) it else it.reversed() }
                val exact = order.map { (workers, current) -> pixels(current, strength, adjustments, false, workers) }
                assertArrayEquals("EXACT edit=$index", exact[0], exact[1])
                assertEquals(serial.previewExternalEv, parallel.previewExternalEv)
                assertEquals(if (index < 2) 1 else index, serial.previewFieldBuilds)
                assertEquals(serial.previewFieldBuilds, parallel.previewFieldBuilds)
                for (current in listOf(serial, parallel)) assertEquals(0, current.draftFieldBytes)
                val serialDecisions = serial.previewDecisions
                val parallelDecisions = parallel.previewDecisions
                val exactEv = serial.previewExternalEv
                // Draft changes must reuse exact decisions rather than remeter coarse input.
                for (draftEdits in listOf(adjustments, adjustments.copy(exposureEv = adjustments.exposureEv + .1))) {
                    val draft = order.reversed().map { (workers, current) -> pixels(current, strength, draftEdits, true, workers) }
                    assertArrayEquals("DRAFT edit=$index adjustments=$draftEdits", draft[0], draft[1])
                    assertSame(serialDecisions, serial.previewDecisions)
                    assertSame(parallelDecisions, parallel.previewDecisions)
                    assertEquals(exactEv, serial.previewExternalEv)
                    assertEquals(exactEv, parallel.previewExternalEv)
                }
                // OFF after ON in the same session retains identical exact output.
                assertArrayEquals(exact[0], pixels(parallel, strength, adjustments, false, 1))
                for (current in listOf(serial, parallel)) assertFalse(current.hasUnreleasedResources)
            }
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
        } finally { try { serial.close() } finally { parallel.close() } }
        assertCleared(serial)
        assertCleared(parallel)
    }

    @Test fun workerCancellationClearsBothCachesAndRequiresNewSession() = fixture { input, root, source ->
        for (draft in listOf(false, true)) {
            val cancelled = session(input, root, source, "cancel-$draft")
            try {
                pixels(cancelled, .5, PhotoAdjustments.NONE, false, 2)
                pixels(cancelled, .6, PhotoAdjustments.NONE, true, 2)
                assertTrue(cancelled.previewFieldBytes > 0)
                assertTrue(cancelled.draftFieldBytes > 0)
                val stoppedOnWorker = AtomicBoolean(false)
                val stoppedThreads = ConcurrentHashMap.newKeySet<Thread>()
                assertThrows(CancellationException::class.java) {
                    cancelled.renderDiagnostic(.7, PhotoAdjustments(30.0, .2), draft, 2) {
                        (Thread.currentThread().name == "preview-pixel-candidate").also {
                            if (it) { stoppedThreads.add(Thread.currentThread()); stoppedOnWorker.set(true) }
                        }
                    }
                }
                assertTrue("Cancellation must originate in a submitted pixel task", stoppedOnWorker.get())
                assertTrue("Cancelled workers must be joined before returning", stoppedThreads.all { !it.isAlive })
                assertCleared(cancelled)
                assertThrows(IllegalStateException::class.java) {
                    cancelled.renderDiagnostic(.7, PhotoAdjustments.NONE, false, 2) { false }
                }
            } finally { cancelled.close() }
            val retry = session(input, root, source, "retry-$draft")
            val reference = session(input, root, source, "reference-$draft")
            try {
                val adjustments = PhotoAdjustments(30.0, .2)
                assertArrayEquals(pixels(reference, .7, adjustments, false, 1), pixels(retry, .7, adjustments, false, 2))
                assertArrayEquals(pixels(reference, .8, adjustments, true, 1), pixels(retry, .8, adjustments, true, 2))
            } finally { try { retry.close() } finally { reference.close() } }
            assertCleared(retry)
            assertCleared(reference)
        }
    }

    private fun assertCleared(session: Original24PreviewSession) {
        assertNull(session.previewDecisions)
        assertNull(session.previewExternalEv)
        assertEquals(0, session.previewFieldBytes)
        assertEquals(0, session.draftFieldBytes)
        assertFalse(session.hasUnreleasedResources)
    }

    private fun pixels(session: Original24PreviewSession, strength: Double, adjustments: PhotoAdjustments,
        draft: Boolean, workers: Int): IntArray {
        val bitmap = requireNotNull(session.renderDiagnostic(strength, adjustments, draft, workers) { false })
        try {
            val edge = if (draft) 256 else 1024
            assertEquals(edge, bitmap.width)
            assertTrue(bitmap.height in 1..edge)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertTrue(bitmap.colorSpace?.isSrgb == true)
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            }
        } finally { bitmap.recycle() }
    }

    private fun session(input: File, root: File, source: SharedOriginalReference, name: String): Original24PreviewSession {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val geometry = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        }
        return Original24PreviewSession(source, File(root, name),
            { Original24Resources.requireAvailable(context) }, geometry) { input.inputStream() }
    }

    private fun fixture(action: (File, File, SharedOriginalReference) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("editor-parallel-synthetic-", ".tmp", context.cacheDir)
        assertTrue(root.delete() && root.mkdir())
        val input = File(root, "input.dng")
        // Exercise both reduction limits without a full-size load test.
        input.outputStream().use { writeSyntheticTrialDng(it, 1088, 80, 1) }
        val source = SharedOriginalReference("content://example.test/editor-parallel/synthetic",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        var failure: Throwable? = null
        try { action(input, root, source) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            if (failure == null) {
                assertTrue(input.delete())
                assertTrue("All session scratch directories must be released", root.delete())
            }
        }
    }
}
