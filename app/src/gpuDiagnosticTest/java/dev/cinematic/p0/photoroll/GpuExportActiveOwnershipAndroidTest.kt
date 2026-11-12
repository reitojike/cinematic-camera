package dev.cinematic.p0.photoroll

import android.content.ComponentCallbacks2
import android.content.res.AssetManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #289: real editor/FGS/export at a named active GPU band, using small synthetic DNGs.
 * Activity trim callbacks and keyed source selection are driven by the test, not OS memory
 * pressure or PhotoRoll list gestures. Actual factory/FGS/publication is not substituted.
 */
@RunWith(AndroidJUnit4::class)
class GpuExportActiveOwnershipAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val ui = SyntheticFixtureRule()

    @Test fun activeGpuExportSurvivesPreviewTrimCallbacksWithoutReopeningPreview() = withEditor { fixture, env, scenario ->
        val barrier = BandBarrier()
        Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
            try {
                click("JPEGを保存")
                val save = awaitActiveBand(fixture, barrier)
                val ticket = requireNotNull(ForegroundExports.gate.active())
                val firstPreview = env.owners.single()
                assertPreviewClosed(firstPreview)
                val request = save.request
                val intent = requireNotNull(fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                var initialEpoch = -1
                scenario.onActivity {
                    initialEpoch = it.previewTrimEpoch
                    it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
                    assertEquals(initialEpoch, it.previewTrimEpoch)
                }
                for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)) {
                    scenario.onActivity { it.onTrimMemory(level) }
                    instrumentation.waitForIdleSync()
                    assertSame(ticket, ForegroundExports.gate.active())
                    assertFalse(ticket.stopRequested)
                    assertEquals(request, save.request)
                    assertEquals(1, env.owners.size)
                    assertNull(action("プレビューを再開"))
                    assertNotNull(action("保存を取り消す"))
                    assertNull(fixture.journal(save).read())
                    assertArrayEquals(intent, fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                }
                scenario.onActivity { assertEquals(initialEpoch + 2, it.previewTrimEpoch) }
                barrier.release.countDown()
                await("trimmed export publication and FGS closure", 180_000) { ForegroundExports.gate.active() == null }
                fixture.verifyPublication(save)
                barrier.witness.assertReleased()
                assertEquals(request, save.request)
                assertArrayEquals(intent, fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                assertEquals(1, env.owners.size)
                assertPreviewClosed(firstPreview)
            } finally { barrier.release.countDown() }
        }
    }

    @Test fun oldGpuExportCompletionCannotPublishIntoNewKeyedPhotoEditor() = withEditor { fixture, env, _ ->
        val sourceA = fixture.sources.first()
        val sourceB = fixture.createSource()
        assertNotEquals(sourceA.fingerprint, sourceB.fingerprint)
        AndroidPhotoEditRecipes(fixture.app).save(sourceB, B_VALUES).get(30, TimeUnit.SECONDS)
        val barrier = BandBarrier()
        Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
            try {
                click("JPEGを保存")
                val saveA = awaitActiveBand(fixture, barrier)
                val ticket = requireNotNull(ForegroundExports.gate.active())
                val requestA = saveA.request
                assertEquals(sourceA, requestA.source)
                val oldPreview = env.owners.single()
                assertPreviewClosed(oldPreview)
                instrumentation.runOnMainSync { env.selected = sourceB.uri }
                await("new photo selected before old export completes") { find { it.text?.toString() == B_NAME } != null }
                assertSame(ticket, ForegroundExports.gate.active())
                assertFalse(ticket.stopRequested)
                assertNull(fixture.journal(saveA).read())
                assertNull(action("保存したJPEGを照合して開く"))
                // ForegroundSaveNotice is intentionally process-wide, including after source changes.
                assertNotNull("The global notice still controls A's active export", action("保存を取り消す"))
                assertSame(ticket, ForegroundExports.gate.active())
                assertSame(saveA, ticket.value.identity)
                assertEquals(sourceA.fingerprint, saveA.request.source.fingerprint)
                assertNotEquals(sourceB.fingerprint, saveA.request.source.fingerprint)
                fixture.assertWorkingSource(saveA)
                assertEquals(B_VALUES, AndroidPhotoEditRecipes(fixture.app).read(sourceB))
                barrier.release.countDown()
                await("old export finishes after photo switch", 180_000) { ForegroundExports.gate.active() == null }
                fixture.verifyPublication(saveA)
                // verifyPublication reads back the actual JPEG fingerprint, dimensions and owner.
                // Bind that same publication record to A's immutable original identity, not just a UI name.
                val publicationA = requireNotNull(fixture.journal(saveA).read())
                val renderedA = publicationA.render as ManualOriginalTrialRender
                assertEquals(requestA.captureId, renderedA.request.captureId)
                assertEquals(requestA.generation, renderedA.request.generation)
                assertEquals(requestA.recipe, renderedA.request.recipe)
                assertEquals(publicationA.fingerprint, renderedA.outputFingerprint)
                assertEquals(sourceA, renderedA.request.source)
                assertEquals(sourceA.fingerprint, renderedA.request.source.fingerprint)
                assertNotEquals(sourceB.fingerprint, renderedA.request.source.fingerprint)
                assertEquals(publicationA.uri, saveA.state.value.uri)
                barrier.witness.assertReleased()
                assertEquals(requestA, saveA.request)
                await("new photo presents its own persisted recipe", 120_000) {
                    env.owners.lastOrNull()?.let { it.source == sourceB.uri && it.session.presented.value == B_VALUES } == true &&
                        action("JPEGを保存") != null
                }
                assertEquals(2, env.owners.size)
                assertNotNull(find { it.text?.toString() == B_NAME })
                assertNull(find { it.text?.toString() == A_NAME })
                assertNull("Old publication must not appear in new editor", action("保存したJPEGを照合して開く"))
                assertNull(action("プレビューを再開"))
                val saveB = AndroidManualOriginalTrialSaves.prepare(fixture.app, sourceB, B_VALUES.strength,
                    GpuExportBoundaryFixture.ALLOWED, PhotoAdjustmentPolicy.adjustments(B_VALUES))
                    .get(60, TimeUnit.SECONDS).also { fixture.handles += it }
                assertNotSame(saveA, saveB)
                assertEquals(sourceB, saveB.request.source)
                assertEquals(sourceB.fingerprint, saveB.request.source.fingerprint)
                assertNotEquals(renderedA.request.source.fingerprint, saveB.request.source.fingerprint)
                assertNotEquals(requestA.captureId, saveB.request.captureId)
                assertEquals(B_VALUES.strength, saveB.appliedStrength, 0.0)
                assertEquals(PhotoAdjustmentPolicy.adjustments(B_VALUES), saveB.appliedAdjustments)
                assertEquals(ManualOriginalTrialSavePhase.READY, saveB.state.value.phase)
                assertNull(saveB.state.value.uri)
                assertNull(fixture.journal(saveB).read())
                assertNull("No second save may start implicitly", fixture.intent(saveB).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                assertEquals(B_VALUES, AndroidPhotoEditRecipes(fixture.app).read(sourceB))
                assertPreviewClosed(oldPreview)
                fixture.verifyOriginals()
            } finally { barrier.release.countDown() }
        }
    }

    private class BandBarrier {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val event = AtomicReference<GpuExportBoundaryWitness.Event>()
        val witness = GpuExportBoundaryWitness { current ->
            if (current.complete && current.stage == "B1" && current.origin == 256 && event.compareAndSet(null, current)) {
                entered.countDown()
                check(release.await(90, TimeUnit.SECONDS)) { "Active GPU boundary barrier expired" }
            }
        }
    }
    private fun awaitActiveBand(fixture: GpuExportBoundaryFixture, barrier: BandBarrier): AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle {
        assertTrue("Actual renderer must reach B1's second completed band", barrier.entered.await(60, TimeUnit.SECONDS))
        val event = requireNotNull(barrier.event.get())
        assertEquals(256, event.rows)
        assertTrue(event.balance.bytes > 0 && event.balance.textures > 0)
        val ticket = requireNotNull(ForegroundExports.gate.active())
        assertTrue(ticket.started)
        assertFalse(ticket.stopRequested)
        val save = ticket.value.identity as AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
        fixture.handles += save
        assertEquals(fixture.sources.first(), save.request.source)
        assertTrue(save.hasUnreleasedResources)
        fixture.assertWorkingSource(save)
        return save
    }

    private data class PreviewOwner(val source: String, val session: GpuPhotoRollSession, val ledger: AtomicReference<GpuMemoryLedger>)
    private inner class EditorEnvironment(val fixture: GpuExportBoundaryFixture) {
        var selected by mutableStateOf(fixture.sources.first().uri)
        val owners = CopyOnWriteArrayList<PreviewOwner>()
        val publications = MutableStateFlow(GpuInputPublication())
        val observer = PhotoRollResourceObserver(fixture.app, DevelopExecution::updateConditions,
            onPublication = { observation -> publications.update { GpuInputPublication(it.sequence + 1, observation) } })
        val renderer = ManualTrialPhotoRollRenderer(fixture.app, observer::snapshot, developEngine = DevelopEngine.GPU)
        private val repository = PhotoRollRepository(fixture.app.contentResolver, fixture.app.packageName)
        fun install() {
            ui.content = {
                val host = LocalContext.current as PhotoRollActivity
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val state by lifecycle.currentStateFlow.collectAsState()
                DisposableEffect(lifecycle) {
                    val events = LifecycleEventObserver { _, event -> when (event) {
                        Lifecycle.Event.ON_START -> observer.start()
                        Lifecycle.Event.ON_RESUME -> observer.foreground(true)
                        Lifecycle.Event.ON_PAUSE -> observer.foreground(false)
                        Lifecycle.Event.ON_STOP -> observer.stop()
                        else -> Unit
                    } }
                    lifecycle.addObserver(events)
                    onDispose { lifecycle.removeObserver(events); observer.stop() }
                }
                // Match PhotoRollScreen's key(selectedUri) lifetime, without impersonating a
                // list gesture or changing the production editor/preview implementation.
                key(selected) {
                    CaptureTheme {
                        GpuPhotoRollEditor(repository, renderer, selected, false,
                            if (selected == fixture.sources.first().uri) A_NAME else B_NAME,
                            state.isAtLeast(Lifecycle.State.RESUMED), publications, host.previewTrimEpoch, {},
                            Modifier.fillMaxSize().safeDrawingPadding(), createSession = ::newSession)
                    }
                }
            }
        }
        private fun newSession(assets: AssetManager): GpuPhotoRollSession {
            val ledger = AtomicReference<GpuMemoryLedger>()
            return GpuPhotoRollSession(assets) { egl, memory ->
                check(ledger.compareAndSet(null, memory))
                DefaultStageClearGpu(egl, memory)
            }.also { owners += PreviewOwner(selected, it, ledger) }
        }
    }

    private fun withEditor(body: (GpuExportBoundaryFixture, EditorEnvironment, ActivityScenario<PhotoRollActivity>) -> Unit) =
        GpuExportBoundaryFixture.run { fixture ->
            check(ForegroundExports.gate.active() == null)
            var camera: PhotoRollCameraOwners.Owner? = null
            val env = EditorEnvironment(fixture)
            try {
                instrumentation.runOnMainSync { camera = AndroidPhotoRollCameraObservations.register {
                    PhotoRollCameraSnapshot(false, false, false, false)
                } }
                AndroidPhotoEditRecipes(fixture.app).save(fixture.sources.first(), A_VALUES).get(30, TimeUnit.SECONDS)
                env.install()
                ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                    await("initial GPU editor", 120_000) {
                        action("JPEGを保存") != null && env.owners.singleOrNull()?.session?.presented?.value == A_VALUES
                    }
                    body(fixture, env, scenario)
                }
            } catch (error: Throwable) {
                fixture.reportFailure("editor-failure-before-owner-cleanup", error)
                throw error
            } finally {
                ForegroundExports.gate.active()?.let { ticket ->
                    val save = ticket.value.identity as? AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
                    // Only cancel this test's source, never an unrelated active save.
                    if (save?.request?.source in fixture.sources) {
                        if (save != null && save !in fixture.handles) fixture.handles += save
                        ForegroundExports.cancel(ticket.value)
                    }
                }
                env.owners.forEach { it.session.close() }
                env.owners.forEach(::assertPreviewClosed)
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                env.renderer.discardTransientInputs()
                AndroidManualOriginalTrialSaves.discardPreview(fixture.app).get(60, TimeUnit.SECONDS)
                await("test FGS cleanup") { ForegroundExports.gate.active() == null }
                env.observer.stop()
                instrumentation.runOnMainSync { camera?.close() }
            }
        }

    private fun assertPreviewClosed(owner: PreviewOwner) {
        assertTrue(owner.session.renderer.awaitClosed(10_000))
        owner.ledger.get()?.let { assertEquals(0L, it.allocatedBytes); assertEquals(0, it.textureCount) }
    }
    private fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (matches(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
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
    private fun await(label: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        ui.failure(label)
        error("GPU active ownership did not reach: $label")
    }
    companion object {
        private const val A_NAME = "SYNTHETIC EXPORT A"
        private const val B_NAME = "SYNTHETIC EXPORT B"
        private val A_VALUES = PhotoEditValues(.65, 20.0, .3)
        private val B_VALUES = PhotoEditValues(.35, -30.0, -.4)
    }
}
