package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.gpu.preview.GpuInputAdmissionRefused
import dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** One fresh instrumentation process; real product input IO over private synthetic DNGs only. */
class GpuInputAdmissionSafetyAndroidTest {
    @Test fun productWaitPreservesCancellationAndReadFailureIdentity() {
        requireGpuInputSafetyFixture()
        val malformed = object : RuntimeException() {
            override val cause: Throwable get() = error("Synthetic inaccessible cause")
        }
        val failures = listOf(OperationCanceledException(), java.util.concurrent.CancellationException(),
            dev.cinematic.p0.rawcapture.DngVerificationStopped(), IOException("synthetic read"), malformed)
        for (primary in failures) {
            val future = java.util.concurrent.CompletableFuture<Unit>().apply { completeExceptionally(primary) }
            val actual = runCatching { awaitTrialPreview(future, CancellationSignal(), awaitCancelledOwner = true) }.exceptionOrNull()
            assertSame("The product wait must not relabel or inspect an arbitrary cause chain", primary, actual)
        }
        for (primary in listOf(OperationCanceledException(), java.util.concurrent.CancellationException(),
            dev.cinematic.p0.rawcapture.DngVerificationStopped(), IOException("synthetic wrapped read"))) {
            val future = java.util.concurrent.CompletableFuture<Unit>().apply {
                completeExceptionally(java.util.concurrent.CompletionException(primary))
            }
            assertSame(primary, runCatching { awaitTrialPreview(future, CancellationSignal(), true) }.exceptionOrNull())
        }
        assertEquals(17, awaitTrialPreview(java.util.concurrent.CompletableFuture.completedFuture(17), CancellationSignal()))
    }

    @Test fun rejectedAndCancelledInputsKeepPrimaryFailureAndReleaseAdmissionBeforeNextInput() {
        requireGpuInputSafetyFixture()
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("gpu-input-safety-", ".tmp", target.cacheDir.canonicalFile)
        check(root.delete() && root.mkdir())
        val fixtureContext = object : ContextWrapper(target) { override fun getCacheDir(): File = root }
        val provider = GpuProductInputAndroidTest.Provider(fixtureContext, 64, 48)
        val resolver = ContentResolver.wrap(provider)
        val storageReads = AtomicInteger()
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getNoBackupFilesDir(): File {
                storageReads.incrementAndGet()
                return File(root, "private").apply { check(isDirectory || mkdir()) }
            }
        }
        val ready = PhotoRollResources(true, 0, false, 2L * 1024 * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        val repository = PhotoRollRepository(resolver, target.packageName)
        var succeeded = false
        var conditionsOwned = false
        try {
            AndroidAutoDevelopQueue.get(app).manualOriginalIo { Unit }.get(30, TimeUnit.SECONDS)
            check(storageReads.get() > 0) { "Run this class in a fresh isolated instrumentation process" }
            DevelopExecution.updateConditions(ready.developConditions(true))
            conditionsOwned = true
            fun open(uri: Uri = provider.uri(1), signal: CancellationSignal = CancellationSignal(),
                resources: PhotoRollResources = ready, fromFiles: Boolean = false,
                admitted: (Throwable?) -> Unit = {}) = GpuPhotoRollInputs.open(app, { resources }, repository,
                uri, fromFiles, 64L * 1024 * 1024, signal,
                GpuInputRequestDeadline(nowMillis = SystemClock::elapsedRealtime), admitted) { false }
            fun closedAdmission() {
                val attempt = DevelopExecution.tryStartGpuDiagnosticPreviewObserved(ready.developConditions(true)).attempt
                assertNotNull("Failed input must release its confirmed-closed token", attempt)
                check(DevelopExecution.completeAfterResourcesClosed(checkNotNull(attempt)))
                assertTrue("All source FDs must be closed before another input", provider.readers.none { it.fileDescriptor.valid() })
                assertEquals(0, provider.writes.get())
                provider.verifyUnchanged()
            }
            val initialReads = provider.opens.get()
            val cancelled = assertThrows(ExecutionException::class.java) {
                open(signal = CancellationSignal().apply { cancel() }).get(30, TimeUnit.SECONDS)
            }
            assertTrue(cancelled.cause is OperationCanceledException)
            assertEquals(initialReads, provider.opens.get())
            closedAdmission()

            repeat(2) {
                val rejected = assertThrows(ExecutionException::class.java) {
                    open(Uri.parse("content://invalid.example/document/test.dng"), fromFiles = true)
                        .get(30, TimeUnit.SECONDS)
                }
                assertTrue(rejected.cause is PhotoRollSourceFailure)
                assertEquals(initialReads, provider.opens.get())
                closedAdmission()
            }
            val unconfirmed = assertThrows(ExecutionException::class.java) {
                open(resources = ready.copy(foreground = null)).get(30, TimeUnit.SECONDS)
            }
            assertTrue(unconfirmed.cause is GpuInputAdmissionRefused)
            assertEquals(initialReads, provider.opens.get())
            closedAdmission()

            // Cleanup may cancel the request and fail after token release. Neither fact may replace
            // the primary read/admission callback failure with a cancellation or a cleanup exception.
            for (failCleanup in listOf(false, true)) {
                val signal = CancellationSignal()
                val primary = IOException("synthetic primary input failure")
                val cleanup = IOException("synthetic released observer failure")
                val releases = AtomicInteger()
                GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                    override fun released() {
                        releases.incrementAndGet()
                        signal.cancel()
                        if (failCleanup) throw cleanup
                    }
                }).use {
                    val failure = assertThrows(ExecutionException::class.java) {
                        open(signal = signal, admitted = { refusal -> assertNull(refusal); throw primary })
                            .get(30, TimeUnit.SECONDS)
                    }
                    assertSame(primary, failure.cause)
                    assertTrue(signal.isCanceled)
                    assertEquals(1, releases.get())
                    assertEquals(if (failCleanup) listOf(cleanup) else emptyList<Throwable>(), primary.suppressed.toList())
                }
                assertEquals(initialReads, provider.opens.get())
                closedAdmission()
            }

