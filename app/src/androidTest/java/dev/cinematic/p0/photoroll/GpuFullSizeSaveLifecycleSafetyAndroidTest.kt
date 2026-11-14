package dev.cinematic.p0.photoroll

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import androidx.activity.compose.LocalActivity
import dev.cinematic.p0.jpegrender.original.isolatedGpuSaveSafetyApp
import dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.AssetManager
import android.net.Uri
import android.opengl.EGL14
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
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #289, opt-in synthetic 12MP editor -> actual FGS -> MediaStore coverage, not device/quality QA.
 * The queue barrier is BEFORE production: it proves background/screen-off production, not a
 * named GPU band or an active-GPU trim. Context loss below is a one-shot exception while EGL is
 * current, not driver/context destruction. Only preview ledgers are observable through this API.
 * Cancellation and ambiguous publication recovery are covered separately; this class preserves FGS/UI guarantees.
 */
@RunWith(AndroidJUnit4::class)
class GpuFullSizeSaveLifecycleSafetyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun real12MpSavePublishesWhileScreenOffWithoutImplicitPreviewRestart() = withFixture { env ->
        env.installEditor()
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            await("real 12MP GPU preview", PREPARE_TIMEOUT_MS) {
                action("JPEGを保存") != null && env.owners.lastOrNull()?.session?.presented?.value == VALUES
            }
            assertEquals(1, env.owners.size)
            val preview = env.owners.single()
            assertTrue(requireNotNull(preview.ledger.get()).peakAllocatedBytes > 0)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val barrier = env.queue.manualOriginalIo {
                entered.countDown()
                check(release.await(180, TimeUnit.SECONDS)) { "Pre-producer UI barrier expired" }
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                click("JPEGを保存")
                await("actual FGS admitted before producer") { ForegroundExports.gate.active()?.started == true }
                val ticket = requireNotNull(ForegroundExports.gate.active())
                val save = (ticket.value.identity as AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle)
                    .also { env.handle = it }
                env.assertServiceStartedAfterPreviewClosed(save)
                assertClosed(preview)
                env.assertRequest(save)
                assertNull(env.journal(save).read())
                env.assertEditsBlocked(save)

                assertTrue(device.pressHome())
                await("Home stops editor") { scenario.state == Lifecycle.State.CREATED }
                assertSame(ticket, ForegroundExports.gate.active())
                assertFalse(ticket.stopRequested)
                env.returnToEditor()
                await("return retains same running save") {
                    scenario.state == Lifecycle.State.RESUMED && action("保存を取り消す") != null
                }
                assertSame(ticket, ForegroundExports.gate.active())
                assertFalse(ticket.stopRequested)
                assertEquals("Home/return must not recreate preview during save", 1, env.owners.size)
                assertNull(action("プレビューを再開"))
                env.assertEditsBlocked(save)

                assertTrue(device.pressHome())
                await("second Home stops editor") { scenario.state == Lifecycle.State.CREATED }
                device.sleep()
                await("screen is off before producer starts") { !device.isScreenOn }
                assertSame(ticket, ForegroundExports.gate.active())
                assertFalse(ticket.stopRequested)
                assertNull("No publication exists while producer is queued", env.journal(save).read())
            } finally {
                release.countDown()
                barrier.get(30, TimeUnit.SECONDS)
            }
            // The entire real producer/publication runs after the Activity is stopped and display
            // is off. No checkpoint counter is interpreted as a particular GPU-band boundary.
            await("publication and FGS closure while screen off", SAVE_TIMEOUT_MS) {
                assertFalse("Unexpected screen wake during background save", device.isScreenOn)
                assertEquals(Lifecycle.State.CREATED, scenario.state)
                ForegroundExports.gate.active() == null
            }
            val save = requireNotNull(env.handle)
            env.verifyPublication(save)
            assertFalse(device.isScreenOn)
            assertEquals(1, env.owners.size)
            device.wakeUp()
            await("screen awake after background publication") { device.isScreenOn }
            check(!requireNotNull(env.app.getSystemService(KeyguardManager::class.java)).isKeyguardLocked) {
                "Environment failure: keyguard remains after wake; this test does not dismiss locks or change lock settings"
            }
            env.returnToEditor()
            await("return shows publication and explicit preview resume") {
                scenario.state == Lifecycle.State.RESUMED && action("保存したJPEGを開く（開く前に照合します）") != null &&
                    action("プレビューを再開") != null
            }
            assertEquals("Publication/return cannot implicitly reopen preview", 1, env.owners.size)
            assertEquals(1, env.serviceStarts.get())
            click("プレビューを再開")
            await("only explicit resume presents original recipe", PREPARE_TIMEOUT_MS) {
                env.owners.size == 2 && env.owners.last().session.presented.value == VALUES
            }
            env.assertRequest(save)
            scenario.moveToState(Lifecycle.State.CREATED)
        }
    }

    @Test fun recoveredPseudoContextLossUsesRealUiRetryAndSame12MpGeneration() = withFixture { env ->
        // Persist the exact same recipe before both factory prepare and editor open. A changed
        // recipe would legitimately request a new generation and cannot recover GPU_FAILED.
        DevelopExecution.updateConditions(ALLOWED)
        val save = AndroidManualOriginalTrialSaves.prepare(env.app, env.source, VALUES.strength, ALLOWED,
            PhotoAdjustmentPolicy.adjustments(VALUES)).get(120, TimeUnit.SECONDS).also { env.handle = it }
        // Normal UI prepares through prepareGpu, which publishes the knownPrepared index.
        // This is same-process retry, not cold process-death restoration.
        val indexed = AndroidManualOriginalTrialSaves.prepareGpu(env.app, env.source, save.geometry,
            VALUES.strength, ALLOWED, PhotoAdjustmentPolicy.adjustments(VALUES)) { false }
            .get(120, TimeUnit.SECONDS)
        assertSame(save, indexed)
        assertEquals(save.request, indexed.request)
        assertEquals(save.geometry, indexed.geometry)
        assertSame(save, AndroidManualOriginalTrialSaves.knownPrepared(env.source))
        val request = save.request
        env.assertRequest(save)
        val beforeCount = env.failureCount()
        check(beforeCount in 0 until Long.MAX_VALUE)
        val injected = AtomicBoolean()
        val firstGlThread = AtomicReference<Thread>()
        val sourceBoundarySeen = AtomicBoolean()
        val failed = save.advance(ALLOWED) {
            if (EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT && injected.compareAndSet(false, true)) {
                firstGlThread.set(Thread.currentThread())
                // Observe the actual factory snapshot and full RGB16 source before GPU failure.
                val work = File(env.directory(save), "original-work")
                val snapshot = File(work, "source-snapshot.dng")
                val raster = File(work, "source-linear.rgb16")
                if (snapshot.isFile && snapshot.length() == env.source.fingerprint.bytes &&
                    raster.isFile && raster.length() == WIDTH.toLong() * HEIGHT * 6) {
                    DngContentVerifier.verify(env.source.fingerprint, { snapshot.inputStream() })
                    sourceBoundarySeen.set(true)
                }
                throw GpuContextLostException("Synthetic UI export pseudo context loss")
            }
            false // Classification/cleanup must see failure, never a latched cancellation.
        }.get(SAVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        assertTrue("Pseudo exception must originate with an actual current EGL context", injected.get())
        assertTrue("Full original snapshot/raster boundary must be reached", sourceBoundarySeen.get())
        assertFalse("First actual export GL thread must have terminated", requireNotNull(firstGlThread.get()).isAlive)
        assertEquals(ManualOriginalTrialSavePhase.GPU_FAILED, failed.phase)
        assertEquals(GpuSaveFailure(GpuSaveFailureReason.CONTEXT_LOST, 1), failed.gpuFailure)
        assertNull(failed.uri)
        assertFalse(save.hasUnreleasedResources)
        assertFalse(File(env.directory(save), "original-work").exists())
        assertFalse(File(env.directory(save), "output.jpg").exists())
        assertNull(env.journal(save).read())
        env.assertFailureCount(beforeCount + 1)
        env.verifyOriginal()
        val intent = requireNotNull(env.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
        assertArrayEquals(ManualOriginalTrialRequestCodec.encode(request), intent)

        env.installEditor()
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            val description = requireNotNull(failed.gpuFailure).description
            await("recovered GPU_FAILED reason/count and real retry control", PREPARE_TIMEOUT_MS) {
                find { it.text?.toString() == description } != null && action("同じ設定で保存を再試行") != null
            }
            assertEquals("Opening failed save must not start an automatic retry", 0, env.serviceStarts.get())
            assertEquals(failed, save.state.value)
            assertEquals(1, env.owners.size)
            val recoveredPreview = env.owners.single()
            assertClosed(recoveredPreview)
            assertNull(recoveredPreview.session.presented.value)
            // engineFactory is invoked by openPhoto only. The recovered failed owner closes the
            // session before that call instead of running a parallel preview during retry.
            assertNull("GPU_FAILED must close preview before openPhoto", recoveredPreview.ledger.get())
            env.assertEditsBlocked(save)
            env.assertFailureCount(beforeCount + 1)

            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val barrier = env.queue.manualOriginalIo {
                entered.countDown()
                check(release.await(90, TimeUnit.SECONDS)) { "UI retry barrier expired" }
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                click("同じ設定で保存を再試行") // Real editor callback -> same handle -> actual FGS.
                await("explicit retry admitted to actual FGS") { ForegroundExports.gate.active()?.started == true }
                assertSame(save, requireNotNull(ForegroundExports.gate.active()).value.identity)
                env.assertServiceStartedAfterPreviewClosed(save)
                assertEquals(request, save.request)
                assertArrayEquals(intent, env.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                env.assertEditsBlocked(save)
            } finally {
                release.countDown()
                barrier.get(30, TimeUnit.SECONDS)
            }
            await("explicit UI GPU retry publishes and releases FGS", SAVE_TIMEOUT_MS) {
                ForegroundExports.gate.active() == null
            }
            env.verifyPublication(save)
            assertEquals(request, save.request)
            assertEquals(failed.gpuFailure, save.state.value.gpuFailure)
            assertArrayEquals(intent, env.intent(save).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            env.assertFailureCount(beforeCount + 1)
            await("retained retry result shown without automatic preview") {
                action("保存したJPEGを開く（開く前に照合します）") != null && action("プレビューを再開") != null
            }
            assertEquals(1, env.owners.size)
            assertEquals(1, env.serviceStarts.get())
            scenario.moveToState(Lifecycle.State.CREATED)
        }
    }

    private inner class Environment(target: Context) {
        val owners = CopyOnWriteArrayList<Owner>()
        val serviceStarts = AtomicInteger()
        val closedAtServiceStart = AtomicReference<Boolean>()
        val serviceIdentity = AtomicReference<AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle>()
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(service: Intent): ComponentName? {
                assertEquals(ForegroundExportService::class.java.name, service.component?.className)
                val save = requireNotNull(ForegroundExports.gate.active()?.value?.identity as?
                    AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle)
                check(save.matches(source)) { "Foreground service must belong to this synthetic source" }
                // Retain the exact owner before service startup or subsequent UI waits can fail.
                serviceIdentity.set(save)
                closedAtServiceStart.set(CompletableFuture.supplyAsync {
                    owners.isNotEmpty() && owners.all { it.session.renderer.awaitClosed(0) }
                }.get(5, TimeUnit.SECONDS))
                serviceStarts.incrementAndGet()
                return super.startForegroundService(service)
            }
        }
        val setting = DevelopEngineSetting(app)
        val resolver = app.contentResolver
        val queue = AndroidAutoDevelopQueue.get(app)
        private val publications = MutableStateFlow(GpuInputPublication())
        private val observer = PhotoRollResourceObserver(app, DevelopExecution::updateConditions,
            onPublication = { observation -> publications.update { GpuInputPublication(it.sequence + 1, observation) } })
        private val renderer = ManualTrialPhotoRollRenderer(app, observer::snapshot, developEngine = DevelopEngine.GPU)
        private val repository = PhotoRollRepository(resolver, app.packageName)
        val sourceName = "synthetic_gpu_export_ui_safety_${UUID.randomUUID()}.dng"
        var sourceUri: Uri? = null
        lateinit var source: SharedOriginalReference
        var output: OwnedRow? = null
        var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        var camera: PhotoRollCameraOwners.Owner? = null

        fun createSource() {
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { sourceUri = it }
            val bytes = requireNotNull(resolver.openOutputStream(uri, "w")).use { writeSyntheticTrialDng(it, WIDTH, HEIGHT, 1) }
            assertTrue(bytes in 1..64L * 1024 * 1024)
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            source = SharedOriginalReference(uri.toString(), DngContentVerifier.identify(bytes,
                { requireNotNull(resolver.openInputStream(uri)) }), OriginalGrant.APP_OWNED)
            AndroidPhotoEditRecipes(app).save(source, VALUES).get(30, TimeUnit.SECONDS)
        }

        private fun newSession(assets: AssetManager): GpuPhotoRollSession {
            val ledger = AtomicReference<GpuMemoryLedger>()
            val session = GpuPhotoRollSession(assets) { egl, memory ->
                check(ledger.compareAndSet(null, memory))
                DefaultStageClearGpu(egl, memory)
            }
            owners += Owner(session, ledger)
            return session
        }

        fun installEditor() {
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
                        GpuPhotoRollEditor(repository, renderer, source.uri, false, "SYNTHETIC 12MP GPU SAFETY",
                            state.isAtLeast(Lifecycle.State.RESUMED), publications, host.previewTrimEpoch, {},
                            Modifier.fillMaxSize().safeDrawingPadding(), createSession = ::newSession)
                    }
                }
            }
        }

        fun returnToEditor() = app.startActivity(Intent(app, PhotoRollActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

        fun assertServiceStartedAfterPreviewClosed(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
            assertEquals(1, serviceStarts.get())
            assertEquals(true, closedAtServiceStart.get())
            assertSame(save, serviceIdentity.get())
            assertEquals(DevelopEngine.GPU, setting.current())
        }

        fun assertRequest(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
            assertEquals(source, save.request.source)
            assertTrue(save.matches(source))
            assertEquals(ManualOriginalTrialRender.recipe(VALUES.strength, PhotoAdjustmentPolicy.adjustments(VALUES)), save.request.recipe)
            assertEquals(WIDTH, save.geometry.rawWidth); assertEquals(HEIGHT, save.geometry.rawHeight)
            assertEquals(WIDTH, save.geometry.sourceWidth); assertEquals(HEIGHT, save.geometry.sourceHeight)
            assertEquals(WIDTH, save.geometry.outputWidth); assertEquals(HEIGHT, save.geometry.outputHeight)
            assertEquals(1, save.geometry.orientation)
            assertEquals(VALUES, CompletableFuture.supplyAsync { AndroidPhotoEditRecipes(app).read(source) }
                .get(30, TimeUnit.SECONDS))
            assertEquals(DevelopEngine.GPU, setting.current())
        }

        fun assertEditsBlocked(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
            listOf("STRENGTH", "色温度", "明るさ").forEach { label ->
                val node = requireNotNull(control(label)) { "Missing edit control: $label" }
                assertFalse("Edit control must be disabled during export: $label", node.isEnabled)
                // Exercise the disabled accessibility action. Its boolean is framework-specific;
                // the contract is no panel/range and no changed committed recipe afterward.
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            instrumentation.waitForIdleSync()
            assertNull(find { it.contentDescription?.toString() == "現像調整パネル" })
            assertNull(find { it.isVisibleToUser && it.isEnabled && it.rangeInfo != null })
            assertRequest(save)
        }

        fun directory(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) =
            RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), save.request.captureId, save.request.generation).generationRoot()
        private fun storage(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle, kind: CaptureJournalFile) =
            AndroidAtomicJournalStorage(requireNotNull(directory(save).parentFile), save.request.generation.toString(), kind)
        fun intent(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) = storage(save, CaptureJournalFile.MANUAL_REQUEST)
        fun journal(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) =
            JpegPublicationJournal(save.request.captureId, save.request.generation, storage(save, CaptureJournalFile.JPEG_PUBLICATION))
        fun failureCount() = app.getSharedPreferences("gpu-export-failures", Context.MODE_PRIVATE).getLong("count", 0)
        fun assertFailureCount(expected: Long) {
            assertEquals(expected, failureCount())
            assertEquals(GpuSaveFailureReason.CONTEXT_LOST.code,
                app.getSharedPreferences("gpu-export-failures", Context.MODE_PRIVATE).getString("lastReason", null))
        }
        fun verifyOriginal() {
            if (::source.isInitialized) assertEquals(source.fingerprint, DngContentVerifier.verify(source.fingerprint,
                { requireNotNull(resolver.openInputStream(Uri.parse(source.uri))) }))
        }
        fun verifyPublication(save: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle) {
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, save.state.value.phase)
            assertFalse(save.hasUnreleasedResources)
            assertRequest(save)
            val record = requireNotNull(journal(save).read())
            val jpeg = Uri.parse(requireNotNull(record.uri))
            output = OwnedRow(jpeg, record.target.displayName)
            assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
            assertEquals(save.state.value.uri, record.uri)
            val proof = record.render as ManualOriginalTrialRender
            assertEquals(source, proof.request.source)
            assertEquals(save.request.captureId, proof.request.captureId)
            assertEquals(save.request.generation, proof.request.generation)
            assertEquals(save.request.recipe, proof.request.recipe)
            assertEquals(VALUES.strength, proof.appliedStrength, 0.0)
            assertEquals(PhotoAdjustmentPolicy.adjustments(VALUES), proof.adjustments)
            assertNotEquals(sourceUri, jpeg)
            requireOwnedRow(app, requireNotNull(output))
            val inspection = AndroidJpegInspector().inspect({ requireNotNull(resolver.openInputStream(jpeg)) }, record.fingerprint.bytes) { false }
            assertEquals(record.fingerprint, inspection.fingerprint)
            assertEquals(proof.request.output, inspection.output)
            assertEquals(WIDTH, inspection.output.width); assertEquals(HEIGHT, inspection.output.height)
            assertEquals(1, inspection.output.orientation)
            assertEquals("sRGB", inspection.output.color)
            assertFalse(File(directory(save), "original-work").exists())
            verifyOriginal()
        }
        fun closeOwners() {
            val save = handle ?: serviceIdentity.get()
            ForegroundExports.gate.active()?.takeIf { it.value.identity === save }?.let { ForegroundExports.cancel(it.value) }
            save?.requestStop()
            owners.forEach { it.session.close() }
            owners.forEach(::assertClosed)
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            renderer.discardTransientInputs()
            AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
            await("foreground export resources released") { ForegroundExports.gate.active() == null }
            assertFalse(save?.hasUnreleasedResources == true)
            observer.stop()
            verifyOriginal()
        }
    }

    private fun withFixture(run: (Environment) -> Unit) {
        val target = isolatedGpuSaveSafetyApp()
        val arguments = InstrumentationRegistry.getArguments()
        // Every test checks all opt-ins before touching preferences, media, or a GL context.
        check(BuildConfig.BUILD_TYPE == "debug")
        check(target.packageName == "dev.cinematic.p0")
        check(arguments.getString("isolated") == "true" && arguments.getString("gpuSaveSafety") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && Build.VERSION.SDK_INT >= 29)
        check(ForegroundExports.gate.active() == null)
        val keyguard = requireNotNull(target.getSystemService(KeyguardManager::class.java))
        check(device.isScreenOn && !keyguard.isKeyguardLocked && !keyguard.isKeyguardSecure) {
            "Use an already unlocked, non-secure emulator; this test never changes lock settings"
        }
        val preferences = target.getSharedPreferences("capture-probe", Context.MODE_PRIVATE)
        val hadEngine = preferences.contains(ENGINE_KEY)
        val previousEngine = preferences.getString(ENGINE_KEY, null)
        val env = Environment(target)
        var success = false
        var failure: Throwable? = null
        try {
            assertFalse(env.setting.visible)
            assertEquals(DevelopEngine.GPU, env.setting.current())
            instrumentation.runOnMainSync {
                env.camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
            }
            env.createSource()
            run(env)
            success = true
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            preserveSafetyCleanup(failure, {
                // Wake only the screen this test was allowed to turn off. Do not dismiss a secure
                // lock, alter permissions, or erase any evidence on failure/uncertain closure.
                if (!device.isScreenOn) device.wakeUp()
                env.closeOwners()
                if (success) {
                    val rows = listOf(requireNotNull(env.output), OwnedRow(requireNotNull(env.sourceUri), env.sourceName))
                    assertEquals(rows.size, rows.map { it.uri }.toSet().size)
                    rows.forEach { requireOwnedRow(env.app, it) }
                    rows.forEach { row ->
                        requireOwnedRow(env.app, row)
                        check(env.resolver.delete(row.uri,
                            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                            arrayOf(env.app.packageName, row.name)) == 1)
                    }
                }
            }, {
                instrumentation.runOnMainSync { env.camera?.close() }
            }, {
                    val editor = preferences.edit()
                    if (hadEngine) editor.putString(ENGINE_KEY, previousEngine) else editor.remove(ENGINE_KEY)
                    check(editor.commit()) { "Restore exact prior develop-engine setting" }
                    check(preferences.contains(ENGINE_KEY) == hadEngine && preferences.getString(ENGINE_KEY, null) == previousEngine)
            })
        }
    }

    private data class Owner(val session: GpuPhotoRollSession, val ledger: AtomicReference<GpuMemoryLedger>)
    private data class OwnedRow(val uri: Uri, val name: String)
    private fun assertClosed(owner: Owner) {
        assertTrue("Preview GL thread/input/callback closure", owner.session.renderer.awaitClosed(10_000))
        owner.ledger.get()?.let { assertEquals(0L, it.allocatedBytes); assertEquals(0, it.textureCount) }
    }
    private fun requireOwnedRow(app: Context, row: OwnedRow) {
        require(row.uri.scheme == "content" && row.uri.authority == "media" && ContentUris.parseId(row.uri) > 0)
        requireNotNull(app.contentResolver.query(row.uri, arrayOf(MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING),
            null, null, null)).use { cursor ->
            check(cursor.count == 1 && cursor.moveToFirst() && cursor.getLong(0) == ContentUris.parseId(row.uri) &&
                cursor.getString(1) == row.name && cursor.getString(2) == app.packageName && !cursor.isNull(3) && cursor.getInt(3) == 0)
        }
    }
    private fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (matches(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        return visit(fixture.fixtureRoot())
    }
    private fun control(label: String): AccessibilityNodeInfo? {
        var node = find { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (node != null && !node.isClickable && node.isEnabled) node = node.parent
        return node?.takeIf { it.refresh() && it.isVisibleToUser }
    }
    private fun action(label: String) = control(label)?.takeIf { it.isClickable && it.isEnabled }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await("enabled $label") { action(label).also { node = it } != null }
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, timeoutMs: Long = 60_000, condition: () -> Boolean) {
        // elapsedRealtime includes screen-off/deep sleep. Bounds are not performance gates.
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        fixture.failure(label)
        error("GPU export safety did not reach: $label")
    }
    companion object {
        private const val WIDTH = 4000
        private const val HEIGHT = 3000
        private const val ENGINE_KEY = "develop/engine"
        private const val PREPARE_TIMEOUT_MS = 120_000L
        private const val SAVE_TIMEOUT_MS = 600_000L
        private val VALUES = PhotoEditValues(.65, 20.0, .3)
        private val ALLOWED = AutoDevelopConditions(true, false, false, false, true, true, true, true,
            previewMemoryAvailable = true)
    }
}
