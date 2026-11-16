package dev.cinematic.p0.photoroll

import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.SystemClock
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import dev.cinematic.p0.rawcapture.DevelopExecution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Editor/Surface and Back navigation, synthetic list launcher and private source provider. */
class GpuPreviewOwnerEditorAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun tenEditorOpenBackCyclesKeepOneContextAndRetireEachPhoto() = editorCycles(10)

    @Test fun fortyEditorOpenBackCyclesMeasureSecondHalfStability() = editorCycles(40)

    private fun editorCycles(rounds: Int) {
        requireGpuInputSafetyFixture()
        InitialPreviewProductFixture(false).use { product ->
            val publications = MutableStateFlow(GpuInputPublication())
            val observer = PhotoRollResourceObserver(product.app, DevelopExecution::updateConditions,
                onPublication = { value -> publications.update { GpuInputPublication(it.sequence + 1, value) } })
            val pool = GpuPhotoRollOwner(product.app.assets)
            val sessions = CopyOnWriteArrayList<GpuPhotoRollSession>()
            val visible = mutableStateOf(false)
            val samples = JSONArray()
            fixture.content = {
                val host = checkNotNull(LocalActivity.current) as PhotoRollActivity
                val uiContext = remember(host) { object : ContextWrapper(host) {
                    override fun getApplicationContext(): Context = product.app
                    override fun getContentResolver() = product.app.contentResolver
                    override fun getNoBackupFilesDir() = product.app.noBackupFilesDir
                    override fun getCacheDir() = product.app.cacheDir
                } }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val state by lifecycle.currentStateFlow.collectAsState()
                DisposableEffect(lifecycle) {
                    val events = LifecycleEventObserver { _, event -> when (event) {
                        Lifecycle.Event.ON_START -> observer.start()
                        Lifecycle.Event.ON_RESUME -> observer.foreground(true)
                        Lifecycle.Event.ON_PAUSE -> { observer.foreground(false); pool.exit(GpuPreviewExit.BACKGROUND) }
                        Lifecycle.Event.ON_STOP -> observer.stop()
                        else -> Unit
                    } }
                    lifecycle.addObserver(events)
                    onDispose { lifecycle.removeObserver(events); observer.stop() }
                }
                CompositionLocalProvider(LocalContext provides uiContext) {
                    CaptureTheme {
                        if (visible.value) GpuPhotoRollEditor(product.repository, product.renderer,
                            product.source.uri, false, "SYNTHETIC", state.isAtLeast(Lifecycle.State.RESUMED),
                            publications, host.previewTrimEpoch, { visible.value = false },
                            Modifier.fillMaxSize().safeDrawingPadding(),
                            createSession = { pool.open().also(sessions::add) })
                        else Button(onClick = { visible.value = true }) { Text("OWNER_OPEN") }
                    }
                }
            }
            try {
                ActivityScenario.launch(PhotoRollActivity::class.java).use {
                    val device = UiDevice.getInstance(instrumentation)
                    repeat(rounds) { index ->
                        await { device.findObject(By.text("OWNER_OPEN")) != null }
                        val start = SystemClock.elapsedRealtimeNanos()
                        device.findObject(By.text("OWNER_OPEN")).click()
                        await {
                            sessions.lastOrNull()?.failure?.value?.let { throw AssertionError("Editor failed", it) }
                            sessions.size == index + 1 && sessions.last().presented.value != null
                        }
                        val presented = SystemClock.elapsedRealtimeNanos()
                        val session = sessions.last()
                        device.pressBack()
                        await { !visible.value && device.findObject(By.text("OWNER_OPEN")) != null }
                        assertTrue(session.renderer.awaitClosed(10_000))
                        GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                        assertEquals(1, checkNotNull(pool.runtimeForDiagnostics).contextCreationCount)
                        SystemClock.sleep(1_000)
                        Runtime.getRuntime().gc(); System.runFinalization(); Runtime.getRuntime().gc()
                        SystemClock.sleep(1_000)
                        samples.put(JSONObject().put("iteration", index)
                            .put("openToPresentedNanos", presented - start)
                            .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()))
                    }
                    product.verify()
                }
            } finally {
                instrumentation.runOnMainSync { fixture.content = null; pool.exit(GpuPreviewExit.ACTIVITY_DESTROYED) }
                observer.stop()
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            }
            val dir = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "additional_test_output/owner-reuse")
            check(dir.mkdirs() || dir.isDirectory)
            File(dir, if (rounds == 10) "ten-editor-cycles.json" else "forty-editor-cycles.json").writeText(JSONObject().put("samples", samples)
                .put("predeclaredSecondHalfCriteria", "For 40 cycles: cycles21..40 OLS bytes/cycle <=0.0005*cycle21 native; endpoint increase <=0.01*cycle21 native. Report all samples and monotonicity; no zero-leak or Pixel claim.")
                .put("scope", "Actual Editor/Surface/Back; synthetic launcher/private256x192 source; presented callback not scanout").toString())
        }
    }

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 60_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(20)
        } while (SystemClock.uptimeMillis() < until)
        error("Editor owner reuse condition timed out")
    }
}
