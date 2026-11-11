package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
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
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Identical test-only initial-presentation control for main and the #340 candidate.
 * No production source changes or fault-injection seam are required. This does not
 * replace the full context-loss/trim/retry lifecycle regression on the candidate. */
class GpuProductInitialPresentationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun synthetic12MpOriginalReachesFirstProductGpuPresentation() {
        check(BuildConfig.BUILD_TYPE == "gpuDiagnostic")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = instrumentation.targetContext
        val setting = DevelopEngineSetting(target)
        val previous = setting.current()
        val root = File.createTempFile("gpu-lifecycle-private-", ".tmp", target.cacheDir)
        check(root.delete() && root.mkdir())
        val storage = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir() = File(root, "store").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        val provider = GpuProductInputAndroidTest.Provider(storage, 4000, 3000)
        val resolver = ContentResolver.wrap(provider)
        val app = object : ContextWrapper(storage) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        val queue = AndroidAutoDevelopQueue.get(app)
        queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
        val publications = MutableStateFlow(GpuInputPublication())
        val observer = PhotoRollResourceObserver(app, DevelopExecution::updateConditions,
            onPublication = { observation -> publications.update { GpuInputPublication(it.sequence + 1, observation) } })
        val renderer = ManualTrialPhotoRollRenderer(app, observer::snapshot, developEngine = DevelopEngine.GPU)
        val repository = PhotoRollRepository(resolver, target.packageName)
        val selected = mutableIntStateOf(1)
        fixture.content = {
            val host = LocalContext.current as PhotoRollActivity
            val uiContext = remember(host) { object : ContextWrapper(host) {
                override fun getApplicationContext(): Context = app
                override fun getContentResolver(): ContentResolver = resolver
                override fun getNoBackupFilesDir() = app.noBackupFilesDir
                override fun getCacheDir() = app.cacheDir
            } }
            // Read the same existing Compose state in both builds without adding a production getter.
            @Suppress("UNCHECKED_CAST")
            val trimEpoch = remember(host) {
                PhotoRollActivity::class.java.getDeclaredField("gpuTrimEpoch\$delegate").apply { isAccessible = true }
                    .get(host) as State<Int>
            }
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
                    key(selected.intValue) {
                        GpuPhotoRollEditor(repository, renderer, provider.uri(selected.intValue).toString(), false,
                            "SYNTHETIC", state.isAtLeast(Lifecycle.State.RESUMED), publications, trimEpoch.value,
                            {}, Modifier.fillMaxSize().safeDrawingPadding())
                    }
                }
            }
        }
        assertTrue(setting.select(DevelopEngine.GPU, GpuAvailability.Available))
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use {
                val until = SystemClock.uptimeMillis() + 60_000
                var ready = false
                do {
                    instrumentation.waitForIdleSync()
                    if (saveReady()) { ready = true; break }
                    SystemClock.sleep(50)
                } while (SystemClock.uptimeMillis() < until)
                if (!ready) {
                    fixture.failure("first full-pipeline presentation")
                    android.util.Log.e("SyntheticFixture", "Initial presentation: reads=${provider.opens.get()} publication=${publications.value} resources=${observer.snapshot()}")
                }
                assertTrue("Synthetic product initial presentation did not complete", ready)
                assertTrue(provider.opens.get() > 0)
                provider.verifyUnchanged()
                assertEquals(0, provider.writes.get())
            }
        } finally {
            try {
                observer.stop()
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                renderer.discardTransientInputs()
                AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
                provider.verifyUnchanged()
                assertTrue(provider.readers.none { it.fileDescriptor.valid() })
                assertFalse(root.walkTopDown().any { it.name == "source-snapshot.dng" || it.name == "source-linear.rgb16" })
            } finally { assertTrue(setting.select(previous, GpuAvailability.Available)) }
        }
    }

    private fun saveReady(): Boolean {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if ((node.text?.toString() == "JPEGを保存" || node.contentDescription?.toString() == "JPEGを保存") &&
                node.isVisibleToUser) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        var current = visit(fixture.fixtureRoot())
        while (current != null && !current.isClickable) current = current.parent
        return current?.let { it.refresh() && it.isVisibleToUser && it.isEnabled &&
            it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } } == true
    }
}
