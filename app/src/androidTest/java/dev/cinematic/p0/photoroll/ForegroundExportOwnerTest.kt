package dev.cinematic.p0.photoroll

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.syntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Empty emulator only: generated DNG, real native render, retained handle, journal and publisher.
 * Initial UI observations are controlled; the Service and render use real resource observations.
 * Two cases exercise Compose Save/cancel; photographic quality/performance is not assessed. */
@RunWith(AndroidJUnit4::class)
class ForegroundExportOwnerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ready = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
        PhotoRollCameraSnapshot(false, false, false, false), true, true)
    private val allowed get() = ready.developConditions(true)

    @Before fun isolatedOnly() {
        check(!unsettledFixture) { "Previous synthetic fixture has unconfirmed resources; stop this suite" }
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") && Build.VERSION.SDK_INT >= 29)
    }

    @Test fun screenSavePublishesInBackgroundAndReturnShowsConfirmedOwner() = screenSave(false)
    @Test fun screenCancelAfterReturnWorksBeforePreviewReloadCompletes() = screenSave(true)

    private fun screenSave(cancelAfterReturn: Boolean) {
        cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        foreground { scenario -> Fixture().use { f ->
            f.initialize(.5, PhotoAdjustments.NONE)
            f.screen = scenario
            scenario.recreate() // The real list now observes this fixture's newly inserted source.
            click(f.name)
            action("JPEGを保存")
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val barrier = AndroidAutoDevelopQueue.get(context).manualOriginalIo {
                entered.countDown(); check(release.await(120, TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                click("JPEGを保存") // Real PhotoRollScreen callback, not an injected save action.
                await { ForegroundExports.gate.active()?.started == true }
                val ticket = ForegroundExports.gate.active()
                assertSame(f.handle, requireNotNull(ticket).value.identity)
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(ticket.stopRequested)
                if (cancelAfterReturn) {
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    // Preview is queued behind the barrier; the process banner must still allow cancel.
                    click("保存を取り消す")
                    await { ticket.stopRequested }
                    assertSame(ticket, ForegroundExports.gate.active())
                }
            } finally { release.countDown(); barrier.get(30, TimeUnit.SECONDS) }
            await { ForegroundExports.gate.active() == null }
            if (cancelAfterReturn) {
                assertEquals(ManualOriginalTrialSavePhase.READY, f.handle.state.value.phase)
                assertNull(f.journal().read())
                assertNull(f.storage(CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                action("JPEGを保存") // Ready UI is not a publication receipt or an automatic retry.
                assertEquals(f.beforeJpegs, jpegCount())
            } else {
                assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, f.handle.state.value.phase)
                scenario.moveToState(Lifecycle.State.RESUMED)
                action("保存したJPEGを照合して開く")
                assertEquals(f.beforeJpegs + 1, jpegCount())
            }
            f.verifyOriginal()
            f.screenVerified = true
        } }
    }

    private fun action(label: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (!node.refresh()) return null // Resume can leave UiAutomation's old Save node cached.
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        var result: AccessibilityNodeInfo? = null
        var attempts = 0
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.className == "android.widget.ScrollView" && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
            return false
        }
        try { await {
            instrumentation.waitForIdleSync()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            result = node?.takeIf { it.isEnabled && it.isVisibleToUser }
            // Compose can omit off-screen semantics entirely until its container scrolls.
            if ((node == null || !node.isVisibleToUser) && ++attempts % 10 == 0) scroll(instrumentation.uiAutomation.rootInActiveWindow)
            result != null
        } } catch (failure: IllegalStateException) {
            val labels = mutableListOf<String>()
            fun describe(node: AccessibilityNodeInfo?) {
                if (node == null || labels.size >= 80) return
                (node.contentDescription ?: node.text)?.toString()?.let { labels += "$it [enabled=${node.isEnabled}, visible=${node.isVisibleToUser}]" }
                for (i in 0 until node.childCount) describe(node.getChild(i))
            }
            describe(instrumentation.uiAutomation.rootInActiveWindow)
            throw AssertionError("Missing enabled visible action: $label; synthetic screen: ${labels.joinToString(" | ")}", failure)
        }
        return requireNotNull(result)
    }
    private fun click(label: String) { assertTrue(action(label).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }

    @Test fun acceptedSavePublishesSameRecipeAfterActivityStopsAndRetryDoesNotDuplicate() = foreground { scenario ->
        Fixture().use { f ->
            f.initialize()
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val barrier = AndroidAutoDevelopQueue.get(context).manualOriginalIo {
                entered.countDown(); check(release.await(120, TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { assertTrue(f.ui.startForegroundSave(context)) }
                await { ForegroundExports.gate.active()?.started == true }
                assertEquals(PhotoRollSavePhase.SAVING, f.ui.snapshot.phase)
                // Duplicate UI activation must neither allocate nor replace the current save.
                val ticket = ForegroundExports.gate.active()
                instrumentation.runOnMainSync { assertTrue(f.ui.startForegroundSave(context)) }
                assertSame(ticket, ForegroundExports.gate.active())
                scenario.moveToState(Lifecycle.State.CREATED)
            } finally { release.countDown(); barrier.get(30, TimeUnit.SECONDS) }
            await { ForegroundExports.gate.active() == null }
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, f.handle.state.value.phase)
            val record = requireNotNull(f.journal().read())
            val render = record.render as ManualOriginalTrialRender
            assertEquals(f.handle.request.source, render.request.source)
            assertEquals(f.handle.request.recipe, render.request.recipe)
            assertEquals(f.handle.appliedStrength, render.appliedStrength, 0.0)
            assertEquals(f.handle.appliedAdjustments, render.adjustments)
            assertEquals(f.beforeJpegs + 1, jpegCount())
            DngContentVerifier.verify(record.fingerprint, { context.contentResolver.openInputStream(Uri.parse(record.uri!!))!! })
            f.verifyOriginal()
            assertFalse(f.handle.hasUnreleasedResources)
            scenario.moveToState(Lifecycle.State.RESUMED)
            DevelopExecution.updateConditions(allowed)
            val same = f.recheck().get(60, TimeUnit.SECONDS)
            assertEquals(record.uri, same.uri)
            assertEquals(f.beforeJpegs + 1, jpegCount())
        }
    }

    @Test fun captureBeforeQueuedAdmissionDoesNotCreateIntentOrAutomaticallyRestart() = foreground {
        Fixture().use { f ->
            f.initialize()
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val barrier = AndroidAutoDevelopQueue.get(context).manualOriginalIo {
                entered.countDown(); check(release.await(60, TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { assertTrue(f.ui.startForegroundSave(context)) }
                await { ForegroundExports.gate.active()?.started == true }
                val capture = DevelopExecution.reserveCapture()
                DevelopExecution.releaseCapture(capture)
            } finally { release.countDown(); barrier.get(30, TimeUnit.SECONDS) }
            await { ForegroundExports.gate.active() == null }
            assertEquals(ManualOriginalTrialSavePhase.READY, f.handle.state.value.phase)
            assertNull(f.journal().read())
            assertNull(f.storage(CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            assertEquals(f.beforeJpegs, jpegCount())
            assertFalse(f.handle.hasUnreleasedResources)
            f.verifyOriginal()
        }
    }

    private fun foreground(block: (ActivityScenario<PhotoRollActivity>) -> Unit) {
        var observation: PhotoRollCameraOwners.Owner? = null
        instrumentation.runOnMainSync {
            observation = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
        }
        try { ActivityScenario.launch(PhotoRollActivity::class.java).use(block) }
        finally { instrumentation.runOnMainSync { observation?.close() } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) { check(System.nanoTime() < deadline) { "Synthetic export did not reach expected state" }; Thread.sleep(25) }
    }

    private inner class Fixture : AutoCloseable {
        private val resolver = context.contentResolver
        private lateinit var uri: Uri
        private lateinit var source: SharedOriginalReference
        lateinit var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
        lateinit var ui: ManualTrialJpegSaveOwner
        private var preparation: java.util.concurrent.CompletableFuture<AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle>? = null
        private var rechecking: java.util.concurrent.CompletableFuture<ManualOriginalTrialSaveState>? = null
        var screen: ActivityScenario<PhotoRollActivity>? = null
        var screenVerified = false
        private var previewCleanup: java.util.concurrent.CompletableFuture<Unit>? = null
        val name = "synthetic_export_${UUID.randomUUID()}.dng"
        val beforeJpegs = jpegCount()
        // Invoked inside use: partial initialization also has a cleanup owner.
        fun initialize(strength: Double = .6, adjustments: PhotoAdjustments = PhotoAdjustments(.2, .3)) {
            uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            val bytes = syntheticTrialDng()
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            source = SharedOriginalReference(uri.toString(), DngContentVerifier.identify(bytes.size.toLong(), { resolver.openInputStream(uri)!! }), OriginalGrant.APP_OWNED)
            DevelopExecution.updateConditions(allowed)
            preparation = AndroidManualOriginalTrialSaves.prepare(context, source, strength, allowed, adjustments)
            handle = requireNotNull(preparation).get(60, TimeUnit.SECONDS)
            ui = ManualTrialJpegSaveOwner(handle) { ready }
        }
        fun storage(kind: CaptureJournalFile) = AndroidAtomicJournalStorage(
            File(context.noBackupFilesDir, "raw-captures/${handle.request.captureId}/renders"), handle.request.generation.toString(), kind)
        fun journal() = JpegPublicationJournal(handle.request.captureId, handle.request.generation, storage(CaptureJournalFile.JPEG_PUBLICATION))
        fun verifyOriginal() = DngContentVerifier.verify(source.fingerprint, { resolver.openInputStream(uri)!! })
        fun recheck() = handle.advance(allowed) { false }.also { rechecking = it }
        override fun close() {
            try {
                // Screen checks/readback have their own lifetime, separate from the export handle.
                screen?.moveToState(Lifecycle.State.CREATED)
                if (::ui.isInitialized) ui.cancelForegroundSave()
                // Do not cancel the future or delete input while its queued preflight could still read it.
                preparation?.let { handle = it.get(60, TimeUnit.SECONDS) }
                rechecking?.get(60, TimeUnit.SECONDS)
                if (screen != null) {
                    previewCleanup = AndroidManualOriginalTrialSaves.discardPreview(context)
                    requireNotNull(previewCleanup).get(60, TimeUnit.SECONDS)
                    // A failed UI wait cannot prove a separate checkOriginal reader has returned.
                    check(screenVerified) { "Retain source after an unconfirmed Screen read" }
                }
                await { ForegroundExports.gate.active() == null }
                if (::handle.isInitialized) {
                    check(!handle.hasUnreleasedResources) { "Retain synthetic artifacts until resources close" }
                    journal().read()?.uri?.let { check(resolver.delete(Uri.parse(it), null, null) == 1) }
                }
                if (::source.isInitialized) verifyOriginal()
                if (::uri.isInitialized) check(resolver.delete(uri, null, null) == 1)
                // Keep private journal evidence; each fixture has a unique source and never reuses it.
            } catch (failure: Throwable) {
                unsettledFixture = true
                throw IllegalStateException("Synthetic cleanup unconfirmed; retain artifacts and stop remaining tests", failure)
            }
        }
    }

    private fun jpegCount() = context.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        arrayOf(MediaStore.Images.Media._ID), "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.MIME_TYPE}=?",
        arrayOf(context.packageName, "image/jpeg"), null)!!.use { it.count }

    companion object { @Volatile private var unsettledFixture = false }
}
