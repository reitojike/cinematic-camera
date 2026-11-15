package dev.cinematic.p0.photoroll

import android.graphics.Rect
import android.graphics.Bitmap
import java.io.File
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditParameter
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.rawcapture.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Additional PR-D regression tests. Synthetic model + real product layout + empty GPU Surface.
 * No RAW/photo input, save owner, real save/import, or physical device. Presented values below
 * are explicitly fixture-controlled inputs: this checks UI propagation, NOT GPU frame correctness.
 * Requires isolated emulator ownership; this class injects touch and scroll events.
 */
class PrDDevelopInteractionsAndroidTest {
    @get:Rule val fixture = SyntheticFixtureRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val source = SharedOriginalReference("content://fixture/pr-d-interactions",
        OriginalFingerprint(100, "b".repeat(64)), OriginalGrant.USER_SELECTED)

    private class Model(val shown: TrialStrengthEditor) {
        var editor by mutableStateOf(shown)
        var presented by mutableStateOf<PhotoEditValues?>(shown.requestedValues)
        var compact by mutableStateOf(false)
        var failure by mutableStateOf(false)
        var cancelled by mutableStateOf(false)
        var banner by mutableStateOf(false)
    }

    private fun withScreen(block: (ActivityScenario<PhotoRollActivity>, Model) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(source))
        val model = Model(bound.shown(bound.ticket, bound.requestedValues))
        val gpu = GpuPhotoRollSession(instrumentation.targetContext.assets)
        fixture.content = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (model.compact) 1.3f else 1f)) {
                CaptureTheme {
                    Box(if (model.compact) Modifier.fillMaxWidth().height(500.dp) else Modifier.fillMaxSize()) {
                        val state = DevelopMessageState(
                            phase = if (model.failure) PhotoRollPhase.BLOCKED else PhotoRollPhase.DISPLAYED,
                            savePhase = if (model.cancelled) PhotoRollSavePhase.CANCELLED else PhotoRollSavePhase.READY,
                            sourceInvalid = model.failure, exportPaused = model.cancelled,
                            message = "原本の読み取り権限がありません。Filesから選び直してください。",
                            canLeave = true, canResumePreview = model.cancelled)
                        val messages = developMessages(state, if (model.banner) listOf(UiMessage(
                            MessageSlot.BANNER, CaptureChromeTone.WARNING,
                            "別の写真のJPEGを保存中です。アプリを離れても続きます。", "fixture.other_save",
                            primary = MessageControl(MessageAction.CANCEL_SAVE, "保存を取り消す"), inProgress = true)) else emptyList())
                        PhotoRollDevelopView("SYNTHETIC.dng", PhotoRollPreviewMode.MANUAL_TRIAL,
                            model.editor, { model.editor }, state.phase, null, null, null, null, null,
                            true, state.savePhase, false, false, false,
                            {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {},
                            modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                            gpuSession = gpu.takeUnless { model.failure || model.cancelled },
                            gpuReady = !model.failure && !model.cancelled,
                            gpuPresentedValues = model.presented,
                            productMessages = messages, productSave = developSavePresentation(state), info = {})
                    }
                }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { block(it, model) }
        } finally {
            fixture.content = null
            gpu.close()
            assertTrue("Empty fixture GPU owner must close", gpu.renderer.awaitClosed(5_000))
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
        }
    }

    @Test fun shortScreenScrollReachesFooterAndNavigationWithPositiveSurface() = withScreen { scenario, model ->
        scenario.onActivity {
            model.compact = true
            model.editor = model.shown.begin(.01, true)
            model.banner = true
        }
        visible("現像結果")
        assertSurfacePositive(scenario)
        val expectedFooter = developSavePresentation(DevelopMessageState(PhotoRollPhase.DISPLAYED,
            PhotoRollSavePhase.READY)).description
        scrollUntilVisible(expectedFooter, forward = true)
        assertTrue("footer must have nonempty visible bounds", rect(visible(expectedFooter)).height() > 0)
        captureEvidence("compact-editing-scrolled-footer")
        scrollUntilVisible("一覧へ戻る", forward = false)
        visible("一覧へ戻る") // Reachable without invoking navigation.
        for (failure in listOf(true, false)) {
            scenario.onActivity {
                model.editor = model.shown
                model.banner = false
                model.failure = failure
                model.cancelled = !failure
            }
            val action = if (failure) "一覧に戻る" else "プレビューを再開"
            scrollUntilVisible(action, forward = true)
            visible(action)
            captureEvidence(if (failure) "compact-source-failure-action" else "compact-cancelled-resume-action")
            val savePhase = if (failure) PhotoRollSavePhase.READY else PhotoRollSavePhase.CANCELLED
            val footer = developSavePresentation(DevelopMessageState(
                if (failure) PhotoRollPhase.BLOCKED else PhotoRollPhase.DISPLAYED, savePhase)).description
            scrollUntilVisible(footer, forward = true)
            visible(footer)
            scrollUntilVisible("一覧へ戻る", forward = false)
        }
    }

    @Test fun captionTracksPresentedInputWithoutUnlockingDraft() = withScreen { scenario, model ->
        scenario.onActivity { model.presented = null }
        visible("現像結果 · 更新中")
        captureEvidence("caption-initial-pending")
        scenario.onActivity { model.presented = model.shown.requestedValues }
        visible("現像結果")
        assertAbsent("現像結果 · 更新中")
        val editing = model.shown.begin(.01, true)
        val draft = editing.change(requireNotNull(editing.session).inputToken, PhotoEditParameter.STRENGTH, .7)
        scenario.onActivity { model.editor = draft }
        visible("現像結果 · 更新中")
        captureEvidence("caption-draft-pending")
        val originalDisplayed = draft.displayedValues
        scenario.onActivity { model.presented = requireNotNull(draft.session).draft }
        visible("現像結果")
        assertAbsent("現像結果 · 更新中")
        assertSame("presentation must not replace editor", draft, model.editor)
        assertEquals("caption does not confirm committed values", originalDisplayed, model.editor.displayedValues)
        assertTrue("pending edit remains pending", model.editor.hasUnappliedChanges)
        captureEvidence("caption-draft-presented")
    }

    @Test fun comparisonCustomActionAndLongPressPreserveLatchedMode() = withScreen { scenario, _ ->
        visible("現像結果")
        assertSurfacePositive(scenario)
        performComparisonAction("RAWの基準表示に切り替える")
        visible("基準表示（撮影時WB・調整なし）")
        SystemClock.sleep(250)
        visible("基準表示（撮影時WB・調整なし）") // Toggle persists, unlike hold.
        captureEvidence("comparison-latched-before")
        longPressAndRelease(expectAfter = "基準表示（撮影時WB・調整なし）")
        performComparisonAction("現像結果へ戻す")
        visible("現像結果")
        longPressAndRelease(expectAfter = "現像結果")
    }

    private fun longPressAndRelease(expectAfter: String) {
        val target = rect(visible("現像結果（表示用）"))
        assertTrue(target.width() > 0 && target.height() > 0)
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                target.exactCenterX(), target.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { check(fixture.automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN)
        try {
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 150)
            visible("基準表示（撮影時WB・調整なし）")
            captureEvidence(if (expectAfter == "現像結果") "long-press-from-after" else "long-press-while-latched")
        } finally { send(MotionEvent.ACTION_UP) }
        visible(expectAfter)
    }

    /** Full software UI evidence only; empty Surface pixels do not represent a rendered photograph. */
    private fun captureEvidence(name: String) {
        val output = File(requireNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")),
            "ui-pr-d-interactions")
        check(output.isDirectory || output.mkdirs())
        val bitmap = fixture.captureSoftware()
        try {
            File(output, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            val resources = instrumentation.targetContext.resources
            File(output, "$name.txt").writeText(
                "Synthetic model + empty GPU Surface. Software View-tree evidence, not photo/GPU-quality or save proof.\n" +
                "bitmap=${bitmap.width}x${bitmap.height}; density=${resources.displayMetrics.density}; " +
                "deviceFontScale=${resources.configuration.fontScale}; compact fixture overrides fontScale=1.3.\n")
        } finally { bitmap.recycle() }
    }

    private fun performComparisonAction(label: String) {
        val image = visible("現像結果（表示用）")
        val action = image.actionList.single { it.label?.toString() == label }
        check(image.performAction(action.id)) { "Custom action not accepted: $label" }
    }

    private fun assertSurfacePositive(scenario: ActivityScenario<PhotoRollActivity>) {
        fun surfaces(view: View): List<SurfaceView> = when (view) {
            is SurfaceView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { surfaces(view.getChildAt(it)) }
            else -> emptyList()
        }
        // Outer image bounds alone are insufficient: caption+banner must leave the real Surface room.
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val surface = surfaces(activity.window.decorView).single()
            val visible = Rect()
            assertTrue("Surface has positive measured size", surface.width > 0 && surface.height > 0)
            assertTrue("Surface is visibly inside viewport", surface.getGlobalVisibleRect(visible))
            assertTrue(visible.width() > 0 && visible.height() > 0)
        }
    }

    private fun rect(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> =
        if (node == null) emptyList() else listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }
    private fun rootNodes() = nodes(fixture.fixtureRoot())
    private fun matches(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString() == label || node.contentDescription?.toString() == label
    private fun visibleOrNull(label: String): AccessibilityNodeInfo? = rootNodes().firstOrNull {
        matches(it, label) && it.isVisibleToUser && rect(it).let { r -> r.width() > 0 && r.height() > 0 }
    }
    private fun visible(label: String): AccessibilityNodeInfo {
        repeat(100) {
            instrumentation.waitForIdleSync()
            visibleOrNull(label)?.let { return it }
            SystemClock.sleep(30)
        }
        error("No visible node: $label")
    }
    private fun assertAbsent(label: String) {
        instrumentation.waitForIdleSync()
        assertNull("Old caption must disappear", visibleOrNull(label))
    }
    private fun scrollUntilVisible(label: String, forward: Boolean) {
        repeat(15) {
            instrumentation.waitForIdleSync()
            if (visibleOrNull(label) != null) return
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val scroll = rootNodes().filter { it.isScrollable && it.actionList.any { a -> a.id == action } }.maxByOrNull { rect(it).height() }
                ?: error("No scroll container while seeking $label")
            check(scroll.performAction(action)) { "Scroll rejected while seeking $label" }
            SystemClock.sleep(150)
        }
        error("Could not scroll to $label")
    }
}