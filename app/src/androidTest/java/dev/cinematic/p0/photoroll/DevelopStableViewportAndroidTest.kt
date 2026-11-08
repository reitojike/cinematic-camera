package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
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
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import org.junit.Assert.*
import org.junit.Test

/** Synthetic pixels only. No camera, source URI, RAW processing or save. */
class DevelopStableViewportAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    @Test fun messagesAndPendingUpdatesDoNotMoveOrEraseThePhoto() = verifyViewport(false)
    @Test fun compactHeightAndLargeTextKeepTheSameViewport() = verifyViewport(true)
    @Test fun portraitFitComparison() = verifyViewport(false, false)
    @Test fun landscapeFitComparison() = verifyViewport(false, false, false)
    @Test fun landscapeWidthComparison() = verifyViewport(false, true, false)
    @Test fun tallPortraitFitComparison() = verifyViewport(false, false, true, true)
    @Test fun tallPortraitWidthComparison() = verifyViewport(false, true, true, true)
    private fun verifyViewport(compact: Boolean, fillWidth: Boolean = true, portrait: Boolean = true, tall: Boolean = false) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = Bitmap.createBitmap(if (portrait) 600 else 900, if (tall) 1200 else if (portrait) 800 else 600, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (y in 0 until height) {
                paint.color = Color.rgb(30 + y * 120 / height, 85 + y * 80 / height, 170)
                canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
            }
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
            canvas.drawRect(8f, 8f, width - 8f, height - 8f, paint)
            canvas.drawCircle(width / 2f, height / 2f, minOf(width, height) * .28f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 35f
            canvas.drawText("SYNTHETIC / TOP", 25f, 55f, paint)
            canvas.drawText("BOTTOM / NO CROP", 25f, height - 25f, paint)
        }

        val phase = mutableStateOf(PhotoRollPhase.DISPLAYED)
        val message = mutableStateOf<String?>(null)
        val status = mutableStateOf(false)
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
            SharedOriginalReference("content://fixture/sizing", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))
        val shown = bound.shown(bound.ticket, bound.requestedValues)
        val editor = mutableStateOf(shown.begin(.01, true))
        fixture.content = {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (compact) 1.8f else 1f)) {
            CaptureTheme { Surface { Box(if (compact) Modifier.height(360.dp) else Modifier.fillMaxSize()) {
            PhotoRollDevelopView("SYNTHETIC", PhotoRollPreviewMode.MANUAL_TRIAL, editor.value, { editor.value }, phase.value,
                PhotoRollRenderStage.LOOK, message.value, image, null, null, true, null, false, false, false,
                {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, Modifier.fillMaxSize().safeDrawingPadding(),
                statusOverlay = { if (status.value) Text("合成の保存状態") }, fillImageWidth = fillWidth) { Text("合成の情報") }
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
            if (fillWidth) assertEquals("width mode uses full viewport width", initial.width(), imageBounds.width())
            val capture = fixture.captureSoftware()
            try {
                val label = "develop-${if (portrait) "portrait" else "landscape"}-${if (fillWidth) "width" else "fit"}${if (compact) "-large-text" else ""}${if (tall) "-tall" else ""}.png"
                val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                val outputRoot = configured?.let(::File) ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
                val directory = File(outputRoot, "develop-sizing").apply { check(isDirectory || mkdirs()) }
                File(directory, label).outputStream().use {
                    capture.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { capture.recycle() }
            scenario.onActivity { editor.value = shown }
            instrumentation.waitForIdleSync()
            val closedBounds = Rect().also { await("現像画像の表示領域").getBoundsInScreen(it) }
            assertEquals("closing the ruler must not move or resize the image viewport", initial, closedBounds)
            scenario.onActivity { editor.value = shown.begin(.01, true) }
            instrumentation.waitForIdleSync()
            val reopenedBounds = Rect().also { await("現像画像の表示領域").getBoundsInScreen(it) }
            assertEquals("opening the ruler must not move or resize the image viewport", initial, reopenedBounds)
            val panelBounds = Rect().also { await("現像調整パネル").getBoundsInScreen(it) }
            assertEquals("ruler overlays the bottom of the image viewport", initial.bottom, panelBounds.bottom)
            for (next in listOf(PhotoRollPhase.DISPLAYED, PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING, PhotoRollPhase.DISPLAYED)) {
                scenario.onActivity { phase.value = next; message.value = "合成の長い通知です。".repeat(15); status.value = true }
                val updated = Rect().also { await("現像画像の表示領域").getBoundsInScreen(it) }
                assertEquals("transient state must not move viewport", initial, updated)
                assertNotNull(await("現像結果（表示用）"))
                if (next != PhotoRollPhase.DISPLAYED) {
                    val notice = Rect().also { await("更新中 · 直前の表示です").getBoundsInScreen(it) }
                    val panel = Rect().also { await("現像調整パネル").getBoundsInScreen(it) }
                    assertTrue("progress is visible above the ruler", notice.height() > 0 && notice.bottom <= panel.top)
                }
            }
            if (fillWidth && portrait && (compact || tall)) {
            var scrollNode = await("現像結果（表示用）")
            while (!scrollNode.isScrollable) scrollNode = requireNotNull(scrollNode.parent)
            assertTrue("tall portrait can scroll inside its viewport", scrollNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            instrumentation.waitForIdleSync()
            val scrolled = Rect().also { await("現像画像の表示領域").getBoundsInScreen(it) }
            assertEquals("image scrolling must not move the viewport", initial, scrolled)
            }
            scenario.onActivity { phase.value = PhotoRollPhase.BLOCKED; message.value = "合成の検証失敗" }
            await("合成の検証失敗")
            assertNull("failure must not present stale pixels as a result", find("現像結果（表示用）"))
        }
        image.recycle()
    }
}
