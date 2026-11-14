package dev.cinematic.p0.photoroll

import android.content.*
import android.net.Uri
import android.os.*
import android.provider.*
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.*
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real DocumentsUI -> ordinary product input/editor. No diagnostic Activity or input loader. */
class GpuInputFilesSafetyAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    @get:Rule val fixture = SyntheticFixtureRule()
    private var waitingFor = "setup"
    private var conditionBoundMs = 10_000L
    private lateinit var evidence: File

    @Test fun filesSelectsOnlyOwnedSyntheticDngAndProductGpuRendersIt() {
        requireGpuInputSafetyFixture()
        val root = File.createTempFile("gpu-files-private-", ".tmp", target.cacheDir.canonicalFile)
        check(root.delete() && root.mkdir())
        evidence = File(root, "evidence").apply { check(mkdir()) }
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = File(root, "store").apply { check(isDirectory || mkdir()) }
            override fun getCacheDir() = File(root, "cache").apply { check(isDirectory || mkdir()) }
        }
        AndroidAutoDevelopQueue.get(app).manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
        val resolver = target.contentResolver
        val name = "gpu-synthetic-${UUID.randomUUID()}.dng"
        val owned = checkNotNull(resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val owners = CopyOnWriteArrayList<GpuPhotoRollSession>()
        val ledgers = CopyOnWriteArrayList<GpuMemoryLedger>()
        val publications = MutableStateFlow(GpuInputPublication())
        val observer = PhotoRollResourceObserver(app, DevelopExecution::updateConditions,
            onPublication = { observation -> publications.update { GpuInputPublication(it.sequence + 1, observation) } })
        val renderer = ManualTrialPhotoRollRenderer(app, observer::snapshot, developEngine = DevelopEngine.GPU)
        val repository = PhotoRollRepository(resolver, target.packageName)
        var fingerprint: OriginalFingerprint? = null
        var completed = false
        try {
            val bytes = checkNotNull(resolver.openOutputStream(owned, "w")).use { writeSyntheticTrialDng(it, 64, 48, 1) }
            fingerprint = DngContentVerifier.identify(bytes, { checkNotNull(resolver.openInputStream(owned)) })
            assertEquals(6530L, fingerprint.bytes)
            assertEquals("c2bea28b28d64a08df77443ce2b9eb3083e670c1fa295465907c76f60d4a4926", fingerprint.sha256)
            assertEquals(1, resolver.update(owned, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            fixture.content = {
                val host = LocalContext.current
                val uiContext = remember(host) { object : ContextWrapper(host) {
                    override fun getApplicationContext(): Context = app
                    override fun getNoBackupFilesDir() = app.noBackupFilesDir
                    override fun getCacheDir() = app.cacheDir
                } }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val state by lifecycle.currentStateFlow.collectAsState()
                var selected by remember { mutableStateOf<Uri?>(null) }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        check(uri.authority == "com.android.externalstorage.documents")
                        check(DocumentsContract.getDocumentId(uri) == "primary:Download/$name")
                        selected = uri
                    }
                }
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
                        val document = selected
                        if (document == null) TextButton(onClick = { picker.launch(arrayOf("image/x-adobe-dng")) }) {
                            Text("合成DNGをFilesから選択")
                        } else GpuPhotoRollEditor(repository, renderer, document.toString(), true,
                            "SYNTHETIC", state.isAtLeast(Lifecycle.State.RESUMED), publications, 0, {},
                            Modifier.fillMaxSize(), createSession = { assets ->
                                GpuPhotoRollSession(assets) { egl, memory ->
                                    ledgers += memory
                                    DefaultStageClearGpu(egl, memory, GpuFrameMode.FULL_RECOMPUTE)
                                }.also { owners += it }
                            })
                    }
                }
            }
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                find("test Files button", By.pkg(target.packageName).text("合成DNGをFilesから選択")).click()
                await("DocumentsUI foreground") { device.currentPackageName?.endsWith(".documentsui") == true }
                val filesPackage = checkNotNull(device.currentPackageName)
                openInternalDownload(filesPackage)
                find("owned synthetic DNG", By.pkg(filesPackage).text(name), Direction.DOWN).click()
                await("ordinary product GPU presents selected DNG", 60_000) {
                    owners.lastOrNull()?.let { current ->
                        current.failure.value?.let { throw AssertionError("Selected native input failed", it) }
                        current.presented.value != null
                    } == true
                }
                val current = owners.last()
                val frame = awaitFrame(current.renderer, 0, "actual selected DNG GPU frame")
                assertEquals(1, frame.windowCount)
                assertTrue(frame.allocatedBytes > 0)
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertEquals(target.packageName, device.currentPackageName)
                DngContentVerifier.verify(checkNotNull(fingerprint), { checkNotNull(resolver.openInputStream(owned)) })
                captureEvidence("product-loaded")
            }
            completed = true
        } finally {
            observer.stop()
            owners.forEach { it.close(); assertTrue(it.renderer.awaitClosed(10_000)) }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            ledgers.forEach { assertEquals(0L, it.allocatedBytes); assertEquals(0, it.textureCount) }
            renderer.discardTransientInputs()
            AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
            // Failures retain the row and evidence. Success deletes only the exact verified owned row.
            if (completed) {
                DngContentVerifier.verify(checkNotNull(fingerprint), { checkNotNull(resolver.openInputStream(owned)) })
                val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                checkNotNull(resolver.query(owned, columns, null, null, null)).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(ContentUris.parseId(owned), it.getLong(0))
                    assertEquals(name, it.getString(1))
                    assertEquals(target.packageName, it.getString(2))
                    assertFalse(it.moveToNext())
                }
                assertEquals(1, resolver.delete(owned, null, null))
            }
        }
    }

    private fun openInternalDownload(filesPackage: String) {
        // Downloads/Recent roots can return a different provider, which the app deliberately rejects.
        // Observe the actual DocumentsUI package and the emulator's device label; select primary
        // storage via the drawer, then its real Download directory. Do not alter the OpenDocument intent.
        val labels = listOfNotNull(Build.MODEL,
            Settings.Global.getString(target.contentResolver, "device_name"),
            "Internal storage", "Internal shared storage", "内部ストレージ", "内部共有ストレージ")
        val rootsList = By.res(filesPackage, "roots_list")
        // Match only a drawer descendant, never a same-named breadcrumb or backing content.
        // BySelector.hasAncestor is available in the existing UiAutomator 2.3.0 dependency.
        val internalRoot = By.pkg(filesPackage)
            .text(Pattern.compile(labels.distinct().joinToString("|") { Pattern.quote(it) }))
            .hasAncestor(rootsList)
        fun openRoots() {
            if (!device.hasObject(rootsList)) {
                find("Files navigation drawer", By.pkg(filesPackage).clazz("android.widget.ImageButton")
                    .desc(Pattern.compile("Show roots|Open navigation drawer|ルートを表示|ナビゲーション ドロワーを開く"))).click()
            }
            find("Files roots list", rootsList)
        }
        openRoots()
        if (!device.hasObject(internalRoot)) {
            device.pressBack()
            find("Files overflow menu", By.pkg(filesPackage)
                .desc(Pattern.compile("More options|その他のオプション"))).click()
            find("show internal storage menu", By.pkg(filesPackage)
                .text(Pattern.compile("Show internal storage|内部ストレージを表示"))).click()
            openRoots()
        }
        find("internal storage root in Files drawer", internalRoot)
        captureEvidence("internal-root-before-click")
        // Evidence collection can change the hierarchy; resolve the scoped root again before clicking.
        val root = checkNotNull(device.findObject(internalRoot)) { "Scoped internal storage root disappeared before click" }
        val transitionDeadline = SystemClock.elapsedRealtime() + 10_000
        root.click()
        captureEvidence("internal-root-after-click")
        // Count evidence capture in the same 10s deadline; never grant a second transition wait.
        val remainingMs = transitionDeadline - SystemClock.elapsedRealtime()
        check(remainingMs > 0) { "Internal storage transition exceeded the existing 10s bound" }
        await("internal Download directory after Files drawer closes", remainingMs) {
            if (device.hasObject(rootsList)) false else {
                val download = device.findObject(By.pkg(filesPackage).text(Environment.DIRECTORY_DOWNLOADS))
                if (download != null) {
                    download.click()
                    true
                } else {
                    scroll(filesPackage)
                    false
                }
            }
        }
    }

    private fun find(label: String, selector: BySelector, scrollDirection: Direction? = null): UiObject2 {
        var result: UiObject2? = null
        await(label) {
            result = device.findObject(selector)
            if (result != null) true else {
                if (scrollDirection != null) device.currentPackageName?.let { scroll(it, scrollDirection) }
                false
            }
        }
        return checkNotNull(result)
    }

    private fun scroll(packageName: String, direction: Direction = Direction.DOWN) {
        device.findObject(By.pkg(packageName).scrollable(true))?.scroll(direction, 0.5f)
    }

    private fun snapshot(renderer: GpuPreviewRenderer): GpuPreviewSnapshot {
        val latest = AtomicReference<GpuPreviewSnapshot>()
        renderer.snapshot { latest.set(it) }
        await("renderer snapshot") { latest.get() != null }
        return checkNotNull(latest.get())
    }

    private fun awaitFrame(renderer: GpuPreviewRenderer, after: Long, label: String): GpuPreviewSnapshot {
        val latest = AtomicReference<GpuPreviewSnapshot>()
        await(label) {
            renderer.snapshot { latest.set(it) }
            latest.get()?.let { it.drawCount > after && it.windowCount == 1 && it.allocatedBytes > 0 } == true
        }
        return checkNotNull(latest.get())
    }

    private fun await(label: String, boundMs: Long = 10_000, condition: () -> Boolean) {
        // Existing UI/GL tests allow 10s for local transitions. Poll state rather than sleeping for it.
        waitingFor = label
        conditionBoundMs = boundMs
        Log.i(TAG, "WAIT $label boundMs=$boundMs")
        val deadline = SystemClock.elapsedRealtime() + boundMs
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out: $label (boundMs=$boundMs)")
    }

    private fun captureEvidence(stage: String) {
        val folder = File(evidence, stage).apply { check(mkdirs() || isDirectory) }
        var failure: Throwable? = null
        fun collect(action: () -> Unit) {
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
        }
        collect { File(folder, "wait-state.txt").writeText("stage=$stage\nwaitingFor=$waitingFor\nconditionBoundMs=$conditionBoundMs\n") }
        collect { device.dumpWindowHierarchy(File(folder, "window.xml")) }
        collect { assertTrue("UI screenshot capture failed", device.takeScreenshot(File(folder, "screen.png"))) }
        // Raw XML/images/logs are local evidence only. Publish redacted state/hash summaries, never
        // document names, provider URIs or personal paths. UiDevice does not shell-unquote '*:S'.
        collect { File(folder, "logcat.txt").writeText(device.executeShellCommand(
            "logcat -d -v brief -t 300 ActivityTaskManager:I ActivityManager:I GpuFilesTest:I AndroidRuntime:E *:S")) }
        Log.i(TAG, "UI evidence saved: stage=$stage waitingFor=$waitingFor")
        failure?.let { throw it }
    }

    private companion object { const val TAG = "GpuFilesTest" }
}
