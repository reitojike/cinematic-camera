package dev.cinematic.p0.photoroll

import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import androidx.activity.compose.LocalActivity
import dev.cinematic.p0.jpegrender.original.isolatedGpuSaveSafetyApp
import dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup
import dev.cinematic.p0.jpegrender.original.requireOwnedSafetyIdentity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
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
import androidx.test.uiautomator.UiDevice
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.syntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One emulator-only GPU editor -> actual foreground service -> publication integration case.
 * The queue barrier controls timing, not rendering or publication. Real DefaultStageClearGpu and
 * Original24Attempt use the GPU setting throughout. This is not physical quality/performance QA. */
@RunWith(AndroidJUnit4::class)
class GpuSaveLifecycleSafetyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun gpuEditorSaveSurvivesHomeAndReturnWithoutImplicitPreviewRestart() {
        check(BuildConfig.BUILD_TYPE == "debug")
        check(instrumentation.targetContext.packageName == "dev.cinematic.p0")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(InstrumentationRegistry.getArguments().getString("gpuSaveSafety") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 29)
        check(ForegroundExports.gate.active() == null)
        val target = isolatedGpuSaveSafetyApp()
        val preferences = target.getSharedPreferences("capture-probe", Context.MODE_PRIVATE)
        val previousSetting = preferences.getString("develop/engine", null)
        val setting = DevelopEngineSetting(target)
        val owners = CopyOnWriteArrayList<Owner>()
        val serviceStarts = AtomicInteger()
        val closedAtServiceStart = AtomicReference<Boolean>()
        // Observe the real start boundary. The wrapper delegates to Android's actual service;
        // awaitClosed(0) runs off-main and never waits for a still-live preview to finish.
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? {
                assertEquals(ForegroundExportService::class.java.name, service.component?.className)
                closedAtServiceStart.set(CompletableFuture.supplyAsync {
                    owners.isNotEmpty() && owners.all { it.session.renderer.awaitClosed(0) }
                }.get(5, TimeUnit.SECONDS))
                serviceStarts.incrementAndGet()
                return super.startForegroundService(service)
            }
        }
        val publications = MutableStateFlow(GpuInputPublication())
        val observer = PhotoRollResourceObserver(app, DevelopExecution::updateConditions,
            onPublication = { observation -> publications.update { GpuInputPublication(it.sequence + 1, observation) } })
        val renderer = ManualTrialPhotoRollRenderer(app, observer::snapshot, developEngine = DevelopEngine.GPU)
        val repository = PhotoRollRepository(app.contentResolver, app.packageName)
        val queue = AndroidAutoDevelopQueue.get(app)
        val resolver = app.contentResolver
        val sourceName = "synthetic_gpu_save_lifecycle_${UUID.randomUUID()}.dng"
        val values = PhotoEditValues(.65, 20.0, .3)
        var sourceUri: Uri? = null
        var source: SharedOriginalReference? = null
        var outputUri: Uri? = null
        var outputName: String? = null
        var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        var camera: PhotoRollCameraOwners.Owner? = null
        var success = false
        var failure: Throwable? = null
        fun newSession(assets: AssetManager): GpuPhotoRollSession {
            val ledger = AtomicReference<GpuMemoryLedger>()
            val session = GpuPhotoRollSession(assets) { egl, memory ->
                check(ledger.compareAndSet(null, memory))
                DefaultStageClearGpu(egl, memory)
            }
            owners += Owner(session, ledger)
            return session
        }
        try {
            assertFalse(setting.visible)
            assertEquals(DevelopEngine.GPU, setting.current())
            instrumentation.runOnMainSync {
                camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { sourceUri = it }
            val bytes = syntheticTrialDng(64, 48, 6)
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(bytes) }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val original = SharedOriginalReference(uri.toString(), DngContentVerifier.identify(bytes.size.toLong(),
                { requireNotNull(resolver.openInputStream(uri)) }), OriginalGrant.APP_OWNED).also { source = it }
            AndroidPhotoEditRecipes(app).save(original, values).get(30, TimeUnit.SECONDS)
            fixture.content = {
                val host = requireNotNull(LocalActivity.current) as PhotoRollActivity
                val uiContext = remember(host) { object : ContextWrapper(host) {
                    override fun getApplicationContext(): Context = app
                    override fun getNoBackupFilesDir(): File = app.noBackupFilesDir
                    override fun getCacheDir(): File = app.cacheDir
                    override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(name, mode)
                    override fun getContentResolver() = app.contentResolver
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
                CompositionLocalProvider(LocalContext provides uiContext) {
                    CaptureTheme {
                        GpuPhotoRollEditor(repository, renderer, uri.toString(), false, "SYNTHETIC GPU SAVE",
                            state.isAtLeast(Lifecycle.State.RESUMED), publications, host.previewTrimEpoch, {},
                            Modifier.fillMaxSize().safeDrawingPadding(), createSession = ::newSession)
                    }
                }
            }
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                await("real GPU presentation") { action("JPEGを保存") != null && owners.lastOrNull()?.session?.presented?.value == values }
                assertEquals(1, owners.size)
                val preview = owners.single()
                assertTrue(preview.ledger.get().peakAllocatedBytes > 0)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val barrier = queue.manualOriginalIo { entered.countDown(); check(release.await(120, TimeUnit.SECONDS)) }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    click("JPEGを保存") // The product callback, with no substituted save owner/work.
                    await("real foreground service admission") { ForegroundExports.gate.active()?.started == true }
                    assertEquals(1, serviceStarts.get())
                    assertEquals(true, closedAtServiceStart.get())
                    assertClosed(preview)
                    val ticket = requireNotNull(ForegroundExports.gate.active())
                    val save = ticket.value.identity as AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
                    handle = save
                    assertEquals(original, save.request.source)
                    assertEquals(ManualOriginalTrialRender.recipe(values.strength, PhotoAdjustmentPolicy.adjustments(values)), save.request.recipe)
                    assertNull(journal(app, save).read())
                    assertTrue(UiDevice.getInstance(instrumentation).pressHome())
                    await("Home stops editor") { scenario.state == Lifecycle.State.CREATED }
                    assertFalse(ticket.stopRequested)
                    assertSame(ticket, ForegroundExports.gate.active())
                    app.startActivity(Intent(app, PhotoRollActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    await("return retains active save") { scenario.state == Lifecycle.State.RESUMED && action("保存を取り消す") != null }
                    assertSame(ticket, ForegroundExports.gate.active())
                    assertFalse(ticket.stopRequested)
                    assertEquals("Home/return cannot recreate a preview during save", 1, owners.size)
                    assertNull("Preview resume must stay disabled during save", action("プレビューを再開"))
                    assertEquals(DevelopEngine.GPU, setting.current())
                    // Finish the actual GPU export while the Activity is stopped again.
                    assertTrue(UiDevice.getInstance(instrumentation).pressHome())
                    await("second Home stops editor") { scenario.state == Lifecycle.State.CREATED }
                } finally { release.countDown(); barrier.get(30, TimeUnit.SECONDS) }
                await("published save releases foreground service") { ForegroundExports.gate.active() == null }
                val save = requireNotNull(handle)
                assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, save.state.value.phase)
                assertFalse(save.hasUnreleasedResources)
                assertEquals(DevelopEngine.GPU, setting.current())
                val record = requireNotNull(journal(app, save).read())
                assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
                assertEquals(save.state.value.uri, record.uri)
                val proof = record.render as ManualOriginalTrialRender
                assertEquals(original, proof.request.source)
                assertEquals(save.request.recipe, proof.request.recipe)
                assertEquals(values.strength, proof.appliedStrength, 0.0)
                assertEquals(PhotoAdjustmentPolicy.adjustments(values), proof.adjustments)
                val jpeg = Uri.parse(requireNotNull(record.uri)).also { outputUri = it }
                outputName = record.target.displayName
                assertNotEquals(uri, jpeg)
                val inspection = AndroidJpegInspector().inspect({ requireNotNull(resolver.openInputStream(jpeg)) }, record.fingerprint.bytes) { false }
                assertEquals(record.fingerprint, inspection.fingerprint)
                assertEquals(proof.request.output, inspection.output)
                assertEquals(48, inspection.output.width); assertEquals(64, inspection.output.height)
                assertFalse(File(RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), save.request.captureId,
                    save.request.generation).generationRoot(), "original-work").exists())
                app.startActivity(Intent(app, PhotoRollActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                await("return shows confirmed retained save") { action("保存したJPEGを開く（開く前に照合します）") != null && action("プレビューを再開") != null }
                assertEquals("Publication/return cannot automatically restart preview", 1, owners.size)
                click("プレビューを再開")
                await("explicit resume presents the same recipe") { owners.size == 2 && owners.last().session.presented.value == values &&
                    action("保存したJPEGを開く（開く前に照合します）") != null }
                val same = save.advance(observer.snapshot().developConditions(true)) { false }.get(30, TimeUnit.SECONDS)
                assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, same.phase)
                assertEquals(record.uri, same.uri)
                assertEquals(record, journal(app, save).read())
                assertEquals(1, serviceStarts.get())
                DngContentVerifier.verify(original.fingerprint, { requireNotNull(resolver.openInputStream(uri)) })
                scenario.moveToState(Lifecycle.State.CREATED)
            }
            success = true
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            preserveSafetyCleanup(failure, {
                observer.stop()
                ForegroundExports.gate.active()?.let { ticket ->
                    requireOwnedSafetyIdentity(ticket.value.identity, handle)
                    ForegroundExports.cancel(ticket.value)
                }
                handle?.requestStop()
                owners.forEach { it.session.close() }
                owners.forEach(::assertClosed)
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                renderer.discardTransientInputs()
                AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
                await("all foreground export resources released") { ForegroundExports.gate.active() == null }
                assertFalse(handle?.hasUnreleasedResources == true)
                source?.let { original -> DngContentVerifier.verify(original.fingerprint,
                    { requireNotNull(resolver.openInputStream(Uri.parse(original.uri))) }) }
                // Failure/uncertainty retains every synthetic row and private journal as evidence.
                if (success) {
                    val input = requireNotNull(sourceUri); val output = requireNotNull(outputUri)
                    val name = requireNotNull(outputName)
                    requireOwnedRow(app, input, sourceName); requireOwnedRow(app, output, name)
                    deleteOwnedRow(app, output, name); deleteOwnedRow(app, input, sourceName)
                }
            }, {
                instrumentation.runOnMainSync { camera?.close() }
            }, {
                val edit = preferences.edit()
                if (previousSetting == null) edit.remove("develop/engine") else edit.putString("develop/engine", previousSetting)
                check(edit.commit()) { "Restore original develop-engine setting" }
            })
        }
    }

    private data class Owner(val session: GpuPhotoRollSession, val ledger: AtomicReference<GpuMemoryLedger>)
    private fun assertClosed(owner: Owner) {
        assertTrue("Preview GL thread, input and callbacks closed", owner.session.renderer.awaitClosed(10_000))
        owner.ledger.get()?.let { assertEquals(0L, it.allocatedBytes); assertEquals(0, it.textureCount) }
    }
    private fun journal(app: Context, save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) =
        JpegPublicationJournal(save.request.captureId, save.request.generation,
            AndroidAtomicJournalStorage(File(app.noBackupFilesDir, "raw-captures/${save.request.captureId}/renders"),
                save.request.generation.toString(), CaptureJournalFile.JPEG_PUBLICATION))
    private fun requireOwnedRow(app: Context, uri: Uri, name: String) {
        require(uri.scheme == "content" && uri.authority == "media" && ContentUris.parseId(uri) > 0)
        requireNotNull(app.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING),
            null, null, null)).use { row ->
            check(row.count == 1 && row.moveToFirst() && row.getLong(0) == ContentUris.parseId(uri) &&
                row.getString(1) == name && row.getString(2) == app.packageName && !row.isNull(3) && row.getInt(3) == 0)
        }
    }
    private fun deleteOwnedRow(app: Context, uri: Uri, name: String) {
        requireOwnedRow(app, uri, name)
        check(app.contentResolver.delete(uri,
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
            arrayOf(app.packageName, name)) == 1)
    }
    private fun action(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        var node = find(fixture.fixtureRoot())
        while (node != null && !node.isClickable) node = node.parent
        return node?.takeIf { it.refresh() && it.isEnabled && it.isVisibleToUser }
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await("enabled $label") { action(label).also { node = it } != null }
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 60_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < end)
        fixture.failure(label)
        error("GPU save lifecycle did not reach: $label")
    }
}
