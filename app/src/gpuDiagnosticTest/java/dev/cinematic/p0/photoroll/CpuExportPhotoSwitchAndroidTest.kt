package dev.cinematic.p0.photoroll

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.gpu.GpuAvailability
import dev.cinematic.p0.gpu.preview.GPU_INPUT_READINESS_TIMEOUT_MS
import dev.cinematic.p0.jpegrender.original.GpuExportBoundaryFixture
import dev.cinematic.p0.jpegrender.original.GpuExportBoundaryWitness
import dev.cinematic.p0.jpegrender.original.Original24GpuOutput
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditParameter
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #347 CPU comparison, using the SAME synthetic DNG generator and publication proof as GPU.
 * The Activity and its PhotoRollScreen are unchanged: selection uses the real list/back actions.
 * No injected keyed editor, fake renderer/save owner, extra retry, or production hook is used.
 *
 * The existing queue seam holds A after FGS acceptance but BEFORE renderer admission. This is
 * not the GPU test's active B1 band boundary. After release, A and B must both really render and
 * publish. The fixed hold spans the GPU input preparation budget; it does not extend any deadline.
 * gpuDiagnostic is only the isolated APK/fixture host. CPU is explicitly selected before opening
 * the Activity and remains selected through both saves; the GPU export witness must stay empty.
 * Its CPU diagnostic preview defaults to one worker, the same serial calculation as normal CPU.
 */
