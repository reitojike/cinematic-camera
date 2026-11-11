package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Full product GPU editor with synthetic private DNGs and standard Activity lifecycle delivery.
 * Camera availability comes from the real observer. This is not physical-device or Files-app QA. */
class GpuProductEditorAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun slidersComparisonSelectionBackgroundAndRetryKeepOriginalAndOwnerBoundaries() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = instrumentation.targetContext
        val provider = GpuProductInputAndroidTest.Provider(target)
        val resolver = ContentResolver.wrap(provider)
        val root = File.createTempFile("gpu-editor-private-", ".tmp", target.cacheDir)
        check(root.delete() && root.mkdir())
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getNoBackupFilesDir(): File = root
        }
        val queue = AndroidAutoDevelopQueue.get(app)
        queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val blockedCleanup = queue.manualOriginalIo {
            cleanupEntered.countDown()
            check(releaseCleanup.await(45, TimeUnit.SECONDS)) { "Synthetic cleanup was not released" }
        }
        check(cleanupEntered.await(5, TimeUnit.SECONDS))
        val publications = MutableStateFlow(GpuInputPublication())
        val observer = PhotoRollResourceObserver(app, DevelopExecution::updateConditions,
            onPublication = { observation -> publications.update { GpuInputPublication(it.sequence + 1, observation) } })
        val renderer = ManualTrialPhotoRollRenderer(app, observer::snapshot, developEngine = DevelopEngine.GPU)
        val repository = PhotoRollRepository(resolver, target.packageName)
        val selected = mutableStateOf(1)
        val trimEpoch = mutableIntStateOf(0)
        fixture.content = {
            val host = LocalContext.current
            val uiContext = remember(host) { object : ContextWrapper(host) {
                override fun getApplicationContext(): Context = app
                override fun getContentResolver(): ContentResolver = resolver
                override fun getNoBackupFilesDir(): File = root
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
                    key(selected.value) {
                        GpuPhotoRollEditor(repository, renderer, provider.uri(selected.value).toString(), false,
                            "SYNTHETIC", state.isAtLeast(Lifecycle.State.RESUMED), publications, trimEpoch.intValue,
                            {}, Modifier.fillMaxSize().safeDrawingPadding())
                    }
                }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                await("readiness timeout is visible while old cleanup is still blocked") {
                    find("入力の準備を確認できません。再試行してください") != null
                }
                assertEquals("timeout never starts a later original read", 0, provider.opens.get())
                releaseCleanup.countDown()
                blockedCleanup.get(10, TimeUnit.SECONDS)
                click("原本を確認して再試行")
                await("first GPU presentation") { saveReady() }
                val openedReads = provider.opens.get()
                val controls = listOf("STRENGTH" to .7f, "色温度" to 30f, "明るさ" to .5f)
                for ((label, value) in controls) {
                    click(label)
                    val rangeLabel = when (label) { "色温度" -> "色温度 · 相対（K値ではありません）"; "明るさ" -> "明るさ · EV"; else -> label }
                    val range = node(rangeLabel, range = true)
                    assertTrue(range.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }))
                    // ACTION_SET_PROGRESS returns before the next accessibility snapshot.
                    // Observe the new value before checking close's updated enabled state.
                    await("new $label range value") {
                        find(rangeLabel, range = true)?.let { it.refresh() && it.rangeInfo?.current == value } == true
                    }
                    click("目盛りを閉じる")
                    await("saved recipe and exact presentation") { saveReady() }
                }
                click("比較")
                click("表示")
                assertEquals("frame/recipe edits never reopen original", openedReads, provider.opens.get())
                provider.verifyUnchanged()
                scenario.onActivity { selected.value = 2 }
                await("second photo opens") { provider.opens.get() > openedReads && saveReady() }
                val secondReads = provider.opens.get()
                scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("same photo resumes only after fresh check") { provider.opens.get() > secondReads && saveReady() }
                // Direct trim-event injection is separate from OS delivery. It releases the mounted
                // preview; a historical event must not pause the subsequently selected photograph.
                scenario.onActivity { trimEpoch.intValue++ }
                await("trim disables save") { !saveReady() }
                click("原本を確認して再試行")
                await("GPU retry after trim") { saveReady() }
                val beforeHistoricalTrim = provider.opens.get()
                scenario.onActivity { selected.value = 1 }
                await("old trim does not stop new photo") { provider.opens.get() > beforeHistoricalTrim && saveReady() }
                provider.verifyUnchanged()
                assertEquals(0, provider.writes.get())
            }
        } finally {
            releaseCleanup.countDown()
            blockedCleanup.get(10, TimeUnit.SECONDS)
            observer.stop()
            renderer.discardTransientInputs()
            AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            provider.verifyUnchanged()
            await("all original descriptors closed") { provider.readers.none { it.fileDescriptor.valid() } }
        }
    }

    private fun find(label: String, range: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if ((node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                node.isVisibleToUser && (!range || node.rangeInfo != null)) return node
            for (i in 0 until node.childCount) visit(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        return visit(fixture.fixtureRoot())
    }
    private fun actionable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }
    private fun saveReady() = actionable(find("JPEGを保存"))?.isEnabled == true
    private fun node(label: String, range: Boolean = false): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await(label) { find(label, range).also { found = it } != null }
        return checkNotNull(found)
    }
    private fun click(label: String) {
        var found: AccessibilityNodeInfo? = null
        await("enabled $label") {
            actionable(find(label))?.let { node ->
                // Re-query the live node; a cached enabled=true can precede the frame update.
                (node.refresh() && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }).also {
                    if (it) found = node
                }
            } == true
        }
        assertTrue(checkNotNull(found).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 60_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < until)
        fixture.failure(label)
        error("Synthetic GPU editor did not reach: $label")
    }
}
