package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.saveprogress.*
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Read-only product View fixture. No image, GPU session, FGS, owner, saved data or blocked worker.
 * Before is the base banner text/control contract; after uses the product message assembler.
 * Software capture is layout evidence only, not a display compositor or actual-save test. */
class SaveProgressScreenshotAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    private data class Layout(val name: String, val height: Int, val fontScale: Float)
    private data class Case(val layout: Layout, val stage: Int, val after: Boolean) {
        val name get() = "${layout.name}-${stage + 1}-${if (after) "after" else "before"}"
    }
    @Test fun captureBeforeAfterForFourPhasesAndStoppingAcrossLayouts() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val labels = listOf("保存の準備中", "画像を現像中", "JPEGを作成中", "保存しています", "保存を止めています")
        val normalAfterOnly = InstrumentationRegistry.getArguments().getString("normalAfterOnly") == "true"
        val animate = InstrumentationRegistry.getArguments().getString("recordAnimation") == "true"
        check(!animate || normalAfterOnly)
        val layouts = listOf(Layout("normal", 760, 1f), Layout("short", 480, 1f), Layout("large-text", 760, 2f))
        val cases = layouts.filter { !normalAfterOnly || it.name == "normal" }.flatMap { layout -> (0..4).flatMap { stage ->
            listOf(Case(layout, stage, false), Case(layout, stage, true)).filter { !normalAfterOnly || it.after } } }
        val shown = mutableStateOf(cases.first())
        val rendered = AtomicReference<String?>()
        val base = ForegroundSaveMessageState(true, true, false, false, true, true, true)
        fun banner(case: Case): UiMessage {
            val stopping = case.stage == 4
            return if (case.after) foregroundSaveMessages(base.copy(stopping = stopping,
                progress = SaveProgress(SaveProgressPhase.entries[case.stage.coerceAtMost(3)],
                    if (case.stage == 1) .46f else null)), activeOnly = true).single()
            else UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
                if (stopping) labels[4] else "JPEGを保存中です。アプリを離れても続きます。",
                "save.foreground_progress",
                primary = MessageControl(MessageAction.CANCEL_SAVE, "保存を取り消す", enabled = !stopping), inProgress = true)
        }
        val state = DevelopMessageState(PhotoRollPhase.DISPLAYED, PhotoRollSavePhase.SAVING, exportPaused = true)
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
            SharedOriginalReference("content://fixture/save-progress", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))
        val editor = bound.shown(bound.ticket, bound.requestedValues)
        fixture.content = {
            val current = shown.value
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, current.layout.fontScale)) {
                CaptureTheme {
                    Surface(Modifier.fillMaxSize(), color = CaptureDesign.Ink) {
                        Column(Modifier.safeDrawingPadding()) {
                            Box(Modifier.width(360.dp).height(current.layout.height.dp)) {
                                // Recreate local scroll only when layout changes; the work is a static fixture.
                                key(current.layout.name) {
                                    PhotoRollDevelopView("SYNTHETIC", PhotoRollPreviewMode.MANUAL_TRIAL,
                                        editor, { editor }, PhotoRollPhase.DISPLAYED, null, null,
                                        null, null, null, true, PhotoRollSavePhase.SAVING, true, false, false,
                                        {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {},
                                        productMessages = developMessages(state.copy(saveStopping = current.after && current.stage == 4), listOf(banner(current))),
                                        productSave = developSavePresentation(state),
                                        info = { Text("合成画面による表示確認です。", style = CaptureDesign.Body) })
                                }
                            }
                        }
                    }
                }
            }
            SideEffect { rendered.set(current.name) }
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            var recording: android.os.ParcelFileDescriptor? = null
            try {
            for (case in cases) {
                val expected = banner(case)
                assertEquals(MessageAction.CANCEL_SAVE, expected.primary?.action)
                assertNull(expected.secondary)
                scenario.onActivity { shown.value = case }
                awaitVisible(case.name, expected.body, rendered)
                assertCancelPresentation(requireNotNull(expected.primary).label, enabled = case.stage != 4)
                if (animate && recording == null) {
                    recording = instrumentation.uiAutomation.executeShellCommand(
                        "screenrecord --time-limit 18 /sdcard/Download/save-progress-animation.mp4")
                }
                if (animate) SystemClock.sleep(3000) // Artifact-only animation; no save worker exists.
                val bitmap = fixture.captureSoftware()
                try { writeImage(case, bitmap) } finally { bitmap.recycle() }
            }
            } finally {
                // Finish the bounded recording before closing this synthetic Activity, even on failure.
                recording?.let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input ->
                    input.copyTo(java.io.ByteArrayOutputStream())
                } }
            }
        }
    }
    private fun awaitVisible(name: String, label: String, rendered: AtomicReference<String?>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun contains(node: AccessibilityNodeInfo?, text: String): Boolean = node != null &&
            ((node.isVisibleToUser && (node.text?.toString() == text || node.contentDescription?.toString() == text)) ||
                (0 until node.childCount).any { contains(node.getChild(it), text) })
        repeat(100) {
            instrumentation.waitForIdleSync()
            val root = fixture.fixtureRoot()
            if (rendered.get() == name && contains(root, label) && contains(root, "保存を取り消す")) return
            SystemClock.sleep(50) // Fixture-only wait; never installed in a save worker.
        }
        fixture.failure(name)
        fail("Missing visible synthetic banner or cancellation: $name")
    }
    private fun assertCancelPresentation(label: String, enabled: Boolean) {
        val root = requireNotNull(fixture.fixtureRoot())
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && (node.text?.toString() == label ||
                node.contentDescription?.toString() == label)) matches += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        assertEquals("One visible save cancellation control; edit cancellation is a separate action", 1, matches.size)
        var control = matches.single()
        while (control.isEnabled && !control.isClickable && control.parent != null) control = control.parent
        assertEquals("Stopping disables repeat cancellation", enabled, control.isEnabled)
        val rootBounds = Rect().also(root::getBoundsInScreen)
        val bounds = Rect().also(control::getBoundsInScreen)
        assertFalse("Cancellation has nonempty bounds", bounds.isEmpty)
        assertTrue("Cancellation remains inside the visible window", rootBounds.contains(bounds))
    }
    private fun writeImage(case: Case, bitmap: Bitmap) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = configured?.let(::File) ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
        val directory = File(root, "save-progress").apply { check(isDirectory || mkdirs()) }
        File(directory, "${case.name}.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(directory, "${case.name}-metrics.txt").writeText(
            "synthetic=true; softwareViewCapture=true; fixture=360x${case.layout.height}dp; fontScale=${case.layout.fontScale}; bitmap=${bitmap.width}x${bitmap.height}")
    }
}
