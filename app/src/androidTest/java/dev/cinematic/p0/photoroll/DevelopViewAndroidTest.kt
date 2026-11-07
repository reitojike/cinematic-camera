package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
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
    @get:org.junit.Rule val diagnostic = SyntheticFixtureRule()
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
                if (node.contentDescription?.toString() == description || node.text?.toString() == description) return node
                for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                return null
            }
            return walk(diagnostic.fixtureRoot())
        }
        fun actionNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var current = node
            while (!current.isClickable) current = current.parent ?: return node
            return current
        }
        fun scroll(horizontal: Boolean, forward: Boolean = true) {
            fun visit(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                val target = if (horizontal) "android.widget.HorizontalScrollView" else "android.widget.ScrollView"
                if (node.className == target && node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
                for (i in 0 until node.childCount) if (visit(node.getChild(i))) return true
                return false
            }
            visit(diagnostic.fixtureRoot())
            SystemClock.sleep(180) // Let the accessibility scroll animation reach its viewport.
            instrumentation.waitForIdleSync()
        }
        fun awaitNode(description: String, enabled: Boolean? = null): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var attempts = 0
            do {
                instrumentation.waitForIdleSync()
                find(description)?.let {
                    // Android can expose the label as a child of the actionable Compose node.
                    val node = if (enabled == null) it else actionNode(it)
                    if (node.isVisibleToUser && (enabled == null || node.isEnabled == enabled)) return node
                }
                if (++attempts % 5 == 0) scroll(false, (attempts / 15) % 2 == 0)
                SystemClock.sleep(50)
            } while (SystemClock.uptimeMillis() < deadline)
            diagnostic.failure(description)
            error("Missing $description (expected enabled=$enabled, observed=${find(description)?.let(::actionNode)?.isEnabled})")
        }
        fun capture(name: String) {
            instrumentation.waitForIdleSync()
            requireNotNull(diagnostic.automation.takeScreenshot()).let { bitmap ->
                try { File(context.getExternalFilesDir(null), "design-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }
        diagnostic.content = { CaptureTheme { Surface {
                PhotoRollDevelopView("SYNTHETIC_LONG_NAME_".repeat(12) + ".dng", mode.value, state.value, { state.value }, phase.value,
                    PhotoRollRenderStage.LOOK, if (phase.value == PhotoRollPhase.BLOCKED) "合成テストの停止理由" else when (mode.value) {
                        PhotoRollPreviewMode.MANUAL_TRIAL -> "強度50の縮小プレビューです。原寸の試験品質JPEGは手動保存時に生成します。顔保護なし・診断露出・暫定色です"
                        PhotoRollPreviewMode.PRIVATE_TRIAL -> "120×80・強度50の縮小試験JPEGです。顔保護なし・診断露出・撮影時WB/暫定色。共有写真へは保存しません"
                        else -> null
                    },
                    after, before, "RAW基準表示（合成）", true, savePhase.value, false,
                    state.value.session == null && phase.value == PhotoRollPhase.DISPLAYED && mode.value != PhotoRollPreviewMode.PRIVATE_TRIAL,
                    true, { saves++ }, { state.value = state.value.begin(.01, true) }, {}, { retries++ },
                    { token, parameter, value -> state.value = state.value.change(token, parameter, value) },
                    { token -> state.value = state.value.reset(token) },
                    { token -> state.value = state.value.apply(token) },
                    { token -> state.value = state.value.cancel(token) },
                    { token -> state.value = state.value.close(token) }, Modifier.fillMaxSize().safeDrawingPadding()) { repeat(8) { Text("合成の原本情報 $it") } }
        } } }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            // The source action is fixed, even when the central strip overflows.
            instrumentation.waitForIdleSync()
            val initialInfo = awaitNode("原本情報と試験品質の注意", true)
            assertTrue("info must be visible without horizontal scrolling", initialInfo.isVisibleToUser)
            assertTrue(awaitNode("一覧へ戻る", true).isVisibleToUser)
            fun keyNode(label: String): AccessibilityNodeInfo {
                fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                    if (node == null) return null
                    if (node.text?.toString() == label) {
                        val action = actionNode(node)
                        if (action.isClickable) return action
                    }
                    for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
                    return null
                }
                return requireNotNull(walk(diagnostic.fixtureRoot())) { "Missing key $label" }
            }
            // Compose exposes selected Button nodes as checked; Tab nodes use isSelected.
            fun keySelected(label: String): Boolean = keyNode(label).let { it.isSelected || (it.isCheckable && it.isChecked) }
            capture("initial")
            fun dump(node: AccessibilityNodeInfo?, depth: Int = 0): String {
                if (node == null) return ""
                return "${" ".repeat(depth)}text=${node.text} description=${node.contentDescription} selected=${node.isSelected} checkable=${node.isCheckable} checked=${node.isChecked} clickable=${node.isClickable} visible=${node.isVisibleToUser}\n" +
                    (0 until node.childCount).joinToString("") { dump(node.getChild(it), depth + 1) }
            }
            File(context.getExternalFilesDir(null), "design-accessibility.txt")
                .writeText(dump(diagnostic.fixtureRoot()))
            assertTrue(keySelected("STRENGTH"))
            assertFalse(keySelected("色温度"))
            val rulerBounds = Rect().also { awaitNode("RESET").getBoundsInScreen(it) }
            val keyBounds = Rect().also { keyNode("STRENGTH").getBoundsInScreen(it) }
            assertTrue("ruler precedes the key row", rulerBounds.bottom <= keyBounds.top)
            fun lastAction(label: String): AccessibilityNodeInfo {
                val matches = mutableListOf<AccessibilityNodeInfo>()
                fun walk(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    if (node.text?.toString() == label) matches += actionNode(node)
                    for (i in 0 until node.childCount) walk(node.getChild(i))
                }
                walk(diagnostic.fixtureRoot())
                return matches.last()
            }
            repeat(2) {
                assertTrue(lastAction("比較").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitNode("RAW基準表示（合成）")
            }
            repeat(2) {
                assertTrue(lastAction("表示").performAction(AccessibilityNodeInfo.ACTION_CLICK))
                awaitNode("現像結果 · 長押しで基準表示")
            }
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

            assertTrue(awaitNode("色温度", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode("色温度 · 相対（K値ではありません）")
            capture("temperature")
            assertTrue(keySelected("色温度"))
            assertFalse(keySelected("STRENGTH"))
            assertTrue(awaitNode("目盛りを閉じる", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            val readySave = awaitNode("JPEGを保存", true)
            assertFalse(keySelected("色温度"))
            assertFalse(keySelected("STRENGTH"))
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
            scenario.onActivity { mode.value = PhotoRollPreviewMode.STAGE_CLEAR }
            awaitNode("DNG 現像")
            assertNull(find("顔保護なし"))
            capture("stage-clear")
            scenario.onActivity { mode.value = PhotoRollPreviewMode.PRIVATE_TRIAL }
            scenario.onActivity { phase.value = PhotoRollPhase.BLOCKED }
            instrumentation.waitForIdleSync()
            capture("blocked")
            for (trialMode in listOf(PhotoRollPreviewMode.DNG_TRIAL, PhotoRollPreviewMode.STAGE_CLEAR_TRIAL)) {
                scenario.onActivity { phase.value = PhotoRollPhase.DISPLAYED; mode.value = trialMode; state.value = TrialStrengthEditor.empty() }
                awaitNode("現像結果 · 長押しで基準表示")
                assertNull(find("表示中 · 未反映の調整あり"))
            }
            scenario.onActivity { phase.value = PhotoRollPhase.DISPLAYED; mode.value = PhotoRollPreviewMode.MANUAL_TRIAL; state.value = initial }
            repeat(4) { scroll(false, false) }
            // No horizontal scrolling is needed to reach the fixed info action.
            val info = awaitNode("原本情報と試験品質の注意", true)
            assertTrue(info.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            awaitNode("合成の原本情報 0")
            repeat(4) { scroll(false) }
            assertFalse(awaitNode("同じ保存を再照合", false).isEnabled)
            capture("info-footer")
            repeat(4) { scroll(false, false) }
            awaitNode("合成の原本情報 0")
            capture("info-top")
            assertTrue(awaitNode("原本情報と試験品質の注意", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertTrue(awaitNode("比較", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode("RAW基準表示（合成）")
            assertTrue(awaitNode("取消", true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertNull(state.value.session) }
        }
        after.recycle(); before.recycle()
    }
}
