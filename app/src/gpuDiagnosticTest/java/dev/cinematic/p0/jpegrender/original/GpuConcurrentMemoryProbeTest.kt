package dev.cinematic.p0.jpegrender.original

import android.app.ActivityManager
import android.graphics.PixelFormat
import android.media.ImageReader
import android.net.Uri
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuInputRequestDeadline
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.photoroll.*
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** #260 partial: actual save factory/admission + GPU input/preview, no Activity or FGS lifecycle.
 * A's first MIST band is deliberately held: observed memory, never normal latency or a true peak.
 * Existing observer boundaries bracket B preflight/decode/reduce together; no native substage claim.
 */
class GpuConcurrentMemoryProbeTest {
    @Test fun measureSaveBandAndNextPhoto() = probe(cancelInput = false)
    @Test fun cancelNextPhotoWhileSaveBandIsHeld() = probe(cancelInput = true)

    private fun probe(cancelInput: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("gpuConcurrentMemory") == "true")
        val size = args.getString("gpuConcurrentMemorySize") ?: "SMALL"
        val dimensions = when (size) {
            "SMALL" -> 512 to 770
            "12MP" -> 4000 to 3000
            "24MP" -> 6000 to 4000
            else -> error("Expected SMALL, 12MP or 24MP")
        }
        // Large cancellation adds no new ownership condition; keep its selector small and explicit.
        require(!cancelInput || size == "SMALL")
        val timeout = if (size == "SMALL") 180L else 900L
        GpuExportBoundaryFixture.run(width = dimensions.first, height = dimensions.second) { fixture ->
            val app = fixture.app
            val sourceA = fixture.sources.single()
            val sourceB = fixture.createSource()
            assertNotEquals(sourceA.fingerprint, sourceB.fingerprint)
            val save = fixture.prepare(sourceA)
            val requestA = save.request
            val reservation = requireNotNull(save.reserveForegroundExport(GpuExportBoundaryFixture.ALLOWED))
            val reached = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val first = AtomicBoolean(true)
            val inputReleased = AtomicInteger()
            val cancellation = CancellationSignal()
            val report = JSONObject().put("scope", "SYNTHETIC_HELD_BAND_CONCURRENT_INPUT_NOT_FGS_UI_OR_DEVICE_BUDGET")
                .put("size", size).put("width", fixture.width).put("height", fixture.height)
                .put("cancelInput", cancelInput).put("heapLimitBytes", Runtime.getRuntime().maxMemory())
                .put("aBitmapPayloadBytes", fixture.width.toLong() * fixture.height * 4)
                .put("aRasterDiskBytes", fixture.width.toLong() * fixture.height * 6)
                .put("outcome", "STARTED")
            val metrics = OriginalProbeMetrics(app)
            val lock = Any()
            fun stage(name: String) = synchronized(lock) { metrics.stage(name) }
            val witness = GpuExportBoundaryWitness { event ->
                if (!event.complete && event.stage == "MIST" && event.origin == 0 && first.compareAndSet(true, false)) {
                    synchronized(lock) {
                        report.put("aHeldTextureBytes", event.balance.bytes)
                            .put("aHeldTextures", event.balance.textures)
                    }
                    stage("A_FIRST_MIST_BAND_BITMAP_AND_TEXTURES_HELD")
                    reached.countDown()
                    check(resume.await(timeout, TimeUnit.SECONDS)) { "Held export interval expired" }
                }
            }
            var export: CompletableFuture<ManualOriginalTrialSaveState>? = null
            var input: CompletableFuture<GpuPhotoRollInputs.Input>? = null
            var session: GpuPhotoRollSession? = null
            var reader: ImageReader? = null
            var drain: HandlerThread? = null
            var primary: Throwable? = null
            var exportObserver: AutoCloseable? = null
            var inputObserver: AutoCloseable? = null
            try {
                exportObserver = Original24GpuOutput.observeForDiagnostics(witness)
                inputObserver = GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                        override fun preflightOpened() {
                            assertEquals(0L, reached.count)
                            assertEquals(1L, resume.count)
                            assertFalse(requireNotNull(export).isDone)
                            stage("B_SNAPSHOT_FD_HELD_BEFORE_NATIVE_PREFLIGHT")
                            if (cancelInput) cancellation.cancel()
                        }
                        override fun released() {
                            inputReleased.incrementAndGet()
                            stage("B_INPUT_IO_NATIVE_AND_TOKEN_RELEASED")
                        }
                    })
                stage("BEFORE_A_SAVE")
                export = save.advanceForegroundExport(reservation, { GpuExportBoundaryFixture.ALLOWED }) { false }
                assertTrue("A must reach a live GPU band", reached.await(timeout, TimeUnit.SECONDS))
                fixture.assertWorkingSource(save)
                fun resources(): PhotoRollResources {
                    val memory = ActivityManager.MemoryInfo().also {
                        app.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
                    }
                    val thermal = app.getSystemService(PowerManager::class.java).currentThermalStatus
                    return PhotoRollResources(true, thermal, memory.lowMemory, memory.availMem,
                        PhotoRollCameraSnapshot(false, false, false, false), true,
                        app.noBackupFilesDir.usableSpace >= 2L * 1024 * 1024 * 1024)
                }
                stage("BEFORE_B_OPEN_WITH_A_HELD")
                input = GpuPhotoRollInputs.open(app, ::resources,
                    PhotoRollRepository(app.contentResolver, app.packageName), Uri.parse(sourceB.uri), false,
                    64L * 1024 * 1024, cancellation,
                    GpuInputRequestDeadline(nowMillis = SystemClock::elapsedRealtime), { error ->
                        if (error != null) stage("B_ADMISSION_REFUSED")
                    }) { false }
                if (cancelInput) {
                    val failure = assertThrows(ExecutionException::class.java) { input!!.get(timeout, TimeUnit.SECONDS) }
                    assertTrue(failure.cause is DngVerificationStopped || failure.cause is OperationCanceledException ||
                        failure.cause is CancellationException)
                    assertTrue(cancellation.isCanceled)
                } else {
                    val loaded = input!!.get(timeout, TimeUnit.SECONDS)
                    assertEquals(sourceB, loaded.original.reference)
                    assertNotEquals(requestA.source, loaded.original.reference)
                    assertEquals(fixture.width, loaded.geometry.outputWidth)
                    assertEquals(fixture.height, loaded.geometry.outputHeight)
                    stage("B_REDUCED_INPUT_RETURNED_WITH_A_HELD")
                    drain = HandlerThread("concurrent-memory-surface").apply { start() }
                    reader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3).also { surface ->
                        surface.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(drain!!.looper))
                    }
                    val preview = GpuPhotoRollSession(app.assets).also { session = it }
                    preview.renderer.attachSurface(reader!!.surface, 320, 240)
                    preview.renderer.setStarted(true)
                    val values = PhotoEditValues(.25, -20.0, -.3)
                    preview.submit(values)
                    preview.renderer.openPhoto(loaded.image)
                    val until = SystemClock.elapsedRealtime() + 30_000
                    while (preview.presented.value != values && SystemClock.elapsedRealtime() < until) {
                        preview.failure.value?.let { throw AssertionError("B preview failed", it) }
                        SystemClock.sleep(20)
                    }
                    assertEquals(values, preview.presented.value)
                    val snapshots = LinkedBlockingQueue<GpuPreviewSnapshot>()
                    preview.renderer.snapshot { snapshots.offer(it) }
                    val balance = requireNotNull(snapshots.poll(10, TimeUnit.SECONDS))
                    report.put("bPreviewTextureBytes", balance.allocatedBytes)
                    stage("B_PRESENTED_WITH_A_BITMAP_AND_TEXTURES_HELD")
                }
                assertEquals("B cleanup must precede result delivery", 1, inputReleased.get())
                assertFalse("B must not complete or cancel A", export!!.isDone)
                assertEquals(requestA, save.request)
                fixture.assertWorkingSource(save)
                fixture.verifyOriginals()
                resume.countDown()
                export!!.get(timeout, TimeUnit.SECONDS)
                fixture.verifyPublication(save)
                witness.assertReleased()
                stage("A_PUBLISHED_B_PREVIEW_RETAINED_OR_CANCELLED")
                report.put("outcome", "SUCCESS").put("originalsUnchanged", true)
            } catch (error: Throwable) {
                primary = error
                report.put("outcome", "FAILED").put("failureType", error.javaClass.simpleName)
                throw error
            } finally {
                // Never abandon blocked producers or remove observer ownership before draining.
                cancellation.cancel()
                if (primary != null) save.requestStop()
                resume.countDown()
                try {
                    var closeFailure: Throwable? = null
                    fun closePart(action: () -> Unit) {
                        try { action() } catch (error: Throwable) {
                            val earlier = closeFailure
                            if (earlier == null) closeFailure = error else if (earlier !== error) earlier.addSuppressed(error)
                        }
                    }
                    closePart { input?.let { future ->
                        try { future.get(timeout, TimeUnit.SECONDS) }
                        catch (expected: ExecutionException) { if (primary == null && !cancelInput) throw expected }
                    } }
                    closePart { export?.get(timeout, TimeUnit.SECONDS) }
                    var previewClosed = session == null
                    closePart { session?.let { preview ->
                        preview.close()
                        check(preview.renderer.awaitClosed(10_000)) { "B preview owner did not close" }
                        previewClosed = true
                    } }
                    closePart { GpuPhotoRollRetiredOwners.releaseBeforeOpen() }
                    // Keep the consumer alive if GL teardown is unconfirmed.
                    if (previewClosed) {
                        closePart { reader?.close() }
                        closePart { drain?.let { it.quitSafely(); it.join(5000); check(!it.isAlive) } }
                    }
                    closePart { save.abandonForegroundExport(reservation) }
                    closePart { fixture.assertClosed(save) }
                    closePart { fixture.verifyOriginals() }
                    closeFailure?.let { throw it }
                    stage("ALL_OBSERVED_OWNERS_CLOSED")
                } catch (cleanup: Throwable) {
                    report.put("outcome", "CLEANUP_FAILED").put("cleanupFailureType", cleanup.javaClass.simpleName)
                    if (primary == null) throw cleanup else if (primary !== cleanup) primary.addSuppressed(cleanup)
                } finally {
                    inputObserver?.close()
                    exportObserver?.close()
                    metrics.stop()
                    val parts = metrics.appendTo(report).toString().chunked(3000)
                    parts.forEachIndexed { index, part -> println("GPU_CONCURRENT_MEMORY_PART=$index/${parts.size}:$part") }
                }
            }
        }
    }
}
