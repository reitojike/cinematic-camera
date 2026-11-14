package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.ComponentCallbacks2
import android.content.res.AssetManager
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.LocalActivity
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
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real product UI and Activity trim dispatch, with private synthetic 12MP sources.
 * onTrimMemory is directly injected, not claimed to be delivered by the OS. No export/physical QA. */
class GpuInputLifecycleSafetyAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule()

    @Suppress("DEPRECATION")
    @Test fun visibleContextLossRetryAndActivityTrimKeepOriginalAndRetireEveryPreviewOwner() {
        requireGpuInputSafetyFixture()
        val target = instrumentation.targetContext
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
        val owners = CopyOnWriteArrayList<Owner>()
        val failNext = AtomicBoolean()
        val firstFrameBarrier = AtomicReference<Pair<CountDownLatch, CountDownLatch>>()
        fun newSession(assets: AssetManager): GpuPhotoRollSession {
            val ledger = AtomicReference<GpuMemoryLedger>()
            val dimensions = AtomicReference<Pair<Int, Int>>()
            val session = GpuPhotoRollSession(assets) { egl, memory ->
                check(ledger.compareAndSet(null, memory)) { "Failed owner automatically created another engine" }
                val real = DefaultStageClearGpu(egl, memory)
                object : StageClearGpu by real {
                    override fun openPhoto(input: LinearSrgbImage, stop: () -> Boolean): StageClearGpuPhoto {
                        dimensions.set(input.width to input.height)
                        return real.openPhoto(input, stop)
                    }
                    override fun renderPreview(photo: StageClearGpuPhoto, params: GpuRenderParams,
                        target: GpuPreviewTarget, display: GpuDisplayOptions): GpuFrameStats {
                        firstFrameBarrier.getAndSet(null)?.let { (entered, release) ->
                            entered.countDown()
                            check(release.await(60, TimeUnit.SECONDS)) { "First frame barrier timed out" }
                        }
                        if (failNext.compareAndSet(true, false)) throw GpuContextLostException("Synthetic editor context loss")
                        return real.renderPreview(photo, params, target, display)
                    }
                }
            }
            owners += Owner(session, ledger, dimensions)
            return session
        }
        fixture.content = {
            val host = checkNotNull(LocalActivity.current) as PhotoRollActivity
            val uiContext = remember(host) { object : ContextWrapper(host) {
                override fun getApplicationContext(): Context = app
                override fun getContentResolver(): ContentResolver = resolver
                override fun getNoBackupFilesDir() = app.noBackupFilesDir
                override fun getCacheDir() = app.cacheDir
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
                    key(selected.intValue) {
                        GpuPhotoRollEditor(repository, renderer, provider.uri(selected.intValue).toString(), false,
                            "SYNTHETIC", state.isAtLeast(Lifecycle.State.RESUMED), publications, host.previewTrimEpoch,
                            {}, Modifier.fillMaxSize().safeDrawingPadding(), createSession = ::newSession)
                    }
                }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                await("first full-pipeline presentation") { saveReady() && owners.lastOrNull()?.session?.presented?.value != null }
                assertEquals(1024 to 768, owners.last().dimensions.get())
                val first = owners.last()
                val initialReads = provider.opens.get()
                val beforeGesture = checkNotNull(first.session.presented.value)
                click("STRENGTH")
                val bounds = android.graphics.Rect().also { awaitNode("STRENGTH", range = true).getBoundsInScreen(it) }
                val records = PreviewGestureDriver(instrumentation.uiAutomation).drag(2,
                    bounds.exactCenterX(), bounds.exactCenterY(), -bounds.width() * .25f,
                    steps = 60, intervalMs = 32)
                assertEquals(62, records.size)
                val releasedStrength = checkNotNull(awaitNode("STRENGTH", range = true).rangeInfo).current.toDouble()
                click("目盛りを閉じる")
                await("real RAW drag release reaches GPU recipe and idle save state") {
                    saveReady() && first.session.presented.value?.let {
                        it != beforeGesture && kotlin.math.abs(it.strength - releasedStrength) < .0001
                    } == true
                }
                assertEquals(initialReads, provider.opens.get())
                provider.verifyUnchanged()
                failNext.set(true)
                click("STRENGTH")
                val range = awaitNode("STRENGTH", range = true)
                assertTrue(range.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                        if (releasedStrength == .7) .6f else .7f) }))
                await("visible GPU failure") { find("GPU を使えません\nGPU の接続が失われました。再試行してください") != null }
                assertFalse(saveReady())
                assertTrue(first.session.failure.value is GpuContextLostException)
                val failed = snapshot(first)
                assertEquals(0L, failed.allocatedBytes)
                assertEquals(0, failed.windowCount)
                assertEquals(0, first.ledger.get().textureCount)
                first.session.submit(dev.cinematic.p0.photoedit.PhotoEditValues(.8, 0.0, 0.0))
                assertEquals(failed.drawCount, snapshot(first).drawCount)
                assertEquals(initialReads, provider.opens.get())
                provider.verifyUnchanged()
                val retryFrameEntered = CountDownLatch(1)
                val releaseRetryFrame = CountDownLatch(1)
                firstFrameBarrier.set(retryFrameEntered to releaseRetryFrame)
                try {
                    click("再試行")
                    assertTrue(retryFrameEntered.await(60, TimeUnit.SECONDS))
                    assertClosed(first)
                    assertEquals(2, owners.size)
                    assertNull(owners.last().session.presented.value)
                    assertFalse(saveReady())
                    await("retry remains CHECKING before first real frame") { find("原本を確認中…") != null }
                    provider.verifyUnchanged()
                } finally { releaseRetryFrame.countDown() }
                await("explicit GPU retry rechecks original and presents") {
                    owners.size == 2 && provider.opens.get() > initialReads && saveReady() &&
                        owners.last().session.presented.value != null
                }
                assertClosed(first)
                assertNull(owners.last().session.failure.value)
                assertTrue(snapshot(owners.last()).allocatedBytes > 0L)

                // A later failure must become visible again, rather than inheriting retry readiness.
                failNext.set(true)
                owners.last().session.submit(dev.cinematic.p0.photoedit.PhotoEditValues(.3, 0.0, 0.0))
                await("new failure after retry is visible and cannot save") {
                    find("GPU を使えません\nGPU の接続が失われました。再試行してください") != null && !saveReady()
                }
                val failedRetry = owners.last()
                click("再試行")
                await("second explicit retry presents") { owners.last() !== failedRetry && saveReady() }
                assertClosed(failedRetry)

                val beforeLow = owners.last()
                val lowReads = provider.opens.get()
                val beforeLowSnapshot = snapshot(beforeLow)
                scenario.onActivity { it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
                instrumentation.waitForIdleSync()
                queue.manualOriginalIo { Unit }.get(30, TimeUnit.SECONDS)
                assertSame(beforeLow, owners.last())
                assertTrue(saveReady())
                assertEquals(lowReads, provider.opens.get())
                assertEquals(beforeLowSnapshot.allocatedBytes, snapshot(beforeLow).allocatedBytes)
                assertTrue(beforeLow.ledger.get().textureCount > 0)

                for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)) {
                    val old = owners.last()
                    val count = owners.size
                    val reads = provider.opens.get()
                    scenario.onActivity { it.onTrimMemory(level) }
                    await("trim disables save") { !saveReady() }
                    assertClosed(old)
                    queue.manualOriginalIo { Unit }.get(30, TimeUnit.SECONDS)
                    assertEquals(count, owners.size)
                    assertEquals(reads, provider.opens.get())
                    provider.verifyUnchanged()
                    click("原本を確認して再試行")
                    await("trim retry opens a new GPU owner") {
                        owners.size == count + 1 && provider.opens.get() > reads && saveReady() &&
                            owners.last().session.presented.value != null
                    }
                }
                val beforeBackground = owners.last()
                val backgroundReads = provider.opens.get()
                scenario.moveToState(Lifecycle.State.CREATED)
                assertClosed(beforeBackground)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await("foreground rechecks and presents") {
                    owners.last() !== beforeBackground && provider.opens.get() > backgroundReads && saveReady()
                }
                repeat(10) { iteration ->
                    val retired = owners.last()
                    val readsBeforeRecreate = provider.opens.get()
                    scenario.recreate()
                    await("Activity recreation $iteration rechecks and draws") {
                        owners.last() !== retired && provider.opens.get() > readsBeforeRecreate &&
                            saveReady() && owners.last().session.presented.value != null
                    }
                    assertClosed(retired)
                    val live = snapshot(owners.last())
                    assertEquals(1, live.windowCount)
                    assertTrue(live.allocatedBytes > 0L)
                    owners.dropLast(1).forEach(::assertClosed)
                }
                val previousPhoto = owners.last()
                val reads = provider.opens.get()
                scenario.onActivity { selected.intValue = 2 }
                await("other photo opens after historical trim") {
                    owners.last() !== previousPhoto && provider.opens.get() > reads && saveReady()
                }
                assertClosed(previousPhoto)
                assertEquals(768 to 1024, owners.last().dimensions.get())
                val oldInputEntered = CountDownLatch(1)
                val releaseOldInput = CountDownLatch(1)
                val holdFirst = AtomicBoolean(true)
                GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                    override fun preflightOpened() {
                        if (holdFirst.compareAndSet(true, false)) {
                            oldInputEntered.countDown()
                            check(releaseOldInput.await(30, TimeUnit.SECONDS))
                        }
                    }
                }).use {
                    try {
                        scenario.onActivity { selected.intValue = 1 }
                        assertTrue(oldInputEntered.await(30, TimeUnit.SECONDS))
                        val cancelledOwner = owners.last()
                        scenario.onActivity { selected.intValue = 2 }
                        scenario.moveToState(Lifecycle.State.CREATED)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        releaseOldInput.countDown()
                        await("latest photo wins over cancelled input after pause and resume") {
                            owners.last() !== cancelledOwner && owners.last().dimensions.get() == (768 to 1024) &&
                                owners.last().session.presented.value != null && saveReady()
                        }
                        assertNull(owners.last().session.failure.value)
                        assertClosed(cancelledOwner)
                        owners.dropLast(1).forEach(::assertClosed)
                    } finally { releaseOldInput.countDown() }
                }
                val trimInputEntered = CountDownLatch(1)
                val releaseTrimInput = CountDownLatch(1)
                val holdTrimInput = AtomicBoolean(true)
                GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                    override fun preflightOpened() {
                        if (holdTrimInput.compareAndSet(true, false)) {
                            trimInputEntered.countDown()
                            check(releaseTrimInput.await(60, TimeUnit.SECONDS))
                        }
                    }
                }).use {
                    try {
                        scenario.onActivity { selected.intValue = 1 }
                        assertTrue(trimInputEntered.await(60, TimeUnit.SECONDS))
                        val trimmedInput = owners.last()
                        scenario.onActivity { it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
                        await("in-flight trim blocks saving") { !saveReady() }
                        releaseTrimInput.countDown()
                        queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
                        assertClosed(trimmedInput)
                        assertNull(trimmedInput.session.presented.value)
                        val closedCount = owners.size
                        val closedReads = provider.opens.get()
                        instrumentation.waitForIdleSync()
                        queue.manualOriginalIo { Unit }.get(30, TimeUnit.SECONDS)
                        assertEquals(closedCount, owners.size)
                        assertEquals(closedReads, provider.opens.get())
                        provider.verifyUnchanged()
                        click("原本を確認して再試行")
                        await("trimmed input requires a fresh explicitly requested generation") {
                            owners.size == closedCount + 1 && owners.last() !== trimmedInput &&
                                provider.opens.get() > closedReads && owners.last().session.presented.value != null && saveReady()
                        }
                        assertEquals(1024 to 768, owners.last().dimensions.get())
                        assertNull(trimmedInput.session.presented.value)
                        owners.dropLast(1).forEach(::assertClosed)
                    } finally { releaseTrimInput.countDown() }
                }
                provider.verifyUnchanged()
                assertEquals(0, provider.writes.get())
            }
        } finally {
            run {
                observer.stop()
                owners.forEach { it.session.close() }
                owners.forEach(::assertClosed)
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                renderer.discardTransientInputs()
                AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
                provider.verifyUnchanged()
                assertTrue(provider.readers.none { it.fileDescriptor.valid() })
                assertFalse(root.walkTopDown().any { it.name == "source-snapshot.dng" || it.name == "source-linear.rgb16" })
            }
        }
    }

    private data class Owner(val session: GpuPhotoRollSession, val ledger: AtomicReference<GpuMemoryLedger>,
        val dimensions: AtomicReference<Pair<Int, Int>>)
    private fun assertClosed(owner: Owner) {
        assertTrue("old input/callback/GL thread must finish", owner.session.renderer.awaitClosed(10_000))
        owner.ledger.get()?.let { assertEquals(0L, it.allocatedBytes); assertEquals(0, it.textureCount) }
    }
    private fun snapshot(owner: Owner): GpuPreviewSnapshot {
        val values = LinkedBlockingQueue<GpuPreviewSnapshot>()
        owner.session.renderer.snapshot { values.offer(it) }
        return checkNotNull(values.poll(10, TimeUnit.SECONDS))
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
        return current?.takeIf { it.refresh() && it.isVisibleToUser && it.isEnabled &&
            it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK } }
    }
    private fun saveReady() = actionable(find("JPEGを保存")) != null
    private fun awaitNode(label: String, range: Boolean = false): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        await(label) { find(label, range).also { node = it } != null }
        return checkNotNull(node)
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await("enabled $label") { actionable(find(label)).also { node = it } != null }
        assertTrue(checkNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
        error("Synthetic product lifecycle did not reach: $label")
    }
}
