package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.photoedit.PhotoEditParameter
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Presentation-only synthetic fixtures. No camera, DNG decoding or public save is invoked. */
class DevelopViewAndroidTest {
    @Test fun editingClosesBeforeSaveAndUnconfirmedDoesNotBecomePublished() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val after = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(86, 120, 135)) }
        val before = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(135, 120, 86)) }
        var initial = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
            SharedOriginalReference("content://fixture/source", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))
        initial = initial.shown(initial.ticket, initial.requestedValues).begin(.01, true)
        val state = mutableStateOf(initial)
        val phase = mutableStateOf(PhotoRollPhase.DISPLAYED)
        val savePhase = mutableStateOf(PhotoRollSavePhase.READY)
        val mode = mutableStateOf(PhotoRollPreviewMode.MANUAL_TRIAL)
        var saves = 0
        var retries = 0
        fun find(description: String): AccessibilityNodeInfo? {
            fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString() == description || node.text?.toString()?.contains(description) == true) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return walk(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun awaitNode(description: String, enabled: Boolean? = null): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5_000
            do { instrumentation.waitForIdleSync(); find(description)?.let { if (enabled == null || it.isEnabled == enabled) return it }; SystemClock.sleep(50) }
            while (SystemClock.uptimeMillis() < deadline)
            error("Missing $description")
        }
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { bitmap ->
                try { File(context.getExternalFilesDir(null), "design-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CaptureTheme {
                PhotoRollDevelopView("SYNTHETIC.dng", mode.value, state.value, { state.value }, phase.value,
                    PhotoRollRenderStage.LOOK, if (phase.value == PhotoRollPhase.BLOCKED) "合成テストの停止理由" else null,
                    after, before, "RAW基準表示（合成）", true, savePhase.value, false,
                    state.value.session == null && phase.value == PhotoRollPhase.DISPLAYED && mode.value != PhotoRollPreviewMode.PRIVATE_TRIAL,
                    true, { saves++ }, { state.value = state.value.begin(.01, true) }, {}, { retries++ },
                    { token, parameter, value -> state.value = state.value.change(token, parameter, value) },
                    { token -> state.value = state.value.reset(token) },
                    { token -> state.value = state.value.apply(token) },
                    { token -> state.value = state.value.cancel(token) },
                    { token -> state.value = state.value.close(token) }, Modifier.fillMaxSize()) { }
            } } }
            val disabledSave = awaitNode("JPEGを保存", false)
            assertFalse("editing must not enable save", disabledSave.isEnabled)
            disabledSave.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            assertEquals(0, saves)
            scenario.onActivity { state.value = state.value.change(requireNotNull(state.value.session).inputToken, PhotoEditParameter.STRENGTH, .8) }
            awaitNode("表示中 50")
            awaitNode("指定 · 未反映")
            capture("editing")
            scenario.onActivity { state.value = initial }

            scenario.onActivity { state.value = state.value.close(requireNotNull(state.value.session).inputToken) }
            instrumentation.waitForIdleSync()
            val readySave = awaitNode("JPEGを保存", true)
            assertTrue(readySave.isEnabled)
            assertTrue(readySave.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            assertEquals(1, saves)
            capture("ready")
            scenario.onActivity { savePhase.value = PhotoRollSavePhase.UNCONFIRMED }
            awaitNode("同じ保存を再照合")
            capture("unconfirmed")
            scenario.onActivity { mode.value = PhotoRollPreviewMode.PRIVATE_TRIAL }
            instrumentation.waitForIdleSync()
            assertFalse(awaitNode("同じ保存を再照合", false).isEnabled)
            capture("private")
            scenario.onActivity { phase.value = PhotoRollPhase.BLOCKED }
            instrumentation.waitForIdleSync()
            capture("blocked")
        }
        after.recycle(); before.recycle()
    }
}
