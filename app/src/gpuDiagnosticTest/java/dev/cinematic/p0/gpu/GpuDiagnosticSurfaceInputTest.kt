package dev.cinematic.p0.gpu

import android.content.ComponentCallbacks2
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.gpu.preview.GpuSurfaceProbe
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** #319: actual Activity/observer/renderer, without Files or resource/admission overrides.
 * Independent results: surface only, ready foreground input, input delivered without a surface.
 * A fourth case delivers UI_HIDDEN during a new resumed load using a bounded provider barrier.
 * A failing input remains a failing test. It does not prove that EGL caused the failure.
 */
@RunWith(AndroidJUnit4::class)
class GpuDiagnosticSurfaceInputTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private lateinit var evidence: File
    private var waitingFor = "setup"
    private var conditionBoundMs = 10_000L
    private var lastState: GpuPreviewDiagnosticActivity.DiagnosticState? = null
    private var lastSnapshot: GpuPreviewSnapshot? = null

    @Test fun surfaceOnlyStopStartRendersAgain() = runProbe("surface-only", cycleSurface = true, dng = false)

    @Test fun foregroundSyntheticDngAfterRealPublicationRenders() =
        runProbe("foreground-control", cycleSurface = false, dng = true)

    @Test fun syntheticDngDeliveredWhileSurfaceAbsentRendersOnResume() =
        runProbe("paused-delivery", cycleSurface = true, dng = true)

    @Suppress("DEPRECATION")
    @Test fun delayedUiHiddenDuringResumedDngLoadDoesNotCancelNewInput() =
        runProbe("delayed-ui-hidden", cycleSurface = true, dng = true, delayedTrim = true)

    @Suppress("DEPRECATION")
    private fun runProbe(name: String, cycleSurface: Boolean, dng: Boolean, delayedTrim: Boolean = false) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
        check(target.packageName == "dev.cinematic.p0.gpudiagnostic")
        check(Log.isLoggable("GpuSurfaceProbe", Log.DEBUG)) { "Enable GpuSurfaceProbe DEBUG before this diagnostic run" }
        // Keep the process backend bound to the real application, not the controlled resolver.
        AndroidAutoDevelopQueue.get(target)
        evidence = File(checkNotNull(target.getExternalFilesDir("gpu-diagnostics")),
            "surface-input/$name-${SystemClock.elapsedRealtime()}").apply { check(mkdirs() || isDirectory) }
        var scenario: ActivityScenario<GpuPreviewDiagnosticActivity>? = null
        var provider: SyntheticDocument? = null
        var failure: Throwable? = null
        try {
            if (dng) {
                provider = SyntheticDocument(target, holdOpen = delayedTrim)
                File(evidence, "fixture.txt").writeText("generator=writeSyntheticTrialDng\nwidth=64\nheight=48\n" +
                    "orientation=1\nbytes=${provider.source.length()}\nsha256=${provider.sha256}\n")
            }
            mark("launch kind=$name")
            scenario = ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java)
            val rendererRef = AtomicReference<GpuPreviewRenderer>()
            val document = provider
            scenario.onActivity { activity ->
                rendererRef.set(activity.renderer)
                if (document != null) {
                    val resolver = ContentResolver.wrap(document)
                    activity.inputContext = object : ContextWrapper(target) {
                        override fun getApplicationContext(): Context = this
                        override fun getContentResolver(): ContentResolver = resolver
                    }
                }
            }
            val renderer = checkNotNull(rendererRef.get())
            val active = awaitSnapshot(renderer, "initial real Activity frame") {
                it.drawCount > 0 && it.windowCount == 1 && it.surfaceAttached && it.allocatedBytes > 0
            }
            if (cycleSurface) {
                scenario.moveToState(Lifecycle.State.CREATED)
                val absent = awaitSnapshot(renderer, "surface detached and EGL window released") {
                    !it.surfaceAttached && it.windowCount == 0
                }
                mark("entered_no_surface drawCount=${absent.drawCount} windowCount=${absent.windowCount}")
                assertFalse(state(scenario).resumed)
                if (document != null && !delayedTrim) {
                    val beforeDelivery = state(scenario)
                    scenario.onActivity { it.acceptDiagnosticDocument(document.uri) }
                    val pending = state(scenario)
                    assertTrue("Paused delivery must remain pending", pending.pending)
                    assertFalse("Paused delivery must not start decoding", pending.loading)
                    assertEquals("Paused callback must not consume input", beforeDelivery.epoch, pending.epoch)
                    assertFalse(pending.surfaceAttached)
                    mark("delivery_pending state=$pending")
                }
                // No readiness wait here: keep onResume -> foreground(true) -> loadPendingDocument.
                mark("resume_requested")
                scenario.moveToState(Lifecycle.State.RESUMED)
                if (!dng) {
                    val resumed = awaitSnapshot(renderer, "surface-only resumed frame") {
                        it.drawCount > absent.drawCount && it.windowCount == 1 && it.surfaceAttached && it.allocatedBytes > 0
                    }
                    assertEquals("Same retained synthetic input allocation", active.allocatedBytes, resumed.allocatedBytes)
                    assertFalse(state(scenario).gpuError)
                }
            } else {
                // A read-only observation after the real observer published to DevelopExecution.
                // Never obtains a trial admission lease, updates conditions, or bypasses a blocker.
                await("real foreground observer publication") { state(scenario).publishedReady }
                mark("foreground_publication_ready")
                scenario.onActivity { it.acceptDiagnosticDocument(checkNotNull(document).uri) }
            }
            if (document != null) {
                if (delayedTrim) {
                    // This is a separate ordering probe, not the immediate paused-delivery case.
                    // Wait for actual publication so admission failure does not hide late UI_HIDDEN.
                    await("real resumed observer publication") { state(scenario).publishedReady }
                    scenario.onActivity { it.acceptDiagnosticDocument(document.uri) }
                    await("new resumed load reaches controlled readonly provider") {
                        check(state(scenario).inputResult != "load_error") { "DNG failed before late-trim probe" }
                        document.openEntered.count == 0L
                    }
                    val loading = state(scenario)
                    assertTrue(loading.resumed && loading.loading)
                    mark("late_ui_hidden_delivery loadEpoch=${loading.epoch} resumed=${loading.resumed}")
                    try {
                        scenario.onActivity { it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
                    } finally { document.allowOpen.countDown() }
                    await("original new-load completion after late UI_HIDDEN", 60_000) {
                        state(scenario).completedEpoch == loading.epoch
                    }
                    val afterTrim = state(scenario)
                    mark("late_ui_hidden_completed state=$afterTrim")
                    assertEquals("Delayed UI_HIDDEN must not cancel the new resumed input", loading.epoch, afterTrim.epoch)
                }
                await("native DNG result", 60_000) {
                    val current = state(scenario)
                    check(current.inputResult != "load_error") {
                        "Original DNG load failed; inspect admission and EGL traces without inferring the cause"
                    }
                    check(!current.gpuError) { "Original GPU error displayed" }
                    current.inputResult == "loaded" && !current.loading
                }
                // The resume frame might still have used the initial synthetic input. Request a new
                // frame through actual UI only after native input was accepted, like the Files test.
                val afterLoad = snapshot(renderer).drawCount
                await("rotation control after DNG load") {
                    device.findObject(By.pkg(target.packageName).text("回転 0°"))?.let { it.click(); true }
                        ?: run { scroll(); false }
                }
                awaitSnapshot(renderer, "new DNG frame after UI rotation") {
                    it.drawCount > afterLoad && it.windowCount == 1 && it.surfaceAttached && it.allocatedBytes > 0
                }
                await("displayed native DNG dimensions") {
                    if (device.hasObject(By.pkg(target.packageName).text(EXPECTED_STATUS))) true
                    else { scroll(Direction.UP); false }
                }
                if (delayedTrim) {
                    // The earlier UI_HIDDEN must not affect a selection started after that notice.
                    val previousEpoch = state(scenario).epoch
                    scenario.onActivity { it.acceptDiagnosticDocument(document.uri) }
                    await("new selection after the earlier UI_HIDDEN", 60_000) {
                        val current = state(scenario)
                        check(current.inputResult != "load_error" && !current.gpuError)
                        current.epoch > previousEpoch && current.inputResult == "loaded" && !current.loading
                    }
                    val secondLoadedDraw = snapshot(renderer).drawCount
                    await("rotation control after second DNG load") {
                        device.findObject(By.pkg(target.packageName).text("回転 90°"))?.let { it.click(); true }
                            ?: run { scroll(); false }
                    }
                    awaitSnapshot(renderer, "new frame after the earlier UI_HIDDEN") {
                        it.drawCount > secondLoadedDraw && it.windowCount == 1 && it.surfaceAttached
                    }
                }
                document.verifyClosedAndUnchanged()
            }
            state(scenario)
            mark("result_success")
            captureEvidence("completed")
        } catch (caught: Throwable) {
            failure = caught
            mark("result_failure ${GpuSurfaceProbe.failureSummary(caught)}")
            // Preserve the live failing UI before pause/close cancels or replaces it.
            runCatching { captureEvidence("failure") }.exceptionOrNull()?.let(caught::addSuppressed)
        } finally {
            provider?.allowOpen?.countDown()
            fun cleanup(action: () -> Unit) {
                try { action() } catch (caught: Throwable) {
                    if (failure == null) {
                        failure = caught
                        runCatching { captureEvidence("cleanup-failure") }.exceptionOrNull()?.let(caught::addSuppressed)
                    } else failure!!.addSuppressed(caught)
                }
            }
            cleanup { scenario?.moveToState(Lifecycle.State.CREATED) }
            if (provider != null) cleanup {
                GpuDiagnosticDngInput.release(target).get(30, TimeUnit.SECONDS)
                provider.verifyClosedAndUnchanged()
                // Retain the owned synthetic source on failure, never discard first-failure evidence.
                if (failure == null) assertTrue("Owned synthetic fixture cleanup", provider.source.delete())
            }
            cleanup { scenario?.close() }
        }
        failure?.let { throw it }
    }

    private fun state(scenario: ActivityScenario<GpuPreviewDiagnosticActivity>): GpuPreviewDiagnosticActivity.DiagnosticState {
        val value = AtomicReference<GpuPreviewDiagnosticActivity.DiagnosticState>()
        scenario.onActivity { value.set(it.diagnosticState()) }
        return checkNotNull(value.get()).also { lastState = it }
    }

    private fun snapshot(renderer: GpuPreviewRenderer): GpuPreviewSnapshot = awaitSnapshot(renderer, "GL snapshot") { true }

    private fun awaitSnapshot(renderer: GpuPreviewRenderer, label: String,
        condition: (GpuPreviewSnapshot) -> Boolean): GpuPreviewSnapshot {
        val value = AtomicReference<GpuPreviewSnapshot>()
        await(label) {
            renderer.snapshot { value.set(it) }
            value.get()?.let { lastSnapshot = it; condition(it) } == true
        }
        return checkNotNull(value.get())
    }

    private fun await(label: String, boundMs: Long = 10_000, condition: () -> Boolean) {
        waitingFor = label
        conditionBoundMs = boundMs
        mark("wait label=$label boundMs=$boundMs")
        val deadline = SystemClock.elapsedRealtime() + boundMs
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out: $label (boundMs=$boundMs)")
    }

    private fun scroll(direction: Direction = Direction.DOWN) {
        device.findObject(By.pkg(target.packageName).scrollable(true))?.scroll(direction, 0.5f)
    }

    private fun mark(line: String) = Log.i(TAG, "tNs=${SystemClock.elapsedRealtimeNanos()} $line")

    private fun captureEvidence(stage: String) {
        val folder = File(evidence, stage).apply { check(mkdirs() || isDirectory) }
        var failure: Throwable? = null
        fun collect(action: () -> Unit) {
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
        }
        collect { File(folder, "state.txt").writeText("stage=$stage\nwaitingFor=$waitingFor\n" +
            "conditionBoundMs=$conditionBoundMs\nstate=$lastState\nsnapshot=$lastSnapshot\n") }
        collect { device.dumpWindowHierarchy(File(folder, "window.xml")) }
        collect { assertTrue("UI screenshot capture failed", device.takeScreenshot(File(folder, "screen.png"))) }
        // Local evidence only: never publish raw UI/logs or a source file. No log buffer is cleared.
        collect { File(folder, "logcat.txt").writeText(device.executeShellCommand(
            "logcat -d -v brief -t 2000 GpuSurfaceProbe:D GpuSurfaceTest:I DngInputProbe:D CinematicRaw:I AndroidRuntime:E *:S")) }
        failure?.let { throw it }
    }

    private class SyntheticDocument(context: Context, holdOpen: Boolean) : ContentProvider() {
        val openEntered = CountDownLatch(1)
        val allowOpen = CountDownLatch(if (holdOpen) 1 else 0)
        val source = File.createTempFile("gpu-surface-input-", ".dng", context.cacheDir).apply {
            outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
            check(setReadOnly())
        }
        val uri: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
            "primary:DCIM/Synthetic/gpu-input.dng")
        private val fingerprint = DngContentVerifier.identify(source.length(), { source.inputStream() })
        val sha256 get() = fingerprint.sha256
        private val descriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()
        private val writes = AtomicInteger()
        init {
            assertEquals(6530L, fingerprint.bytes)
            assertEquals("c2bea28b28d64a08df77443ce2b9eb3083e670c1fa295465907c76f60d4a4926", fingerprint.sha256)
            attachInfo(context, ProviderInfo().apply {
                authority = "com.android.externalstorage.documents"; exported = false
            })
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/x-adobe-dng"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            check(uri == this.uri)
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply { addRow(Array<Any?>(columns.size) { index ->
                when (columns[index]) {
                    OpenableColumns.DISPLAY_NAME -> "Synthetic GPU input.dng"
                    OpenableColumns.SIZE -> source.length()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1L
                    else -> null
                }
            }) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(uri == this.uri && mode == "r")
            openEntered.countDown()
            check(allowOpen.await(10, TimeUnit.SECONDS)) { "Controlled provider release timed out" }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes.incrementAndGet(); error("No writes") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            writes.incrementAndGet(); error("No writes")
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No writes") }
        fun verifyClosedAndUnchanged() {
            assertTrue("Completed loader retains no provider descriptor", descriptors.none { it.fileDescriptor.valid() })
            assertEquals(0, writes.get())
            DngContentVerifier.verify(fingerprint, { source.inputStream() })
        }
    }

    private companion object {
        const val TAG = "GpuSurfaceTest"
        const val EXPECTED_STATUS = "DNG AS_SHOT入力: 64 × 48（長辺1024・786,432画素以下）"
    }
}