@RunWith(AndroidJUnit4::class)
class CpuExportPhotoSwitchAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // content remains null: this rule observes the real Activity, it does not replace setContent.
    @get:Rule val ui = SyntheticFixtureRule()

    @Test fun acceptedCpuSaveAllowsRealListSwitchAndBothPhotosPublish() {
        val app = instrumentation.targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        // Fail prerequisites instead of silently skipping a requested comparison.
        check(BuildConfig.BUILD_TYPE == "gpuDiagnostic" && app.packageName == "dev.cinematic.p0.gpudiagnostic")
        check(args.getString("isolated") == "true" && args.getString("gpuExportBoundaries") == "true")
        check(args.getString("cpuPhotoSwitch") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Physical devices are excluded" }
        GpuExportBoundaryFixture.run { fixture ->
            // run() checks the shared GPU-capable comparison environment and owns safe cleanup.
            // Its initial setting is changed BEFORE any preview or save is prepared.
            assertTrue(fixture.setting.select(DevelopEngine.CPU, GpuAvailability.Checking))
            assertEquals(DevelopEngine.CPU, fixture.setting.current())
            val sourceA = fixture.sources.single()
            val sourceB = fixture.createSource()
            val nameA = displayName(sourceA)
            val nameB = displayName(sourceB)
            assertNotEquals(sourceA.fingerprint, sourceB.fingerprint)
            val recipes = AndroidPhotoEditRecipes(app)
            recipes.save(sourceA, A_VALUES).get(30, TimeUnit.SECONDS)
            recipes.save(sourceB, B_VALUES).get(30, TimeUnit.SECONDS)
            cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
                instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it)
            }
            var camera: PhotoRollCameraOwners.Owner? = null
            val gpu = GpuExportBoundaryWitness()
            Original24GpuOutput.observeForDiagnostics(gpu).use {
                var primaryFailure: Throwable? = null
                try {
                    check(ForegroundExports.gate.active() == null)
                    instrumentation.runOnMainSync {
                        camera = AndroidPhotoRollCameraObservations.register {
                            PhotoRollCameraSnapshot(false, false, false, false)
                        }
                    }
                    ActivityScenario.launch(PhotoRollActivity::class.java).use {
                        click(nameA)
                        await("A restored CPU preview", 120_000) { readyRecipe(A_VALUES) }
                        val saveA = prepare(fixture, sourceA, A_VALUES)
                        assertEquals(ManualOriginalTrialSavePhase.READY, saveA.state.value.phase)
                        val requestA = saveA.request
                        val entered = CountDownLatch(1)
                        val release = CountDownLatch(1)
                        val barrier = AndroidAutoDevelopQueue.get(app).manualOriginalIo {
                            entered.countDown()
                            check(release.await(90, TimeUnit.SECONDS)) { "CPU accepted-export barrier expired" }
                        }
                        var barrierFailure: Throwable? = null
                        try {
                            assertTrue(entered.await(10, TimeUnit.SECONDS))
                            click("JPEGを保存")
                            await("A foreground export accepted") { ForegroundExports.gate.active()?.started == true }
                            val ticket = requireNotNull(ForegroundExports.gate.active())
                            assertSame(saveA, ticket.value.identity)
                            assertFalse(ticket.stopRequested)
                            assertNull(fixture.journal(saveA).read())
                            emit("A_ACCEPTED_BEFORE_RENDER", "source=A phase=${saveA.state.value.phase} held=${saveA.hasUnreleasedResources}")
                            // Production back/list selection, not a direct mutableState change.
                            click("一覧へ戻る")
                            click(nameB)
                            await("B editor selected while A is unpublished") {
                                find { it.text?.toString() == nameB } != null && action("一覧へ戻る") != null
                            }
                            val switchedAt = SystemClock.elapsedRealtime()
                            emit("B_SELECTED_A_UNPUBLISHED", "gateIsA=${ForegroundExports.gate.active() === ticket}")
                            // Controlled contention, not a performance measurement or a timeout relaxation.
                            while (SystemClock.elapsedRealtime() - switchedAt < GPU_INPUT_READINESS_TIMEOUT_MS + 1_000) {
                                assertSame(ticket, ForegroundExports.gate.active())
                                assertFalse(ticket.stopRequested)
                                assertNull(fixture.journal(saveA).read())
                                assertNull(action("JPEGを保存"))
                                Thread.sleep(100)
                            }
                            assertEquals(B_VALUES, recipes.read(sourceB))
                            assertNull(action("保存したJPEGを照合して開く"))
                            assertNotNull(action("保存を取り消す"))
                            emit("RELEASE_A_QUEUE", "BWaitMs=${SystemClock.elapsedRealtime() - switchedAt}")
                        } catch (error: Throwable) {
                            barrierFailure = error
                            throw error
                        } finally {
                            release.countDown()
                            try { barrier.get(30, TimeUnit.SECONDS) }
                            catch (cleanup: Throwable) {
                                val primary = barrierFailure
                                if (primary == null) throw cleanup
                                retainFailure(primary, cleanup)
                            }
                        }
                        await("A real CPU publication and FGS closure", 180_000) { ForegroundExports.gate.active() == null }
                        fixture.verifyPublication(saveA)
                        assertEquals(requestA, saveA.request)
                        assertTrue("CPU A export must not execute GPU bands", gpu.events.isEmpty() && gpu.released.isEmpty())
                        emit("A_CPU_PUBLISHED", "source=A phase=${saveA.state.value.phase} held=${saveA.hasUnreleasedResources}")
                        // No Back, retry, resume, or second selection after A completes.
                        await("B automatically becomes saveable with its persisted recipe", 120_000) { readyRecipe(B_VALUES) }
                        assertNotNull(find { it.text?.toString() == nameB })
                        assertNull(find { it.text?.toString() == nameA })
                        assertNull(action("保存したJPEGを照合して開く"))
                        val saveB = prepare(fixture, sourceB, B_VALUES)
                        assertNotSame(saveA, saveB)
                        assertNotEquals(requestA.captureId, saveB.request.captureId)
                        assertEquals(ManualOriginalTrialSavePhase.READY, saveB.state.value.phase)
                        assertNull(fixture.journal(saveB).read())
                        assertNull("B must not save implicitly", fixture.intent(saveB).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                        emit("B_CPU_READY_WITHOUT_RETRY", "source=B recipe=${saveB.request.recipe}")
                        assertEquals(DevelopEngine.CPU, fixture.setting.current())
                        click("JPEGを保存")
                        await("B real CPU publication and FGS closure", 180_000) {
                            saveB.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED && ForegroundExports.gate.active() == null
                        }
                        fixture.verifyPublication(saveB)
                        assertNotEquals(saveA.state.value.uri, saveB.state.value.uri)
                        assertEquals(B_VALUES, recipes.read(sourceB))
                        assertTrue("Neither save may enter GPU export", gpu.events.isEmpty() && gpu.released.isEmpty())
                        assertEquals(DevelopEngine.CPU, fixture.setting.current())
                        fixture.verifyOriginals()
                        emit("B_CPU_PUBLISHED", "source=B phase=${saveB.state.value.phase} held=${saveB.hasUnreleasedResources} gpuEvents=${gpu.events.size}")
                    }
                } catch (error: Throwable) {
                    primaryFailure = error
                    fixture.reportFailure("cpu-photo-switch-before-cleanup", error)
                    throw error
                } finally {
                    var retained = primaryFailure
                    fun cleanup(action: () -> Unit) {
                        try { action() }
                        catch (failure: Throwable) { retained = retainFailure(retained, failure) }
                    }
                    try {
                        cleanup {
                            ForegroundExports.gate.active()?.let { ticket ->
                                val save = ticket.value.identity as? AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
                                if (save?.request?.source in fixture.sources) ForegroundExports.cancel(ticket.value)
                            }
                        }
                        cleanup { AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS) }
                        cleanup { await("CPU test FGS cleanup") { ForegroundExports.gate.active() == null } }
                    } finally {
                        cleanup { instrumentation.runOnMainSync { camera?.close() } }
                    }
                    // Cleanup-only failures fail the test; otherwise preserve the original failure.
                    if (primaryFailure == null) retained?.let { throw it }
                }
            }
        }
    }

    private fun prepare(fixture: GpuExportBoundaryFixture, source: SharedOriginalReference, values: PhotoEditValues) =
        AndroidManualOriginalTrialSaves.prepare(fixture.app, source, values.strength,
            GpuExportBoundaryFixture.ALLOWED, PhotoAdjustmentPolicy.adjustments(values))
            .get(60, TimeUnit.SECONDS).also {
                fixture.handles += it
                assertEquals(source, it.request.source)
                assertEquals(values.strength, it.appliedStrength, 0.0)
                assertEquals(PhotoAdjustmentPolicy.adjustments(values), it.appliedAdjustments)
            }

    private fun displayName(source: SharedOriginalReference): String = requireNotNull(
        instrumentation.targetContext.contentResolver.query(Uri.parse(source.uri),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)).use {
        check(it.count == 1 && it.moveToFirst())
        it.getString(0)
    }

    private fun readyRecipe(values: PhotoEditValues): Boolean = action("JPEGを保存") != null &&
        PhotoEditParameter.entries.all { key ->
            val label = editValueLabel(key, requireNotNull(values[key]))
            find { it.text?.toString()?.lineSequence()?.any { line -> line.trim() == label } == true } != null
        }

    /** Optional stream diagnostics never replace a test/cleanup outcome. */
    private fun emit(event: String, detail: String, primary: Throwable? = null) {
        try {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nCpuPhotoSwitchEvidence event=$event elapsedMs=${SystemClock.elapsedRealtime()} $detail\n")
            })
        } catch (reporting: Throwable) {
            if (primary != null) retainFailure(primary, reporting)
        }
    }

    private fun retainFailure(primary: Throwable?, additional: Throwable): Throwable {
        if (primary == null) return additional
        if (additional !== primary) primary.addSuppressed(additional)
        return primary
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
        val failure = IllegalStateException("CPU photo switch did not reach: $label")
        try { ui.failure(label) }
        catch (reporting: Throwable) { retainFailure(failure, reporting) }
        val labels = mutableListOf<String>()
        try {
            find { node -> (node.contentDescription ?: node.text)?.toString()?.let { if (labels.size < 80) labels += it }; false }
        } catch (reporting: Throwable) { retainFailure(failure, reporting) }
        emit("FAILED", "$label ui=${labels.joinToString(" | ")}", failure)
        throw failure
    }

    companion object {
        private val A_VALUES = PhotoEditValues(.65, 20.0, .3)
        private val B_VALUES = PhotoEditValues(.35, -30.0, -.4)
    }
}