            val preflightEntered = CountDownLatch(1)
            val releasePreflight = CountDownLatch(1)
            val stopped = CancellationSignal()
            val closed = AtomicInteger()
            GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                override fun preflightOpened() {
                    preflightEntered.countDown()
                    check(releasePreflight.await(30, TimeUnit.SECONDS))
                }
                override fun released() { closed.incrementAndGet() }
            }).use {
                val inFlight = open(signal = stopped)
                try {
                    assertTrue(preflightEntered.await(30, TimeUnit.SECONDS))
                    stopped.cancel()
                    assertFalse("Cancellation request is not resource-close acknowledgement", inFlight.isDone)
                    assertEquals(0, closed.get())
                    val second = DevelopExecution.tryStartGpuDiagnosticPreviewObserved(ready.developConditions(true)).attempt
                    if (second != null) {
                        DevelopExecution.completeAfterResourcesClosed(second)
                        fail("An in-flight cancelled input must retain admission until actual close")
                    }
                } finally { releasePreflight.countDown() }
                val failure = assertThrows(ExecutionException::class.java) { inFlight.get(30, TimeUnit.SECONDS) }
                assertTrue(failure.cause is dev.cinematic.p0.rawcapture.DngVerificationStopped)
                assertEquals(1, closed.get())
                closedAdmission()
            }

            val next = open().get(30, TimeUnit.SECONDS)
            assertEquals(provider.uri(1), next.original.uri)
            assertEquals(64, next.image.width)
            assertEquals(48, next.image.height)
            assertTrue("The next selection must really decode native input", provider.opens.get() > initialReads)
            closedAdmission()
            val inputBits = next.image.copyPixels().map { it.toRawBits() }.toIntArray()
            GlThread().use { thread -> thread.call {
                EglContexts(thread, target.assets).use { egl ->
                    GlCapabilities.inspect(egl).requireSupported()
                    val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
                    DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE).use { engine ->
                        engine.openPhoto(next.image) { false }.use { photo ->
                            for (params in listOf(GpuRenderParams(0.0, PhotoAdjustments.NONE),
                                GpuRenderParams(1.0, PhotoAdjustments(60.0, 1.0)))) {
                                val output = engine.renderForTest(photo, params, GpuStageTap.Point.DISPLAY8)
                                assertEquals(next.image.width, output.width)
                                assertEquals(next.image.height, output.height)
                                assertTrue(output.copyPixels().all { it.isFinite() })
                            }
                            assertEquals(1, engine.photoAnalysisCount)
                        }
                    }
                    assertEquals(0L, ledger.allocatedBytes)
                    assertEquals(0, ledger.textureCount)
                    assertTrue(ledger.peakAllocatedBytes <= GpuMemoryLedger.PREVIEW_BUDGET)
                }
            } }
            assertArrayEquals(inputBits, next.image.copyPixels().map { it.toRawBits() }.toIntArray())
            closedAdmission()
            succeeded = true
        } finally {
            if (conditionsOwned) DevelopExecution.updateConditions(AutoDevelopConditions())
            // Retain private evidence after any failed/uncertain owner assertion. No shared row exists.
            if (succeeded) {
                check(root.canonicalFile.parentFile == target.cacheDir.canonicalFile)
                check(root.name.startsWith("gpu-input-safety-"))
                check(root.deleteRecursively())
            }
        }
    }
}
