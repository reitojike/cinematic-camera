package dev.cinematic.p0.photoroll

import android.app.ActivityManager
import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import androidx.compose.ui.platform.LocalContext
import android.content.ComponentCallbacks2
import android.content.res.AssetManager
import android.os.SystemClock
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.gpu.preview.GPU_INPUT_READINESS_TIMEOUT_MS
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
class GpuActiveSaveOwnershipSafetyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val ui = SyntheticFixtureRule()

    @Test fun repeatedDuplicateStartsThenUiCancellationStopActualServiceAndGpuOwners() {
        repeat(2) {
            withEditor { fixture, _, _ ->
                val barrier = BandBarrier()
                Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
                    try {
                        click("JPEGを保存")
                        val save = awaitActiveBand(fixture, barrier)
                        val ticket = requireNotNull(ForegroundExports.gate.active())
                        val intent = requireNotNull(fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                        instrumentation.runOnMainSync {
                            repeat(3) {
                                fixture.app.startForegroundService(Intent(fixture.app, ForegroundExportService::class.java)
                                    .setAction(ForegroundExportService.START)
                                    .putExtra(ForegroundExportService.TOKEN, ticket.token))
                            }
                        }
                        instrumentation.waitForIdleSync()
                        assertSame(ticket, ForegroundExports.gate.active())
                        assertSame(save, ticket.value.identity)
                        click("保存を取り消す")
                        assertTrue(ticket.stopRequested)
                        barrier.release.countDown()
                        await("cancelled foreground export drains") { ForegroundExports.gate.active() == null }
                        assertEquals(ManualOriginalTrialSavePhase.CANCELLED, save.state.value.phase)
                        assertNull(save.state.value.uri)
                        fixture.assertClosed(save, unpublished = true)
                        barrier.witness.assertReleased()
                        fixture.verifyOriginals()
                        assertArrayEquals(intent, fixture.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                        val manager = requireNotNull(fixture.app.getSystemService(ActivityManager::class.java))
                        @Suppress("DEPRECATION")
                        fun serviceRunning() = manager.getRunningServices(100).any {
                            it.service.className == ForegroundExportService::class.java.name
                        }
                        await("actual FGS stops before another explicit save") { !serviceRunning() }
                    } finally { barrier.release.countDown() }
                }
            }
        }
    }
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
        var nextSave: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        SwitchTrace(env).use { trace ->
        Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
            try {
                click("JPEGを保存")
                val saveA = awaitActiveBand(fixture, barrier)
                trace.save.set(saveA)
                trace.mark("a-active-band")
                val ticket = requireNotNull(ForegroundExports.gate.active())
                val requestA = saveA.request
                assertEquals(sourceA, requestA.source)
                val oldPreview = env.owners.single()
                assertPreviewClosed(oldPreview)
                trace.mark("select-b-before")
                instrumentation.runOnMainSync { env.selected = sourceB.uri }
                trace.mark("select-b-after")
                await("new photo selected before old export completes") { find { it.text?.toString() == B_NAME } != null }
                assertSame(ticket, ForegroundExports.gate.active())
                assertFalse(ticket.stopRequested)
                assertNull(fixture.journal(saveA).read())
                assertNull(action("保存したJPEGを開く（開く前に照合します）"))
                // ForegroundSaveNotice is intentionally process-wide, including after source changes.
                assertNotNull("The global notice still controls A's active export", action("保存を取り消す"))
                assertSame(ticket, ForegroundExports.gate.active())
                assertSame(saveA, ticket.value.identity)
                assertEquals(sourceA.fingerprint, saveA.request.source.fingerprint)
                assertNotEquals(sourceB.fingerprint, saveA.request.source.fingerprint)
                fixture.assertWorkingSource(saveA)
                assertEquals(B_VALUES, AndroidPhotoEditRecipes(fixture.app).read(sourceB))
                if (InstrumentationRegistry.getArguments().getString("gpuExportSwitchContended") == "true") {
                    // Controlled A contention, not an extension of B's admission/test deadlines.
                    val until = SystemClock.elapsedRealtime() + GPU_INPUT_READINESS_TIMEOUT_MS + 1_000
                    trace.mark("begin-fixed-a-contention")
                    while (SystemClock.elapsedRealtime() < until) {
                        assertSame(ticket, ForegroundExports.gate.active())
                        assertFalse(ticket.stopRequested)
                        assertNull(fixture.journal(saveA).read())
                        Thread.sleep(100)
                    }
                    val preparationError = find {
                        it.text?.toString() == "入力の準備を確認できません。再試行してください"
                    } != null
                    trace.mark("end-fixed-a-contention-bPreparationError=$preparationError")
                }
                // This must happen BEFORE A is released: completion-after-release is not concurrency.
                await("B presents its own recipe while A remains at B1", 30_000) {
                    env.owners.lastOrNull()?.let {
                        it.source == sourceB.uri && it.session.presented.value == B_VALUES
                    } == true
                }
                assertSame(ticket, ForegroundExports.gate.active())
                assertTrue(saveA.hasUnreleasedResources)
                assertNull(fixture.journal(saveA).read())
                assertNull("B save authority is still queued behind A", action("JPEGを保存"))
                trace.mark("b-presented-before-a-release")
                trace.mark("release-a-band")
                barrier.release.countDown()
                await("old export finishes after photo switch", 180_000) { ForegroundExports.gate.active() == null }
                trace.mark("a-gate-cleared")
                fixture.verifyPublication(saveA)
                trace.mark("a-publication-verified")
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
                assertNull("Old publication must not appear in new editor", action("保存したJPEGを開く（開く前に照合します）"))
                assertNull(action("プレビューを再開"))
                val saveB = AndroidManualOriginalTrialSaves.prepare(fixture.app, sourceB, B_VALUES.strength,
                    GpuExportBoundaryFixture.ALLOWED, PhotoAdjustmentPolicy.adjustments(B_VALUES))
                    .get(60, TimeUnit.SECONDS).also { fixture.handles += it }
                nextSave = saveB
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
        // A's single-attempt observer is gone; B gets its own real GPU witness.
        val saveB = requireNotNull(nextSave)
        val bWitness = GpuExportBoundaryWitness()
        Original24GpuOutput.observeForDiagnostics(bWitness).use {
            click("JPEGを保存")
            await("B real GPU publication and FGS closure", 180_000) {
                saveB.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED &&
                    ForegroundExports.gate.active() == null
            }
            fixture.verifyPublication(saveB)
            bWitness.assertReleased()
            val publishedB = requireNotNull(fixture.journal(saveB).read())
            val proofB = publishedB.render as ManualOriginalTrialRender
            assertEquals(sourceB, proofB.request.source)
            assertEquals(saveB.request.recipe, proofB.request.recipe)
            val saveA = fixture.handles.first()
            assertNotEquals(saveA.state.value.uri, saveB.state.value.uri)
            assertNotEquals(saveA.request.source.fingerprint, proofB.request.source.fingerprint)
            fixture.verifyPublication(saveA)
            fixture.verifyOriginals()
        }
    }

    @Test fun reselectingActiveAClosesNewPreviewAndRequiresExplicitResumeAfterPublication() = withEditor { fixture, env, _ ->
        val sourceA = fixture.sources.first()
        val sourceB = fixture.createSource()
        AndroidPhotoEditRecipes(fixture.app).save(sourceB, B_VALUES).get(30, TimeUnit.SECONDS)
        val originalA = env.owners.single()
        val barrier = BandBarrier()
        Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
            try {
                click("JPEGを保存")
                val saveA = awaitActiveBand(fixture, barrier)
                val ticket = requireNotNull(ForegroundExports.gate.active())
                instrumentation.runOnMainSync { env.selected = sourceB.uri }
                await("B displays during A save", 30_000) {
                    env.owners.lastOrNull()?.let {
                        it.source == sourceB.uri && it.session.presented.value == B_VALUES
                    } == true
                }
                val ownerB = env.owners.last()
                val countBeforeReturn = env.owners.size
                val inputOpens = java.util.concurrent.atomic.AtomicInteger()
                GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                    override fun preflightOpened() { inputOpens.incrementAndGet() }
                }).use {
                    instrumentation.runOnMainSync { env.selected = sourceA.uri }
                    await("reselected A creates a distinct editor owner") {
                        env.owners.size == countBeforeReturn + 1 && env.owners.last().source == sourceA.uri
                    }
                    val returnedA = env.owners.last()
                    assertNotSame(originalA.session, returnedA.session)
                    assertNotSame(ownerB.session, returnedA.session)
                    assertPreviewClosed(returnedA)
                    assertPreviewClosed(ownerB)
                    assertNull(returnedA.session.presented.value)
                    assertEquals("Same active A must not prepare input again", 0, inputOpens.get())
                    assertSame(ticket, ForegroundExports.gate.active())
                    assertSame(saveA, ticket.value.identity)
                    assertFalse(ticket.stopRequested)
                    assertNotNull(action("保存を取り消す"))
                    assertNull(action("JPEGを保存"))
                    assertNull(action("プレビューを再開"))
                    assertNull(action("保存したJPEGを開く（開く前に照合します）"))
                    barrier.release.countDown()
                    await("A publishes while its reselected preview stays closed", 180_000) {
                        ForegroundExports.gate.active() == null
                    }
                    fixture.verifyPublication(saveA)
                    barrier.witness.assertReleased()
                    await("A offers explicit resume after publication") { action("プレビューを再開") != null }
                    assertEquals(countBeforeReturn + 1, env.owners.size)
                    assertNull(returnedA.session.presented.value)
                    assertPreviewClosed(returnedA)
                    assertEquals("Publication must not trigger implicit input open", 0, inputOpens.get())
                    click("プレビューを再開")
                    await("Explicit resume creates and presents fresh A", 30_000) {
                        env.owners.size == countBeforeReturn + 2 && env.owners.last().let {
                            it.source == sourceA.uri && it.session.presented.value == A_VALUES
                        }
                    }
                    assertNotSame(returnedA.session, env.owners.last().session)
                    assertTrue("Explicit resume must check the original again", inputOpens.get() > 0)
                    fixture.verifyPublication(saveA)
                    fixture.verifyOriginals()
                }
            } finally { barrier.release.countDown() }
        }
    }

    @Test fun switchingDisplayedBToCKeepsAExportAndClosesB() = interruptedPreview(PreviewInterruption.SWITCH)
    @Test fun backgroundAfterBPresentationClosesPreviewWithoutCancellingA() = interruptedPreview(PreviewInterruption.BACKGROUND)
    @Test fun contextLossOnDisplayedBDoesNotCancelOrReopenA() = interruptedPreview(PreviewInterruption.CONTEXT_LOSS)
    @Test fun contextLossOnBFirstDrawDoesNotCancelOrReopenA() = interruptedPreview(PreviewInterruption.FIRST_DRAW_LOSS)

    @Test fun switchingBWhilePreflightOwnsSnapshotDrainsOnlyBAndKeepsA() = interruptedPreparation(false)
    @Test fun backgroundWhileBPreflightOwnsSnapshotDrainsOnlyBAndKeepsA() = interruptedPreparation(true)

    /** Hold a real B snapshot/FD before native preflight, not a delay guessed from UI text. */
    private fun interruptedPreparation(background: Boolean) = withEditor { fixture, env, scenario ->
        val sourceB = fixture.createSource()
        val sourceC = if (background) null else fixture.createSource()
        val valuesC = PhotoEditValues(.25, 10.0, .1)
        val recipes = AndroidPhotoEditRecipes(fixture.app)
        recipes.save(sourceB, B_VALUES).get(30, TimeUnit.SECONDS)
        sourceC?.let { recipes.save(it, valuesC).get(30, TimeUnit.SECONDS) }
        val barrier = BandBarrier()
        val preflight = CountDownLatch(1)
        val releaseInput = CountDownLatch(1)
        val inputClosed = CountDownLatch(1)
        val first = AtomicBoolean(true)
        Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
            try {
                click("JPEGを保存")
                val saveA = awaitActiveBand(fixture, barrier)
                val ticket = requireNotNull(ForegroundExports.gate.active())
                val intentA = requireNotNull(fixture.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                    override fun preflightOpened() {
                        if (first.compareAndSet(true, false)) {
                            preflight.countDown()
                            check(releaseInput.await(30, TimeUnit.SECONDS)) { "B preflight test barrier expired" }
                        }
                    }
                    override fun released() { inputClosed.countDown() }
                }).use {
                    try {
                        instrumentation.runOnMainSync { env.selected = sourceB.uri }
                        assertTrue("B must own a real snapshot while A exports", preflight.await(30, TimeUnit.SECONDS))
                        val ownerB = env.owners.last()
                        assertEquals(sourceB.uri, ownerB.source)
                        assertNull(ownerB.session.presented.value)
                        assertEquals(1L, inputClosed.count)
                        if (background) scenario.moveToState(Lifecycle.State.CREATED)
                        else {
                            instrumentation.runOnMainSync { env.selected = requireNotNull(sourceC).uri }
                            await("C selection disposes B before releasing its input", 10_000) {
                                env.owners.lastOrNull()?.source == sourceC?.uri
                            }
                        }
                        assertPreviewClosed(ownerB)
                        // Cancellation is not closure while the real snapshot/FD is still retained.
                        assertEquals(1L, inputClosed.count)
                        releaseInput.countDown()
                        assertTrue("B snapshot/FD/token must all close", inputClosed.await(30, TimeUnit.SECONDS))
                        assertPreviewClosed(ownerB)
                        assertNull("Stale B input must never present", ownerB.session.presented.value)
                        if (!background) await("C presents after B input drains", 30_000) {
                            env.owners.lastOrNull()?.let {
                                it.source == sourceC?.uri && it.session.presented.value == valuesC
                            } == true
                        }
                        assertSame(ticket, ForegroundExports.gate.active())
                        assertFalse(ticket.stopRequested)
                        assertTrue(saveA.hasUnreleasedResources)
                        assertNull(fixture.journal(saveA).read())
                        assertArrayEquals(intentA, fixture.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                        fixture.assertWorkingSource(saveA)
                    } finally { releaseInput.countDown() }
                }
                barrier.release.countDown()
                await("A publishes after B preparation cancellation", 180_000) { ForegroundExports.gate.active() == null }
                fixture.verifyPublication(saveA)
                barrier.witness.assertReleased()
                fixture.verifyOriginals()
            } finally { releaseInput.countDown(); barrier.release.countDown() }
        }
    }

    /** B has no GL context during preflight: lose A's active export context at this boundary. */
    @Test fun exportContextLossWhileBPreflightIsHeldDoesNotReleaseBInput() =
        withEditor(expectedGpuFailures = 1, expectedReason = GpuSaveFailureReason.CONTEXT_LOST) { fixture, env, _ ->
            val sourceB = fixture.createSource()
            AndroidPhotoEditRecipes(fixture.app).save(sourceB, B_VALUES).get(30, TimeUnit.SECONDS)
            val barrier = BandBarrier(failAfterRelease = true)
            val preflight = CountDownLatch(1)
            val releaseInput = CountDownLatch(1)
            val inputClosed = CountDownLatch(1)
            Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
                try {
                    click("JPEGを保存")
                    val saveA = awaitActiveBand(fixture, barrier)
                    val requestA = saveA.request
                    val intentA = requireNotNull(fixture.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                    GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                        override fun preflightOpened() {
                            preflight.countDown()
                            check(releaseInput.await(60, TimeUnit.SECONDS)) { "B retained input barrier expired" }
                        }
                        override fun released() { inputClosed.countDown() }
                    }).use {
                        try {
                            instrumentation.runOnMainSync { env.selected = sourceB.uri }
                            assertTrue(preflight.await(30, TimeUnit.SECONDS))
                            val ownerB = env.owners.last()
                            assertEquals(sourceB.uri, ownerB.source)
                            assertNull(ownerB.session.presented.value)
                            barrier.release.countDown()
                            await("A context failure closes only export", 30_000) {
                                saveA.state.value.phase == ManualOriginalTrialSavePhase.GPU_FAILED &&
                                    ForegroundExports.gate.active() == null
                            }
                            assertEquals(GpuSaveFailureReason.CONTEXT_LOST, saveA.state.value.gpuFailure?.reason)
                            fixture.assertClosed(saveA, unpublished = true)
                            barrier.witness.assertReleased()
                            assertEquals(requestA, saveA.request)
                            assertArrayEquals(intentA, fixture.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                            assertEquals("A release must not release B's retained snapshot/token", 1L, inputClosed.count)
                            assertNull(ownerB.session.failure.value)
                            releaseInput.countDown()
                            assertTrue(inputClosed.await(30, TimeUnit.SECONDS))
                            await("B displays and prepares its own save after A failure", 30_000) {
                                ownerB.session.presented.value == B_VALUES && action("JPEGを保存") != null
                            }
                            val saveB = AndroidManualOriginalTrialSaves.prepare(fixture.app, sourceB, B_VALUES.strength,
                                GpuExportBoundaryFixture.ALLOWED, PhotoAdjustmentPolicy.adjustments(B_VALUES))
                                .get(30, TimeUnit.SECONDS).also { fixture.handles += it }
                            assertEquals(sourceB, saveB.request.source)
                            assertEquals(ManualOriginalTrialSavePhase.READY, saveB.state.value.phase)
                            assertNull(fixture.journal(saveB).read())
                            assertNull(fixture.intent(saveB).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                            assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, saveA.state.value.phase)
                            fixture.verifyOriginals()
                        } finally { releaseInput.countDown() }
                    }
                } finally { releaseInput.countDown(); barrier.release.countDown() }
            }
        }

    private enum class PreviewInterruption { SWITCH, BACKGROUND, CONTEXT_LOSS, FIRST_DRAW_LOSS }

    /** Real B input/render and A export. Context loss is an injected engine exception, not OS loss.
     * First-draw loss and presented-B loss are distinct; neither claims loss during native decode. */
    private fun interruptedPreview(interruption: PreviewInterruption) = withEditor { fixture, env, scenario ->
        val sourceB = fixture.createSource()
        AndroidPhotoEditRecipes(fixture.app).save(sourceB, B_VALUES).get(30, TimeUnit.SECONDS)
        val barrier = BandBarrier()
        Original24GpuOutput.observeForDiagnostics(barrier.witness).use {
            try {
                click("JPEGを保存")
                val saveA = awaitActiveBand(fixture, barrier)
                val ticket = requireNotNull(ForegroundExports.gate.active())
                val intentA = requireNotNull(fixture.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                if (interruption == PreviewInterruption.FIRST_DRAW_LOSS) env.failNextPreview.set(true)
                instrumentation.runOnMainSync { env.selected = sourceB.uri }
                await("B first draw during A export", 30_000) {
                    env.owners.lastOrNull()?.let {
                        it.source == sourceB.uri && if (interruption == PreviewInterruption.FIRST_DRAW_LOSS)
                            it.session.failure.value is GpuContextLostException else it.session.presented.value == B_VALUES
                    } == true
                }
                val ownerB = env.owners.last()
                when (interruption) {
                    PreviewInterruption.SWITCH -> {
                        val sourceC = fixture.createSource()
                        val valuesC = PhotoEditValues(.25, 10.0, .1)
                        AndroidPhotoEditRecipes(fixture.app).save(sourceC, valuesC).get(30, TimeUnit.SECONDS)
                        instrumentation.runOnMainSync { env.selected = sourceC.uri }
                        await("C replaces B without waiting for A", 30_000) {
                            env.owners.lastOrNull()?.let {
                                it.source == sourceC.uri && it.session.presented.value == valuesC
                            } == true
                        }
                        assertPreviewClosed(ownerB)
                    }
                    PreviewInterruption.BACKGROUND -> {
                        scenario.moveToState(Lifecycle.State.CREATED)
                        assertPreviewClosed(ownerB)
                    }
                    PreviewInterruption.CONTEXT_LOSS, PreviewInterruption.FIRST_DRAW_LOSS -> {
                        if (interruption == PreviewInterruption.CONTEXT_LOSS) {
                            env.failNextPreview.set(true)
                            ownerB.session.submit(PhotoEditValues(.45, -30.0, -.4))
                        } else assertNull(ownerB.session.presented.value)
                        await("B reports injected context loss") { ownerB.session.failure.value is GpuContextLostException }
                        val snapshot = previewSnapshot(ownerB)
                        assertEquals(0L, snapshot.allocatedBytes)
                        assertEquals(0, snapshot.windowCount)
                        ownerB.ledger.get()?.let { assertEquals(0, it.textureCount) }
                        val count = env.owners.size
                        ownerB.session.submit(B_VALUES)
                        assertEquals(snapshot.drawCount, previewSnapshot(ownerB).drawCount)
                        assertEquals("Failure must not create another session", count, env.owners.size)
                        ownerB.session.close()
                        assertPreviewClosed(ownerB)
                    }
                }
                assertSame(ticket, ForegroundExports.gate.active())
                assertFalse(ticket.stopRequested)
                assertTrue(saveA.hasUnreleasedResources)
                assertNull(fixture.journal(saveA).read())
                assertArrayEquals(intentA, fixture.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                fixture.assertWorkingSource(saveA)
                barrier.release.countDown()
                await("A publishes despite B interruption", 180_000) { ForegroundExports.gate.active() == null }
                fixture.verifyPublication(saveA)
                barrier.witness.assertReleased()
                assertArrayEquals(intentA, fixture.intent(saveA).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                assertEquals(B_VALUES, AndroidPhotoEditRecipes(fixture.app).read(sourceB))
                assertPreviewClosed(ownerB)
                fixture.verifyOriginals()
            } finally { barrier.release.countDown() }
        }
    }

    private fun previewSnapshot(owner: PreviewOwner): GpuPreviewSnapshot {
        val values = LinkedBlockingQueue<GpuPreviewSnapshot>()
        owner.session.renderer.snapshot { values.offer(it) }
        return requireNotNull(values.poll(10, TimeUnit.SECONDS))
    }

    /** Test-only sampling; diagnostic overhead is evidence, not a performance measurement.
     * Never touches admission, queue ordering or retry/timeout values. */
    private class SwitchTrace(private val env: EditorEnvironment) : AutoCloseable {
        val save = AtomicReference<AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle>()
        private val enabled = InstrumentationRegistry.getArguments().getString("gpuExportSwitchTrace") == "true"
        private val executor = if (enabled) Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "gpu-switch-test-trace").apply { isDaemon = true }
        } else null
        private val started = SystemClock.elapsedRealtime()
        init { executor?.scheduleAtFixedRate({ sample("sample", SystemClock.elapsedRealtime()) }, 0, 2, TimeUnit.SECONDS) }
        fun mark(stage: String) {
            if (!enabled) return
            val eventAt = SystemClock.elapsedRealtime()
            try { executor?.execute { sample(stage, eventAt) } } catch (_: Throwable) { /* Diagnostics cannot replace evidence. */ }
        }
        private fun sample(stage: String, eventAt: Long) {
            val now = SystemClock.elapsedRealtime()
            try {
                val current = save.get()
                val publication = env.publications.value
                val owners = env.owners.mapIndexed { index, owner ->
                    "owner$index(surface=${owner.session.renderer.surfaceAttached.value}," +
                        "presented=${owner.session.presented.value},failure=${owner.session.failure.value?.javaClass?.simpleName})"
                }.joinToString(";")
                val stacks = Thread.getAllStackTraces().entries.filter { (thread, frames) ->
                    thread.name == "auto-develop" || frames.any {
                        it.className.endsWith("ManualTrialPhotoRollRenderer") && it.methodName == "openGpu"
                    }
                }.joinToString(";") { (thread, frames) ->
                    "${thread.name}:${thread.state}:" + frames.take(24).joinToString("|") {
                        "${it.className}.${it.methodName}:${it.lineNumber}"
                    }
                }
                output("eventAt=$eventAt observedAt=$now elapsed=${now - started} stage=$stage " +
                    "gateActive=${ForegroundExports.gate.active() != null} " +
                    "aPhase=${current?.state?.value?.phase} aHeld=${current?.hasUnreleasedResources} " +
                    "publication=${publication.sequence}:${publication.observation.cameraState}:" +
                    "${AutoDevelopPolicy.previewBlockers(publication.observation.conditions)} $owners stacks=$stacks")
            } catch (failure: Throwable) {
                output("eventAt=$eventAt observedAt=$now stage=$stage diagnosticFailure=${failure.javaClass.simpleName}")
            }
        }
        private fun output(value: String) {
            try { InstrumentationRegistry.getInstrumentation().sendStatus(0,
                Bundle().apply { putString("stream", "\nGpuSwitchTrace $value\n") }) }
            catch (_: Throwable) { /* Never replace a test failure with a reporting failure. */ }
        }
        override fun close() {
            mark("trace-close")
            executor?.shutdown()
            try { if (executor?.awaitTermination(5, TimeUnit.SECONDS) == false) executor.shutdownNow() }
            catch (_: InterruptedException) { executor?.shutdownNow(); Thread.currentThread().interrupt() }
        }
    }
    private class BandBarrier(private val failAfterRelease: Boolean = false) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val event = AtomicReference<GpuExportBoundaryWitness.Event>()
        val witness = GpuExportBoundaryWitness { current ->
            if (current.complete && current.stage == "B1" && current.origin == 256 && event.compareAndSet(null, current)) {
                entered.countDown()
                check(release.await(90, TimeUnit.SECONDS)) { "Active GPU boundary barrier expired" }
                if (failAfterRelease) throw GpuContextLostException("Synthetic export loss while B prepares")
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
        val failNextPreview = AtomicBoolean()
        val publications = MutableStateFlow(GpuInputPublication())
        val observer = PhotoRollResourceObserver(fixture.app, DevelopExecution::updateConditions,
            onPublication = { observation -> publications.update { GpuInputPublication(it.sequence + 1, observation) } })
        val renderer = ManualTrialPhotoRollRenderer(fixture.app, observer::snapshot, developEngine = DevelopEngine.GPU)
        private val repository = PhotoRollRepository(fixture.app.contentResolver, fixture.app.packageName)
        fun install() {
            ui.content = {
                val host = requireNotNull(LocalActivity.current) as PhotoRollActivity
                val uiContext = remember(host) { object : ContextWrapper(host) {
                    override fun getApplicationContext(): Context = fixture.app
                    override fun getNoBackupFilesDir(): File = fixture.app.noBackupFilesDir
                    override fun getCacheDir(): File = fixture.app.cacheDir
                    override fun getSharedPreferences(name: String, mode: Int) = fixture.app.getSharedPreferences(name, mode)
                    override fun getContentResolver() = fixture.app.contentResolver
                } }
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
                key(selected) { CompositionLocalProvider(LocalContext provides uiContext) {
                    CaptureTheme {
                        GpuPhotoRollEditor(repository, renderer, selected, false,
                            if (selected == fixture.sources.first().uri) A_NAME else B_NAME,
                            state.isAtLeast(Lifecycle.State.RESUMED), publications, host.previewTrimEpoch, {},
                            Modifier.fillMaxSize().safeDrawingPadding(), createSession = ::newSession)
                    }
                } }
            }
        }
        private fun newSession(assets: AssetManager): GpuPhotoRollSession {
            val ledger = AtomicReference<GpuMemoryLedger>()
            return GpuPhotoRollSession(assets) { egl, memory ->
                check(ledger.compareAndSet(null, memory))
                val real = DefaultStageClearGpu(egl, memory)
                object : StageClearGpu by real {
                    override fun renderPreview(photo: StageClearGpuPhoto, params: GpuRenderParams,
                        target: GpuPreviewTarget, display: GpuDisplayOptions): GpuFrameStats {
                        if (failNextPreview.compareAndSet(true, false))
                            throw GpuContextLostException("Synthetic concurrent-preview context loss")
                        return real.renderPreview(photo, params, target, display)
                    }
                }
            }.also { owners += PreviewOwner(selected, it, ledger) }
        }
    }

    private fun withEditor(expectedGpuFailures: Long = 0, expectedReason: GpuSaveFailureReason? = null,
        body: (GpuExportBoundaryFixture, EditorEnvironment, ActivityScenario<PhotoRollActivity>) -> Unit) =
        GpuExportBoundaryFixture.run(expectedGpuFailures, expectedReason, ordinaryActivity = true) { fixture ->
            check(ForegroundExports.gate.active() == null)
            var camera: PhotoRollCameraOwners.Owner? = null
            val env = EditorEnvironment(fixture)
            var primaryFailure: Throwable? = null
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
                primaryFailure = error
                fixture.reportFailure("editor-failure-before-owner-cleanup", error)
                throw error
            } finally {
                var retained = primaryFailure
                fun cleanup(action: () -> Unit) {
                    try { action() } catch (failure: Throwable) {
                        val primary = retained
                        if (primary == null) retained = failure
                        else if (primary !== failure) primary.addSuppressed(failure)
                    }
                }
                cleanup {
                    ForegroundExports.gate.active()?.let { ticket ->
                        val save = ticket.value.identity as? AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
                        // Only cancel this test's source, never an unrelated active save.
                        if (save?.request?.source in fixture.sources) {
                            if (save != null && save !in fixture.handles) fixture.handles += save
                            ForegroundExports.cancel(ticket.value)
                        }
                    }
                }
                env.owners.forEach { owner -> cleanup { owner.session.close() } }
                env.owners.forEach { owner -> cleanup { assertPreviewClosed(owner) } }
                cleanup { GpuPhotoRollRetiredOwners.releaseBeforeOpen() }
                cleanup { env.renderer.discardTransientInputs() }
                cleanup { AndroidManualOriginalTrialSaves.discardPreview(fixture.app).get(60, TimeUnit.SECONDS) }
                cleanup { await("test FGS cleanup") { ForegroundExports.gate.active() == null } }
                cleanup { env.observer.stop() }
                cleanup { instrumentation.runOnMainSync { camera?.close() } }
                // Cleanup-only failure fails the test; otherwise keep the original assertion primary.
                if (primaryFailure == null) retained?.let { throw it }
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
