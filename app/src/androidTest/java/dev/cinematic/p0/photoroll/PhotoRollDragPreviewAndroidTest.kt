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

/** Actual screen and pointer flow, with synthetic provider bytes and a controlled display renderer.
 * No RAW decoding, camera, shared-media writes, publication, timing or real-device quality claims.
 * Run only on the shared GMD ATD with isolated=true. Pixels are software View-tree evidence.
 */
class PhotoRollDragPreviewAndroidTest {
    @get:Rule val fixture = SyntheticFixtureRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun continuousDragDisplaysCompletedFramesAndReleaseRejectsTheOldDraft() = screen { renderer, _ ->
        val initial = open(renderer)
        val drag = beginDrag()
        drag.move(48f)
        val first = renderer.next(draft = true)
        drag.move(96f)
        drag.move(120f)
        assertFalse("New pointer values must not cancel an active coarse render", first.cancellation.isCanceled)
        first.complete(Color.RED)
        val second = renderer.next(draft = true)
        drag.move(150f)
        awaitColor(Color.RED) // The pointer is still down, with a newer value already pending.
        assertTrue(second.request.strength > first.request.strength)
        assertEquals(1, renderer.calls.count { !it.draft })
        second.complete(Color.GREEN)
        val stale = renderer.next(draft = true)
        drag.move(180f)
        awaitColor(Color.GREEN)
        assertFalse("Close must not accept display-only pixels", action("目盛りを閉じる").isEnabled)
        assertFalse(action("JPEGを保存").isEnabled)

        drag.up()
        stale.complete(Color.MAGENTA) // Deliberately ignores cancellation to exercise stale-result disposal.
        val exact = renderer.next(draft = false)
        assertTrue(exact.request.refreshDecisions)
        assertTrue(exact.request.strength > stale.request.strength)
        await("Stale coarse pair recycled") { stale.recycled() }
        assertFalse(hasColor(Color.MAGENTA))
        exact.complete(Color.CYAN)
        awaitColor(Color.CYAN)
        await("Old displayed pairs recycled") { initial.recycled() && first.recycled() && second.recycled() }
        assertTrue(action("目盛りを閉じる").isEnabled)
        assertEquals(0, renderer.saves.get())
        assertEquals("Exact and coarse renderer owners must never overlap", 1, renderer.maximumActive.get())
    }

    @Test fun returningToTheCommittedValueStillRestoresExactPixelsOnRelease() = screen { renderer, _ ->
        open(renderer)
        val drag = beginDrag()
        drag.move(72f)
        val changed = renderer.next(draft = true)
        changed.complete(Color.RED)
        awaitColor(Color.RED)
        drag.move(0f) // Cross the baseline: the real ruler snaps back to the committed 0.5.
        val unchanged = renderer.next(draft = true)
        assertEquals(.5, unchanged.request.strength, 0.0)
        unchanged.complete(Color.GREEN)
        awaitColor(Color.GREEN)
        assertFalse(action("目盛りを閉じる").isEnabled)
        drag.up()
        val exact = renderer.next(draft = false)
        assertEquals(.5, exact.request.strength, 0.0)
        assertTrue("Unchanged-value release must refresh exact decisions", exact.request.refreshDecisions)
        exact.complete(Color.CYAN)
        awaitColor(Color.CYAN)
        await("Draft display pair recycled") { unchanged.recycled() }
        assertTrue(action("目盛りを閉じる").isEnabled)
        assertEquals(0, renderer.saves.get())
    }

