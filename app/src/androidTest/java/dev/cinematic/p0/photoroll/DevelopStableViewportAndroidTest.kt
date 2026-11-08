package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import org.junit.Assert.*
import org.junit.Test

/** Synthetic pixels only. No camera, source URI, RAW processing or save. */
class DevelopStableViewportAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    @Test fun messagesAndPendingUpdatesDoNotMoveOrEraseThePhoto() = verifyViewport(false)
    @Test fun compactHeightAndLargeTextKeepTheSameViewport() = verifyViewport(true)
    private fun verifyViewport(compact: Boolean) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(50, 120, 180)) }
        val phase = mutableStateOf(PhotoRollPhase.DISPLAYED)
        val message = mutableStateOf<String?>(null)
        val status = mutableStateOf(false)
        val editor = TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults)
        fixture.content = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (compact) 1.8f else 1f)) {
            CaptureTheme { Surface { Box(if (compact) Modifier.height(360.dp) else Modifier.fillMaxSize()) {
            PhotoRollDevelopView("SYNTHETIC", PhotoRollPreviewMode.MANUAL_TRIAL, editor, { editor }, phase.value,
                PhotoRollRenderStage.LOOK, message.value, image, null, null, true, null, false, false, false,
                {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, Modifier.fillMaxSize().safeDrawingPadding(),
                statusOverlay = { if (status.value) Text("合成の保存状態") }) { Text("合成の情報") }
        } } } } }
        fun find(label: String): AccessibilityNodeInfo? {
            fun walk(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.contentDescription?.toString() == label || n.text?.toString() == label) return n
                for (i in 0 until n.childCount) walk(n.getChild(i))?.let { return it }
                return null
            }
            return walk(fixture.fixtureRoot())
        }
        fun await(label: String): AccessibilityNodeInfo {
            repeat(100) {
                instrumentation.waitForIdleSync()
                find(label)?.let { return it }
                SystemClock.sleep(50)
            }
            error("Missing synthetic node: $label")
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            val initial = Rect().also { await("現像画像の表示領域").getBoundsInScreen(it) }
            val imageBounds = Rect().also { await("現像結果（表示用）").getBoundsInScreen(it) }
            assertEquals("portrait uses full viewport width", initial.width(), imageBounds.width())
            for (next in listOf(PhotoRollPhase.DISPLAYED, PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING, PhotoRollPhase.DISPLAYED)) {
                scenario.onActivity { phase.value = next; message.value = "合成の長い通知です。".repeat(15); status.value = true }
                val updated = Rect().also { await("現像画像の表示領域").getBoundsInScreen(it) }
                assertEquals("transient state must not move viewport", initial, updated)
                assertNotNull(await("現像結果（表示用）"))
                if (next != PhotoRollPhase.DISPLAYED) assertNotNull(await("更新中 · 直前の表示です"))
            }
            scenario.onActivity { phase.value = PhotoRollPhase.BLOCKED; message.value = "合成の検証失敗" }
            await("合成の検証失敗")
            assertNull("failure must not present stale pixels as a result", find("現像結果（表示用）"))
        }
        image.recycle()
    }
}
