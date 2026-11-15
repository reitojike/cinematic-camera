package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
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
import dev.cinematic.p0.rawcapture.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real product layout with synthetic state and an empty GPU Surface. Not photo quality or save QA. */
class PrDDevelopLayoutAndroidTest {
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun panelStaysBelowImageAndEmptyViewportHostsCompactProblem() {
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(requireNotNull(args.getString("additionalTestOutputDir")), "ui-pr-d")
        check(output.isDirectory || output.mkdirs())
        val source = SharedOriginalReference("content://fixture/pr-d", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(source))
        val shown = bound.shown(bound.ticket, bound.requestedValues)
        val cases = listOf("normal", "editing", "pending", "saving", "cancelled", "failed", "compact-editing", "compact-failed")
        for (name in cases) {
            val compact = name.startsWith("compact")
            val editing = name.endsWith("editing") || name == "pending"
            val failed = name.endsWith("failed")
            val saving = name == "saving"
            val cancelled = name == "cancelled"
            val gpu = GpuPhotoRollSession(instrumentation.targetContext.assets)
            val editor = if (editing) shown.begin(.01, true) else shown
            val messageState = DevelopMessageState(
                phase = if (failed) PhotoRollPhase.BLOCKED else PhotoRollPhase.DISPLAYED,
                savePhase = when { saving -> PhotoRollSavePhase.SAVING; cancelled -> PhotoRollSavePhase.CANCELLED; else -> PhotoRollSavePhase.READY },
                saving = saving, exportPaused = saving || cancelled, editing = editing,
                message = "原本の読み取り権限がありません。Filesから選び直してください。",
                sourceInvalid = failed, canLeave = true, canResumePreview = cancelled)
            val messages = developMessages(messageState, if (saving) listOf(UiMessage(MessageSlot.BANNER,
                CaptureChromeTone.WARNING, "JPEGを保存中です。アプリを離れても続きます。", "synthetic.save", inProgress = true)) else emptyList())
            fixture.content = {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (compact) 1.3f else 1f)) {
                    CaptureTheme {
                        Box(if (compact) Modifier.fillMaxWidth().height(500.dp) else Modifier.fillMaxSize()) {
                            PhotoRollDevelopView("SYNTHETIC.dng", PhotoRollPreviewMode.MANUAL_TRIAL, editor, { editor },
                                messageState.phase, null, null, null, null, null, true, messageState.savePhase,
                                saving, false, false, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {},
                                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                                gpuSession = gpu.takeUnless { saving || cancelled || failed }, gpuReady = !saving && !cancelled && !failed,
                                gpuPresentedValues = shown.requestedValues.takeUnless { name == "pending" || saving || cancelled || failed },
                                productMessages = messages, productSave = developSavePresentation(messageState), info = {})
                        }
                    }
                }
            }
            try {
                ActivityScenario.launch(PhotoRollActivity::class.java).use {
                    val image = bounds("現像画像の表示領域")
                    assertTrue("image has positive height: $name", image.height() > 0)
                    bounds(if (name == "pending") "現像結果 · 更新中" else "現像結果")
                    if (editing) {
                        val panel = bounds("現像調整パネル")
                        assertTrue("panel must not overlap image: $name image=$image panel=$panel", panel.top >= image.bottom)
                    }
                    if (compact && failed) {
                        val problem = bounds("原本を読み込めません")
                        assertTrue("empty viewport contains the problem", image.contains(problem))
                        val action = bounds("一覧に戻る")
                        assertTrue("compact return action is inside viewport", image.contains(action))
                        assertTrue("problem remains below caption", problem.top >= bounds("現像結果").bottom)
                    }
                    val bitmap = fixture.captureSoftware()
                    try { File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                    finally { bitmap.recycle() }
                    File(output, "$name.txt").writeText("Synthetic state; empty GPU surface; software View-tree image, not photo/GPU-quality proof. viewport=$image")
                }
            } finally {
                gpu.close()
                assertTrue(gpu.renderer.awaitClosed(5_000))
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                fixture.content = null
            }
        }
    }

    private fun bounds(label: String): Rect {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        repeat(100) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            find(fixture.fixtureRoot())?.let { return Rect().also(it::getBoundsInScreen) }
            SystemClock.sleep(30)
        }
        fixture.failure(label)
        error("Missing synthetic layout node: $label")
    }
}