    @Test fun cancelPhotoSwitchAndCloseRejectPendingDraftsAndReleaseTheirPixels() = screen { renderer, _ ->
        open(renderer)
        val cancelledDrag = beginDrag()
        cancelledDrag.move(72f)
        val cancelled = renderer.next(draft = true)
        cancelled.complete(Color.RED)
        awaitColor(Color.RED)
        cancelledDrag.move(120f)
        val pendingCancel = renderer.next(draft = true)
        click("取消")
        cancelledDrag.cancel()
        pendingCancel.complete(Color.MAGENTA)
        val restored = renderer.next(draft = false)
        assertEquals(.5, restored.request.strength, 0.0)
        restored.complete(Color.CYAN)
        awaitColor(Color.CYAN)
        await("Cancel releases displayed and rejected draft pairs") { cancelled.recycled() && pendingCancel.recycled() }

        val switchedDrag = beginDrag()
        switchedDrag.move(72f)
        val oldPhoto = renderer.next(draft = true)
        val discardedBeforeSwitch = renderer.discards.get()
        click("一覧へ戻る")
        switchedDrag.cancel()
        click(SECOND_NAME)
        oldPhoto.complete(Color.MAGENTA)
        val otherPhoto = renderer.next(draft = false)
        assertNotEquals(oldPhoto.request.original.uri, otherPhoto.request.original.uri)
        assertEquals(.5, otherPhoto.request.strength, 0.0)
        otherPhoto.complete(Color.GREEN)
        awaitColor(Color.GREEN)
        await("Photo switch releases old pixels") { oldPhoto.recycled() && restored.recycled() }
        assertTrue(renderer.discards.get() > discardedBeforeSwitch)

        val closedDrag = beginDrag()
        closedDrag.move(72f)
        val pendingClose = renderer.next(draft = true)
        click("一覧へ戻る")
        closedDrag.cancel()
        click("‹ 撮影") // Actual closeRoll callback removes the screen composition.
        node("Synthetic screen closed")
        assertTrue(pendingClose.cancellation.isCanceled)
        pendingClose.complete(Color.MAGENTA)
        await("Closing releases all renderer display copies") { renderer.calls.all { it.recycled() } }
        assertEquals(0, renderer.saves.get())
    }

    @Test fun backgroundCancellationCannotAdoptLatePixelsAndResumeRechecksTheSource() = screen { renderer, scenario ->
        open(renderer)
        val drag = beginDrag()
        drag.move(72f)
        val stopped = renderer.next(draft = true)
        scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(stopped.cancellation.isCanceled)
        stopped.complete(Color.MAGENTA)
        await("Backgrounded draft pair recycled") { stopped.recycled() }
        scenario.moveToState(Lifecycle.State.RESUMED)
        val resumed = renderer.next(draft = false)
        assertEquals(.5, resumed.request.strength, 0.0)
        resumed.complete(Color.CYAN)
        awaitColor(Color.CYAN)
        assertFalse(hasColor(Color.MAGENTA))
        assertEquals(0, renderer.saves.get())
    }

    @Test fun producerCancellationDoesNotStopFutureDraftsWhileTheScreenRemainsActive() = screen { renderer, _ ->
        open(renderer)
        val interruptedDrag = beginDrag()
        interruptedDrag.move(72f)
        val interrupted = renderer.next(draft = true)
        interrupted.cancelProducer()
        await("Producer cancellation returned") { interrupted.finished }
        awaitColor(Color.BLUE)
        interruptedDrag.up()
        renderer.next(draft = false).complete(Color.CYAN)
        awaitColor(Color.CYAN)

        val nextDrag = beginDrag()
        nextDrag.move(72f)
        renderer.next(draft = true).complete(Color.GREEN)
        awaitColor(Color.GREEN) // The long-lived screen actor must still be collecting.
        nextDrag.up()
        renderer.next(draft = false).complete(Color.BLUE)
        awaitColor(Color.BLUE)
        assertEquals(0, renderer.saves.get())
    }

