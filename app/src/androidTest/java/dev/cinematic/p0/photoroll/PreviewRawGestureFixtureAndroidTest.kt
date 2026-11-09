package dev.cinematic.p0.photoroll

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

import dev.cinematic.p0.rawcapture.AndroidAutoDevelopQueue
import dev.cinematic.p0.rawcapture.DevelopExecution
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.rawcapture.DngContentVerifier
import org.junit.Assume.assumeTrue

/** 合成DNGを製品の画面・実rendererへ接続する専用機能試験。実提示時刻の合格証明ではない。 */
class PreviewRawGestureFixtureAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture = SyntheticFixtureRule(hostClassName)
    private val parallelProbe get() = instrumentation.targetContext.packageName == "dev.cinematic.p0.gpudiagnostic"
    private val hostClassName get() = if (parallelProbe) PhotoRollActivity::class.java.name
        else "dev.cinematic.p0.photoroll.PreviewPresentationActivity"

    @Test fun dragAndReleaseReachRealSyntheticRawPreview() {
        val args = InstrumentationRegistry.getArguments()
        val target = instrumentation.targetContext
        assumeTrue(args.getString(if (parallelProbe) "previewParallelProbe" else "previewRawGestureProbe") == "true" && args.getString("isolated") == "true")
        assumeTrue(parallelProbe || target.packageName == "dev.cinematic.p0.previewdiagnostic")
        // 単独の新規instrumentation processで実行。共有メディアにはアクセスしない。
        val provider = SyntheticProvider(target)
        val resolver = ContentResolver.wrap(provider)
        val root = File.createTempFile("preview-gesture-private-", ".tmp", target.cacheDir)
        check(root.delete() && root.mkdir())
        val storageReads = AtomicInteger()
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
            override fun getNoBackupFilesDir(): File { storageReads.incrementAndGet(); return root }
        }
        // 現get/create契約では、新規ownerだけがこのgetterを同期呼出しする。
        val queue = AndroidAutoDevelopQueue.get(app)
        check(storageReads.get() > 0) { "A fresh isolated process is required; an existing queue must not be reused" }
        queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
        val ready = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        DevelopExecution.updateConditions(ready.developConditions(true))
        val actual = ManualTrialPhotoRollRenderer(app, resources = { ready })
        val exactCount = AtomicInteger()
        val draftCount = AtomicInteger()
        val exactRequests = CopyOnWriteArrayList<PhotoRollRenderRequest>()
        val draftRequests = CopyOnWriteArrayList<PhotoRollRenderRequest>()
        val renderer = object : PhotoRollRenderer by actual {
            override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult =
                actual.render(request, cancellation).also { if (it is PhotoRollRenderResult.Preview) { exactRequests += request; exactCount.incrementAndGet() } }
            override fun renderDraft(request: PhotoRollRenderRequest, cancellation: CancellationSignal): PhotoRollRenderResult.Preview? =
                actual.renderDraft(request, cancellation).also { if (it != null) { draftRequests += request; draftCount.incrementAndGet() } }
        }
        val repository = PhotoRollRepository(resolver, target.packageName)
        fixture.content = {
            val host = LocalContext.current
            val uiContext = remember(host) { object : ContextWrapper(host) {
                override fun getApplicationContext(): Context = app
                override fun getContentResolver(): ContentResolver = resolver
                override fun getNoBackupFilesDir(): File = root
            } }
            CompositionLocalProvider(LocalContext provides uiContext) {
                CaptureTheme { PhotoRollScreen(repository, renderer, true) {} }
            }
        }
        if (parallelProbe) instrumentation.runOnMainSync {
            assertEquals(PhotoRollCameraSnapshot(), AndroidPhotoRollCameraObservations.snapshot())
        }
        try {
            ActivityScenario.launch<androidx.activity.ComponentActivity>(android.content.Intent().setClassName(target.packageName, hostClassName)).use {
                click(FIRST_NAME)
                await { exactCount.get() == 1 }
                if (parallelProbe) {
                    instrumentation.runOnMainSync {
                        assertEquals(PhotoRollCameraSnapshot(false, false, false, false),
                            AndroidPhotoRollCameraObservations.snapshot())
                    }
                    val initial = exactRequests.last()
                    assertEquals(1, initial.diagnosticWorkers)
                    val beforeSwitch = exactCount.get()
                    await("Switch ready before toggle") { switchControl()?.isEnabled == true }
                    click(PARALLEL_SWITCH)
                    await { exactCount.get() > beforeSwitch }
                    val enabled = exactRequests.last()
                    assertEquals(2, enabled.diagnosticWorkers)
                    assertEquals(initial.strength, enabled.strength, 0.0)
                    assertEquals(initial.adjustments, enabled.adjustments)
                    // Renderer completion precedes main-thread adoption and accessibility publication.
                    await("Candidate exact UI adopted") {
                        switchControl()?.let { it.isChecked && it.isEnabled } == true
                    }
                    snapshot("Before STRENGTH click")
                }
                click("STRENGTH")
                if (parallelProbe) {
                    snapshot("After STRENGTH action")
                    await("Adjustment panel visible") { findNode("現像調整パネル") != null }
                    await("STRENGTH range visible") { findNode("STRENGTH", true) != null }
                    await("Switch disabled with adjustment open") { switchControl()?.isEnabled == false }
                    assertFalse("Cannot switch while an adjustment is open", requireNotNull(switchControl()).isEnabled)
                    snapshot("Adjustment ready")
                }
                val beforeDrag = exactCount.get()
                val bounds = Rect().also { node("STRENGTH", true).getBoundsInScreen(it) }
                val records = PreviewGestureDriver(instrumentation.uiAutomation).drag(2,
                    bounds.exactCenterX(), bounds.exactCenterY(), -bounds.width() * .25f,
                    steps = 60, intervalMs = 32)
                assertEquals(62, records.size)
                await { exactCount.get() > beforeDrag }
                // 計算完了と画面提示は別。ここで50msや提示完了を合格にしない。
                assertTrue("Actual exact RAW renderer completed", exactCount.get() >= 2)
                assertTrue("Actual draft RAW renderer completed", draftCount.get() > 0)
                if (parallelProbe) {
                    assertTrue("Release must use candidate execution", exactRequests.drop(1).all { it.diagnosticWorkers == 2 })
                    assertTrue("Draft must use candidate execution", draftRequests.isNotEmpty() && draftRequests.all { it.diagnosticWorkers == 2 })
                    click("目盛りを閉じる")
                    await("Closed adjustment adopted before OFF") {
                        findNode("現像調整パネル") == null && switchControl()?.let { it.isChecked && it.isEnabled } == true
                    }
                    val released = exactRequests.last()
                    val beforeSwitch = exactCount.get()
                    await("Switch ready before toggle") { switchControl()?.isEnabled == true }
                    click(PARALLEL_SWITCH)
                    await { exactCount.get() > beforeSwitch }
                    val disabled = exactRequests.last()
                    assertEquals(1, disabled.diagnosticWorkers)
                    assertEquals(released.strength, disabled.strength, 0.0)
                    assertEquals(released.adjustments, disabled.adjustments)
                    await("Serial exact UI adopted") {
                        switchControl()?.let { !it.isChecked && it.isEnabled } == true
                    }
                    snapshot("Final OFF adopted")
                    println("Synthetic editor switch: OFF=1 ON=2 draft=2 release=2 OFF=1 presentation=NOT_MEASURED")
                }
                assertEquals("No shared writes", 0, provider.writes.get())
                provider.verifyUnchanged()
                println("Synthetic RAW gesture: exact=${exactCount.get()} draft=${draftCount.get()} presentation=NOT_MEASURED")
            }
            if (parallelProbe) instrumentation.runOnMainSync {
                assertEquals("Diagnostic Activity must release its camera observation",
                    PhotoRollCameraSnapshot(), AndroidPhotoRollCameraObservations.snapshot())
            }
        } finally {
            // 新しいsource readを先に拒否する。queue barrierだけでは画面側verify IOを待てない。
            provider.stopOpening()
            actual.discardTransientInputs()
            try { AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS) }
            finally { provider.close() }
            // 実行記録と後片付けの確認用に、この試験固有のprivateディレクトリは保持する。
        }
    }

    /** Inspect the actual checkable control, not a labelled descendant with different flags. */
    private fun switchControl(): AccessibilityNodeInfo? {
        var current = findNode(PARALLEL_SWITCH)
        while (current != null && !current.isCheckable) current = current.parent
        return current
    }

    /** Synthetic labels only; no image bytes, source URI or private filesystem paths. */
    private fun snapshot(checkpoint: String) {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root?.packageName?.toString() != instrumentation.targetContext.packageName) {
            android.util.Log.i("SyntheticFixture", "checkpoint=$checkpoint targetRootUnavailable=true")
            return
        }
        val labels = setOf("STRENGTH", "現像調整パネル", "目盛りを閉じる", PARALLEL_SWITCH)
        val lines = mutableListOf("checkpoint=$checkpoint root=${root?.packageName}/${root?.className}")
        fun flags(node: AccessibilityNodeInfo): String {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val range = node.rangeInfo?.let { "${it.min}..${it.max}:${it.current}" }
            return "class=${node.className} visible=${node.isVisibleToUser} enabled=${node.isEnabled} " +
                "checked=${node.isChecked} checkable=${node.isCheckable} clickable=${node.isClickable} " +
                "range=$range bounds=$bounds actions=${node.actionList.map { it.id }}"
        }
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30 || visited++ >= 1000) return
            val label = labels.firstOrNull { it == node.text?.toString() || it == node.contentDescription?.toString() }
            if (label != null || node.rangeInfo != null) {
                lines += "label=${label ?: "range"} ${flags(node)}"
                var ancestor = node.parent
                while (ancestor != null && !ancestor.isClickable && !ancestor.isCheckable) ancestor = ancestor.parent
                ancestor?.let { lines += "control-ancestor ${flags(it)}" }
            }
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(root, 0)
        lines.forEach { android.util.Log.i("SyntheticFixture", it) }
    }
    private fun findNode(label: String, range: Boolean = false): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if ((node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                node.isVisibleToUser && (!range || node.rangeInfo != null)) return node
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        return find(fixture.fixtureRoot())
    }
    private fun node(label: String, range: Boolean = false): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        await("Visible node: $label range=$range") { findNode(label, range).also { found = it } != null }
        return requireNotNull(found)
    }
    private fun click(label: String) {
        var enabled: AccessibilityNodeInfo? = null
        await("Clickable node: $label") {
            var found = findNode(label)
            while (found != null && !found.isClickable) found = found.parent
            enabled = found
            found?.isEnabled == true
        }
        assertTrue(requireNotNull(enabled).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String = "condition", condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 120_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        snapshot("Timed out: $label")
        fixture.failure(label)
        error("Synthetic RAW fixture timed out: $label")
    }
    private class SyntheticProvider(context: Context) : ContentProvider(), AutoCloseable {
        private val packageName = context.packageName
        private val files = (1..1).associateWith { id ->
            File.createTempFile("drag-screen-$id-", ".dng", context.cacheDir).apply {
                outputStream().use { writeSyntheticRollDng(it, 1152, 768, 1) }
            }
        }
        private val fingerprints = files.mapValues { (_, file) ->
            DngContentVerifier.identify(file.length(), { file.inputStream() })
        }
        fun verifyUnchanged() { files.forEach { (id, file) ->
            DngContentVerifier.verify(requireNotNull(fingerprints[id]), { file.inputStream() })
        } }
        @Volatile private var stopping = false
        @Synchronized fun stopOpening() { stopping = true }
        val reads = CopyOnWriteArrayList<ParcelFileDescriptor>()
        val writes = AtomicInteger()
        init { attachInfo(context, ProviderInfo().apply { authority = "media"; exported = false }) }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/x-adobe-dng"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection)
            val cursor = MatrixCursor(columns)
            if ("video" in uri.pathSegments) return cursor
            val id = uri.lastPathSegment?.toIntOrNull()
            val ids = if (id != null) listOf(id) else if (columns.size == 2) listOf(1) else listOf(1)
            ids.forEach { source -> cursor.addRow(Array<Any?>(columns.size) { index -> when (columns[index]) {
                MediaStore.MediaColumns._ID -> source
                OpenableColumns.DISPLAY_NAME -> FIRST_NAME
                OpenableColumns.SIZE -> requireNotNull(files[source]).length()
                MediaStore.MediaColumns.MIME_TYPE -> "image/x-adobe-dng"
                MediaStore.MediaColumns.DATE_ADDED -> (3 - source).toLong()
                MediaStore.MediaColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                MediaStore.MediaColumns.RELATIVE_PATH -> "DCIM/Synthetic/"
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME -> packageName
                MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED -> 0
                else -> null
            } }) }
            return cursor
        }
        @Synchronized override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(!stopping) { "Synthetic provider is stopping" }
            check(mode == "r") { "Read-only fixture" }
            return ParcelFileDescriptor.open(requireNotNull(files[uri.lastPathSegment?.toIntOrNull()]),
                ParcelFileDescriptor.MODE_READ_ONLY).also { reads += it }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes.incrementAndGet(); error("No shared writes") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
        override fun close() {
            stopOpening()
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (reads.any { it.fileDescriptor.valid() } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            check(reads.none { it.fileDescriptor.valid() }) { "Synthetic readers still active; sources retained" }
            verifyUnchanged()
            check(writes.get() == 0) { "Unexpected shared write attempt" }
            // sourceも保持する。未確認の非同期利用に対して強制close/deleteしない。
        }
    }

    private companion object {
        const val FIRST_NAME = "Synthetic gesture.dng"
        const val PARALLEL_SWITCH = "診断プレビュー2並列"
    }
}
