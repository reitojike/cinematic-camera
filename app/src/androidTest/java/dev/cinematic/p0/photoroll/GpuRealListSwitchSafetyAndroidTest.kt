package dev.cinematic.p0.photoroll

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.jpegrender.original.GpuExportBoundaryFixture
import dev.cinematic.p0.jpegrender.original.GpuExportBoundaryWitness
import dev.cinematic.p0.jpegrender.original.Original24GpuOutput
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoEditParameter
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Product list/back/editor/FGS; only private storage and host composition are test-isolated.
 * No selectedUri injection, permission grant, CPU comparison or production hook.
 * Not a test of PhotoRollActivity's own dependency-construction wiring. */
class GpuRealListSwitchSafetyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val ui = SyntheticFixtureRule()

    @Test fun realListSwitchKeepsAAndRequiresExplicitBSave() =
        GpuExportBoundaryFixture.run(ordinaryActivity = true) { f ->
            val app = f.app
            cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
                check(app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) {
                    "Required existing permission missing: $it"
                }
            }
            check(ForegroundExports.gate.active() == null)
            val a = f.sources.single()
            val b = f.createSource()
            assertNotEquals(a.fingerprint, b.fingerprint)
            val nameA = displayName(app, a)
            val nameB = displayName(app, b)
            val recipes = AndroidPhotoEditRecipes(app)
            recipes.save(a, A_VALUES).get(30, TimeUnit.SECONDS)
            recipes.save(b, B_VALUES).get(30, TimeUnit.SECONDS)
            val env = ListEnvironment(f)
            val barrier = Barrier()
            var primary: Throwable? = null
            try {
                env.install()
                ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                    click(nameA)
                    await("A displayed and ready") { readyRecipe(A_VALUES) }
                    awaitSurfacePixels(scenario)
                    val saveA = requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(a))
                    f.handles += saveA
                    assertRecipe(saveA, a, A_VALUES)
                    assertNull(f.journal(saveA).read())
                    assertNull(f.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                    val requestA = saveA.request
                    Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
                        try {
                            click(SAVE)
                            assertTrue(barrier.entered.await(60, TimeUnit.SECONDS))
                            val band = requireNotNull(barrier.event.get())
                            assertEquals(256, band.rows)
                            assertTrue(band.balance.bytes > 0 && band.balance.textures > 0)
                            val ticket = requireNotNull(ForegroundExports.gate.active())
                            assertTrue(ticket.started)
                            assertSame(saveA, ticket.value.identity)
                            f.assertWorkingSource(saveA)
                            val intentA = requireNotNull(f.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))

                            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                            await("product list after Back") { action(nameB) != null }
                            click(nameB)
                            await("B recipe presented while A holds band") {
                                find { it.text?.toString() == nameB } != null &&
                                    recipeLabels(B_VALUES) && noDirtyMarker()
                            }
                            awaitSurfacePixels(scenario)
                            assertSame(ticket, ForegroundExports.gate.active())
                            assertFalse(ticket.stopRequested)
                            assertEquals(requestA, saveA.request)
                            f.assertWorkingSource(saveA)
                            assertNull(f.journal(saveA).read())
                            assertArrayEquals(intentA, f.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                            assertNull(action(OPEN))
                            assertEquals(B_VALUES, recipes.read(b))

                            barrier.release.countDown()
                            await("A publication with B still ready", 180_000) {
                                saveA.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED &&
                                    ForegroundExports.gate.active() == null && readyRecipe(B_VALUES)
                            }
                            barrier.witness.assertReleased()
                            f.verifyPublication(saveA)
                            assertEquals(requestA, saveA.request)
                            assertArrayEquals(intentA, f.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                        } finally { barrier.release.countDown() }
                    }
                    assertNotNull(find { it.text?.toString() == nameB })
                    assertNull(find { it.text?.toString() == nameA })
                    assertNull("A result must not become B result", action(OPEN))
                    awaitSurfacePixels(scenario)
                    val saveB = requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(b))
                    f.handles += saveB
                    assertNotSame(saveA, saveB)
                    assertRecipe(saveB, b, B_VALUES)
                    assertEquals(ManualOriginalTrialSavePhase.READY, saveB.state.value.phase)
                    assertNull(saveB.state.value.uri)
                    assertNull(f.journal(saveB).read())
                    assertNull(f.intent(saveB).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                    assertNotEquals(saveA.request.captureId, saveB.request.captureId)
                    val requestB = saveB.request
                    val witnessB = GpuExportBoundaryWitness()
                    Original24GpuOutput.observeForDiagnostics(witnessB).use {
                        click(SAVE)
                        await("explicit B publication", 180_000) {
                            saveB.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED &&
                                ForegroundExports.gate.active() == null
                        }
                        witnessB.assertReleased()
                    }
                    f.verifyPublication(saveB)
                    assertEquals(requestB, saveB.request)
                    assertNotEquals(saveA.state.value.uri, saveB.state.value.uri)
                    assertRecipe(saveA, a, A_VALUES)
                    assertRecipe(saveB, b, B_VALUES)
                    f.verifyOriginals()
                }
            } catch (t: Throwable) {
                primary = t
                throw t
            } finally {
                barrier.release.countDown()
                env.closeOwned(primary)
            }
        }

    private inner class ListEnvironment(private val f: GpuExportBoundaryFixture) {
        private val app = f.app
        private val publications = MutableStateFlow(GpuInputPublication())
        private val observer = PhotoRollResourceObserver(app, DevelopExecution::updateConditions,
            onPublication = { value -> publications.update { GpuInputPublication(it.sequence + 1, value) } })
        private val renderer = ManualTrialPhotoRollRenderer(app, observer::snapshot, developEngine = DevelopEngine.GPU)
        private val repository = PhotoRollRepository(app.contentResolver, app.packageName)
        private var camera: PhotoRollCameraOwners.Owner? = null

        fun install() {
            instrumentation.runOnMainSync {
                camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
            }
            ui.content = {
                val host = requireNotNull(LocalActivity.current) as PhotoRollActivity
                val context = remember(host) { object : ContextWrapper(host) {
                    override fun getApplicationContext(): Context = app
                    override fun getNoBackupFilesDir(): File = app.noBackupFilesDir
                    override fun getCacheDir(): File = app.cacheDir
                    override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(name, mode)
                    override fun getContentResolver() = app.contentResolver
                } }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val state by lifecycle.currentStateFlow.collectAsState()
                DisposableEffect(lifecycle) {
                    val listener = LifecycleEventObserver { _, event -> when (event) {
                        Lifecycle.Event.ON_START -> observer.start()
                        Lifecycle.Event.ON_RESUME -> observer.foreground(true)
                        Lifecycle.Event.ON_PAUSE -> observer.foreground(false)
                        Lifecycle.Event.ON_STOP -> observer.stop()
                        else -> Unit
                    } }
                    lifecycle.addObserver(listener)
                    onDispose { lifecycle.removeObserver(listener); observer.stop() }
                }
                CompositionLocalProvider(LocalContext provides context) {
                    CaptureTheme {
                        PhotoRollScreen(repository, renderer, state.isAtLeast(Lifecycle.State.RESUMED),
                            publications, host.previewTrimEpoch,
                            importConditions = { observer.snapshot().developConditions(rendererAvailable = true) },
                            close = host::finish)
                    }
                }
            }
        }

        fun closeOwned(primary: Throwable?) {
            var secondary: Throwable? = null
            fun cleanup(block: () -> Unit) {
                try { block() } catch (t: Throwable) {
                    val previous = primary ?: secondary
                    if (previous == null) secondary = t else if (previous !== t) previous.addSuppressed(t)
                }
            }
            var mayDrain = true
            cleanup {
                ForegroundExports.gate.active()?.let { ticket ->
                    val handle = ticket.value.identity as? AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
                    if (handle == null || f.sources.none { handle.matches(it) }) {
                        mayDrain = false
                        error("Do not cancel an unrelated save")
                    }
                    if (handle !in f.handles) f.handles += handle
                    ForegroundExports.cancel(ticket.value)
                }
            }
            if (mayDrain) cleanup {
                await("owned foreground save drained", 180_000) { ForegroundExports.gate.active() == null }
            }
            cleanup { renderer.discardTransientInputs() }
            cleanup { GpuPhotoRollRetiredOwners.releaseBeforeOpen() }
            cleanup { AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS) }
            cleanup { f.handles.distinct().forEach { f.assertClosed(it) } }
            cleanup { observer.stop() }
            cleanup { instrumentation.runOnMainSync { camera?.close() } }
            // No media/files are deleted here. Fixture retains failure evidence and performs
            // its verified, URI-specific cleanup only when this whole body succeeds.
            if (primary == null) secondary?.let { throw it }
        }
    }

    private class Barrier {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val event = AtomicReference<GpuExportBoundaryWitness.Event>()
        val witness = GpuExportBoundaryWitness { value ->
            if (value.complete && value.stage == "B1" && value.origin == 256 && event.compareAndSet(null, value)) {
                entered.countDown()
                check(release.await(90, TimeUnit.SECONDS)) { "A active band hold expired" }
            }
        }
    }

    private fun assertRecipe(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle,
        source: SharedOriginalReference, values: PhotoEditValues) {
        assertEquals(source, save.request.source)
        assertEquals(values.strength, save.appliedStrength, 0.0)
        assertEquals(PhotoAdjustmentPolicy.adjustments(values), save.appliedAdjustments)
    }
    private fun displayName(app: Context, source: SharedOriginalReference): String = requireNotNull(
        app.contentResolver.query(Uri.parse(source.uri), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
    ).use { check(it.count == 1 && it.moveToFirst()); it.getString(0) }
    private fun readyRecipe(values: PhotoEditValues) = action(SAVE) != null && recipeLabels(values) && noDirtyMarker()
    private fun recipeLabels(values: PhotoEditValues) = PhotoEditParameter.entries.all { key ->
        val label = editValueLabel(key, requireNotNull(values[key]))
        find { it.text?.toString()?.lineSequence()?.any { line -> line.trim() == label } == true } != null
    }
    private fun noDirtyMarker() = find {
        val text = it.text?.toString().orEmpty()
        text.contains("指定 · 未反映") || text.contains("表示中の値なし")
    } == null

    /** Polls only documented no-data readiness; other PixelCopy errors fail immediately. */
    private fun awaitSurfacePixels(scenario: ActivityScenario<PhotoRollActivity>) {
        await("real product Surface pixels", 15_000) {
            var surface: SurfaceView? = null
            fun locate(view: View) {
                if (view is SurfaceView && view.isShown && view.width > 0 && view.height > 0) {
                    check(surface == null) { "More than one visible preview Surface" }
                    surface = view
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) locate(view.getChildAt(i))
            }
            scenario.onActivity { locate(it.window.decorView) }
            val view = surface ?: return@await false
            if (!view.holder.surface.isValid) return@await false
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                val done = CountDownLatch(1)
                var result = -1
                PixelCopy.request(view, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue("PixelCopy callback", done.await(10, TimeUnit.SECONDS))
                if (result == PixelCopy.ERROR_SOURCE_NO_DATA) return@await false
                assertEquals(PixelCopy.SUCCESS, result)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                pixels.toSet().size > 8
            } finally { bitmap.recycle() }
        }
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (predicate(node)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        return visit(ui.fixtureRoot())
    }
    private fun action(label: String): AccessibilityNodeInfo? {
        var node = find { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (node != null && !node.isClickable && node.isEnabled) node = node.parent
        return node?.takeIf { it.refresh() && it.isVisibleToUser && it.isClickable && it.isEnabled }
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await(label) { action(label).also { node = it } != null }
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, timeoutMs: Long = 60_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            instrumentation.waitForIdleSync()
            if (predicate()) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        ui.failure(label)
        error("Real-list GPU save did not reach: $label")
    }
    private companion object {
        const val SAVE = "JPEGを保存"
        const val OPEN = "保存したJPEGを開く（開く前に照合します）"
        val A_VALUES = PhotoEditValues(.65, 20.0, .3)
        val B_VALUES = PhotoEditValues(.35, -30.0, -.4)
    }
}