    private fun screen(block: (ControlledRenderer, ActivityScenario<PhotoRollActivity>) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val provider = SyntheticProvider(instrumentation.targetContext)
        val resolver = ContentResolver.wrap(provider)
        val repository = PhotoRollRepository(resolver, instrumentation.targetContext.packageName)
        val renderer = ControlledRenderer()
        val visible = mutableStateOf(true)
        fixture.content = {
            val host = LocalContext.current
            val context = remember(host) { object : ContextWrapper(host) {
                override fun getContentResolver(): ContentResolver = resolver
            } }
            CompositionLocalProvider(LocalContext provides context) {
                CaptureTheme {
                    if (visible.value) PhotoRollScreen(repository, renderer, true) { visible.value = false }
                    else Text("Synthetic screen closed")
                }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                try { block(renderer, scenario) }
                finally { renderer.releaseAll() }
            }
            await("Screen disposal releases every transferred display pair") { renderer.calls.all { it.recycled() } }
            await("Repository source descriptors closed") { provider.reads.all { !it.fileDescriptor.valid() } }
            assertEquals(0, provider.writes.get())
        } finally {
            renderer.releaseAll()
            provider.close()
        }
    }

    private fun open(renderer: ControlledRenderer): RenderCall {
        click(FIRST_NAME)
        return renderer.next(draft = false).also {
            assertFalse(it.request.refreshDecisions)
            it.complete(Color.BLUE)
            awaitColor(Color.BLUE)
        }
    }

    private fun beginDrag(): Drag {
        if (findNode("STRENGTH", range = true) == null) click("STRENGTH")
        val bounds = Rect().also { node("STRENGTH", range = true).getBoundsInScreen(it) }
        return Drag(bounds.exactCenterX(), bounds.exactCenterY()).also { it.down() }
    }

    private inner class Drag(private val x: Float, private val y: Float) {
        private var downTime = 0L
        private var currentX = x
        private fun touch(action: Int) {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, currentX, y, 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            instrumentation.waitForIdleSync()
        }
        fun down() = touch(MotionEvent.ACTION_DOWN)
        fun move(dp: Float) { currentX = x - dp * instrumentation.targetContext.resources.displayMetrics.density; touch(MotionEvent.ACTION_MOVE) }
        fun up() = touch(MotionEvent.ACTION_UP)
        fun cancel() = touch(MotionEvent.ACTION_CANCEL)
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
        await("Reachable $label") { findNode(label, range).also { found = it } != null }
        return requireNotNull(found)
    }

    private fun action(label: String): AccessibilityNodeInfo {
        var found = node(label)
        while (!found.isClickable) found = found.parent ?: return found
        return found
    }

    private fun click(label: String) {
        val found = action(label)
        assertTrue("Enabled $label", found.isEnabled)
        assertTrue("Click $label", found.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun hasColor(color: Int): Boolean {
        val bitmap = fixture.captureSoftware()
        try {
            // Sample the full view because the preview moves when the editor opens or closes.
            var count = 0
            for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8)
                if (bitmap.getPixel(x, y) == color && ++count >= 20) return true
            return false
        } finally { bitmap.recycle() }
    }

    private fun awaitColor(color: Int) = await("Displayed color ${Integer.toHexString(color)}") { hasColor(color) }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        fixture.failure(label)
        fail(label)
    }

    private class RenderCall(val request: PhotoRollRenderRequest, val cancellation: CancellationSignal, val draft: Boolean) {
        private val release = CountDownLatch(1)
        @Volatile private var color = Color.MAGENTA
        @Volatile private var producerCancelled = false
        @Volatile var finished = false
        @Volatile var result: PhotoRollRenderResult.Preview? = null
        fun complete(color: Int) { this.color = color; release.countDown() }
        fun cancelProducer() { producerCancelled = true; release.countDown() }
        fun pixels(): Pair<Bitmap, Bitmap> {
            check(release.await(30, TimeUnit.SECONDS)) { "Test did not release ${if (draft) "draft" else "exact"} renderer" }
            if (producerCancelled) throw CancellationException("Synthetic optional producer cancellation")
            fun bitmap(value: Int) = Bitmap.createBitmap(request.maxEdge, request.maxEdge, Bitmap.Config.ARGB_8888).apply { eraseColor(value) }
            return bitmap(color) to bitmap(Color.YELLOW)
        }
        fun recycled() = finished && (result?.let { it.bitmap.isRecycled && it.comparisonBitmap?.isRecycled == true } ?: true)
    }

