package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Product owner + real full pipeline, with one explicit EGL-loss boundary. No performance sampling. */
class GpuProductContextLossAndroidTest {
    @Test fun submitQueuedBeforeContextLossCannotReopenBehindFailureAndExplicitRetryChecksOriginalAgain() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File.createTempFile("gpu-owner-private-", ".tmp", target.cacheDir)
        check(root.delete() && root.mkdir())
        val privateContext = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = File(root, "store").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val provider = GpuProductInputAndroidTest.Provider(privateContext, 4000, 3000)
        val resolver = ContentResolver.wrap(provider)
        val app = object : ContextWrapper(privateContext) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        val resources = PhotoRollResources(true, 0, false, 2L * 1024 * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        val queue = AndroidAutoDevelopQueue.get(app)
        queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
        DevelopExecution.updateConditions(resources.developConditions(true))
        val sourceRenderer = ManualTrialPhotoRollRenderer(app, { resources }, developEngine = DevelopEngine.GPU)
        val repository = PhotoRollRepository(resolver, target.packageName)
        val drain = HandlerThread("gpu-owner-synthetic-surface").apply { start() }
        val reader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain.looper))
        val ledgers = CopyOnWriteArrayList<GpuMemoryLedger>()
        val sessions = mutableListOf<GpuPhotoRollSession>()
        val failNext = AtomicBoolean()
        val drawing = CountDownLatch(1)
        val releaseDraw = CountDownLatch(1)
        fun createSession() = GpuPhotoRollSession(target.assets) { egl, ledger ->
            ledgers += ledger
            val real = DefaultStageClearGpu(egl, ledger)
            object : StageClearGpu by real {
                override fun renderPreview(photo: StageClearGpuPhoto, params: GpuRenderParams,
                    target: GpuPreviewTarget, display: GpuDisplayOptions): GpuFrameStats {
                    if (failNext.compareAndSet(true, false)) {
                        drawing.countDown()
                        check(releaseDraw.await(15, TimeUnit.SECONDS)) { "Synthetic context loss was not released" }
                        throw GpuContextLostException("Synthetic product context loss")
                    }
                    return real.renderPreview(photo, params, target, display)
                }
            }
        }.also { sessions += it }
        fun open(session: GpuPhotoRollSession, id: Int) {
            sourceRenderer.discardTransientInputs()
            AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            val input = sourceRenderer.openGpu(repository, provider.uri(id), false, CancellationSignal(),
                GpuInputRequestDeadline(nowMillis = SystemClock::elapsedRealtime), { assertNull(it) }) {
                PhotoAdjustmentPolicy.defaults
            }
            assertEquals(if (id == 1) 1024 else 768, input.input.width)
            assertEquals(if (id == 1) 768 else 1024, input.input.height)
            session.renderer.attachSurface(reader.surface, 320, 240)
            session.renderer.setStarted(true)
            session.submit(input.values)
            session.renderer.openPhoto(input.input)
            await { session.failure.value?.let { throw AssertionError("Unexpected GPU failure", it) }
                session.presented.value == input.values }
        }
        try {
            val first = createSession()
            open(first, 1)
            val readsAtOpen = provider.opens.get()
            failNext.set(true)
            first.submit(PhotoEditValues(.6, 0.0, 0.0))
            assertTrue(drawing.await(10, TimeUnit.SECONDS))
            // Accepted before failure, but its GL wake executes only after the failing draw.
            first.submit(PhotoEditValues(.7, 0.0, 0.0))
            releaseDraw.countDown()
            await { first.failure.value is GpuContextLostException }
            val failed = snapshot(first)
            assertEquals("queued input must not recreate a GPU engine behind a visible failure", 1, ledgers.size)
            assertEquals(0L, failed.allocatedBytes)
            assertEquals(0, failed.windowCount)
            assertEquals(0, ledgers.single().textureCount)
            first.submit(PhotoEditValues(.8, 0.0, 0.0))
            assertEquals(failed.drawCount, snapshot(first).drawCount)
            assertEquals(readsAtOpen, provider.opens.get())
            provider.verifyUnchanged()
            first.close()
            assertTrue(first.renderer.awaitClosed(10_000))
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()

            // The product's explicit retry creates a new owner and performs a fresh original check.
            val retry = createSession()
            open(retry, 1)
            assertTrue(provider.opens.get() > readsAtOpen)
            assertNull(retry.failure.value)
            assertTrue(snapshot(retry).allocatedBytes > 0L)
            assertEquals(2, ledgers.size)
            provider.verifyUnchanged()
        } finally {
            releaseDraw.countDown()
            sessions.forEach { it.close() }
            sessions.forEach { assertTrue(it.renderer.awaitClosed(10_000)) }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            ledgers.forEach { assertEquals(0L, it.allocatedBytes); assertEquals(0, it.textureCount) }
            sourceRenderer.discardTransientInputs()
            AndroidManualOriginalTrialSaves.discardPreview(app).get(30, TimeUnit.SECONDS)
            reader.close(); drain.quitSafely(); drain.join(5_000)
            assertFalse(drain.isAlive)
            provider.verifyUnchanged()
            assertEquals(0, provider.writes.get())
            assertTrue(provider.readers.none { it.fileDescriptor.valid() })
            assertFalse("transient input files must not survive owner close", root.walkTopDown().any {
                it.name == "source-snapshot.dng" || it.name == "source-linear.rgb16" })
        }
    }

    private fun snapshot(session: GpuPhotoRollSession): GpuPreviewSnapshot {
        val values = LinkedBlockingQueue<GpuPreviewSnapshot>()
        session.renderer.snapshot { values.offer(it) }
        return checkNotNull(values.poll(10, TimeUnit.SECONDS))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        do { if (condition()) return; SystemClock.sleep(20) } while (SystemClock.uptimeMillis() < deadline)
        error("Synthetic GPU owner condition did not complete")
    }
}