    private class ControlledRenderer : PhotoRollRenderer {
        override val mode = PhotoRollPreviewMode.MANUAL_TRIAL
        val calls = CopyOnWriteArrayList<RenderCall>()
        val discards = AtomicInteger()
        val saves = AtomicInteger()
        private val pending = LinkedBlockingQueue<RenderCall>()
        private val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        override fun discardTransientInputs() { discards.incrementAndGet() }
        override fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal) = render(request, cancellation, false)
        override fun renderDraft(request: PhotoRollRenderRequest, cancellation: CancellationSignal) = render(request, cancellation, true)
        private fun render(request: PhotoRollRenderRequest, cancellation: CancellationSignal, draft: Boolean): PhotoRollRenderResult.Preview {
            val call = RenderCall(request, cancellation, draft)
            val count = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, count) }
            calls += call
            pending.put(call)
            try {
                val (after, before) = call.pixels()
                val owner = if (draft) null else object : PhotoRollJpegSaveOwner {
                    override val snapshot = PhotoRollSaveSnapshot(PhotoRollSavePhase.READY)
                    override val snapshots = flowOf(snapshot)
                    override val width = 0
                    override val height = 0
                    override val appliedStrength = request.strength
                    override val appliedAdjustments = request.adjustments
                    override fun matches(source: SharedOriginalReference, mode: PhotoRollPreviewMode) =
                        source == request.original.reference && mode == PhotoRollPreviewMode.MANUAL_TRIAL
                    override fun advance(shouldStop: () -> Boolean): PhotoRollSaveSnapshot {
                        saves.incrementAndGet()
                        error("Synthetic screen test must never publish")
                    }
                }
                return PhotoRollRenderResult.Preview(after, mode, jpegSave = owner, comparisonBitmap = before,
                    appliedStrength = request.strength.takeUnless { draft },
                    appliedAdjustments = request.adjustments.takeUnless { draft },
                    comparisonDescription = "Synthetic baseline").also { call.result = it }
            } finally { call.finished = true; active.decrementAndGet() }
        }
        fun next(draft: Boolean): RenderCall {
            val call = requireNotNull(pending.poll(8, TimeUnit.SECONDS)) { "No ${if (draft) "draft" else "exact"} request" }
            assertEquals("Render kind", draft, call.draft)
            assertEquals("Render edge", if (draft) 256 else 1024, call.request.maxEdge)
            return call
        }
        fun releaseAll() { calls.forEach { it.complete(Color.MAGENTA) } }
    }

    /** Local-only resolver: every query/read, including thumbnail attempts, stays in this provider.
     * Its bytes are identity fixtures, deliberately not actual DNGs. Only the fake renderer decodes.
     */
    private class SyntheticProvider(context: Context) : ContentProvider(), AutoCloseable {
        private val packageName = context.packageName
        private val files = (1..2).associateWith { id ->
            File.createTempFile("drag-screen-$id-", ".bin", context.cacheDir).apply {
                writeText("Synthetic source $id ${System.nanoTime()}")
            }
        }
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
            val ids = if (id != null) listOf(id) else if (columns.size == 2) listOf(1) else listOf(1, 2)
            ids.forEach { source -> cursor.addRow(Array<Any?>(columns.size) { index -> when (columns[index]) {
                MediaStore.MediaColumns._ID -> source
                OpenableColumns.DISPLAY_NAME -> if (source == 1) FIRST_NAME else SECOND_NAME
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
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r") { "Read-only fixture" }
            return ParcelFileDescriptor.open(requireNotNull(files[uri.lastPathSegment?.toIntOrNull()]),
                ParcelFileDescriptor.MODE_READ_ONLY).also { reads += it }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes.incrementAndGet(); error("No shared writes") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No shared writes") }
        override fun close() { reads.forEach { it.close() }; files.values.forEach { check(it.delete()) } }
    }

    private companion object {
        const val FIRST_NAME = "Synthetic drag A.dng"
        const val SECOND_NAME = "Synthetic drag B.dng"
    }
}
