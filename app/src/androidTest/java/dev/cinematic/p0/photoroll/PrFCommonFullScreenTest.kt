package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.*
import java.io.File
import org.junit.Test

/**
 * Full-window SYNTHETIC visual evidence only. Real shared dialogs and passive screen views,
 * no CameraProbe, GPU, input file, import controller, save owner, or permission operation.
 * All callbacks are inert. Recovery/INFO message data mirror their production call sites.
 * Reset uses a synthetic capture scaffold (not CaptureScreen); import uses an empty real browser.
 * Software decor + dialog window capture is not a manual regression or compositor test.
 */
class PrFCommonFullScreenTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()

    @Test fun captureDialogsInFullScreenContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("isolated") == "true")
        check(Build.HARDWARE in listOf("ranchu", "goldfish") || Build.FINGERPRINT.contains("generic")) {
            "Synthetic emulator only; no physical-device operation"
        }
        val output = File(requireNotNull(args.getString("additionalTestOutputDir")), "ui-pr-f-common-full")
        check(output.isDirectory || output.mkdirs())
        val selected = args.getString("commonDialogCases")?.split(",")?.toSet()
        val cases = listOf("recovery", "info", "reset", "import").filter { selected == null || it in selected }
        check(cases.isNotEmpty())
        val image = syntheticImage()
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
            SharedOriginalReference("content://fixture/pr-f-common", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))
        val editor = bound.shown(bound.ticket, bound.requestedValues)
        try {
            for (name in cases) {
                fixture.content = {
                    CaptureTheme {
                        Box(Modifier.fillMaxSize().background(CaptureDesign.Ink).safeDrawingPadding()) {
                            when (name) {
                                "import" -> importBackground()
                                "reset" -> resetBackground(image)
                                else -> developBackground(name, editor, image)
                            }
                            when (name) {
                                "reset" -> CaptureResetDialog(dismiss = {}, reset = {})
                                "import" -> ImportDialogView(DngImportScreenState(), null,
                                    awaitingSelection = false, pickerPending = false, foreground = true,
                                    close = {}, select = {}, cancelPreparation = {}, cancel = {}, resume = {}, cancelItem = {})
                                "recovery" -> CaptureDialog(
                                    MessageDialog("前回の保存結果を確認できません",
                                        "保存先に JPEG がある可能性があります。保存し直す場合は、前回と同じ設定で別の JPEG を作ります。原本と前回の記録は保持します。",
                                        MessageControl(MessageAction.CLOSE, "今は保存しない"),
                                        MessageControl(MessageAction.CONFIRM, "重複の可能性はあるが保存し直す")),
                                    onDismissRequest = {}, onAction = {},
                                ) {
                                    CaptureTextAction(MessageControl(MessageAction.RECHECK_SAVE, "もう一度確認"), {})
                                }
                                "info" -> CaptureDialog(
                                    MessageDialog("原本と現像について", "原本は読み取り専用です。コピー・上書き・公開は行いません。",
                                        MessageControl(MessageAction.CLOSE, "閉じる")), onDismissRequest = {}, onAction = {},
                                ) {
                                    Text("SYNTHETIC.dng", style = CaptureDesign.Body, color = CaptureDesign.Text)
                                    Text("顔保護には対応していません。色は暫定で、24MP 以下の対応 DNG のみ読み込めます。",
                                        style = CaptureDesign.Body, color = CaptureDesign.Muted)
                                }
                            }
                        }
                    }
                }
                ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                    val expected = when (name) {
                        "reset" -> listOf("撮影制御をリセット", "全リセット", "取消")
                        "import" -> listOf("DNGを端末に取り込む", "DNGを選ぶ", "閉じる")
                        "recovery" -> listOf("前回の保存結果を確認できません", "今は保存しない", "重複の可能性はあるが保存し直す", "もう一度確認")
                        else -> listOf("原本と現像について", "SYNTHETIC.dng", "閉じる")
                    }
                    expected.forEach(::awaitText)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity { activity ->
                        val host = activity.window.decorView
                        val dialog = WindowInspector.getGlobalWindowViews().single {
                            it !== host && it.isShown && it.hasWindowFocus()
                        }
                        check(host.width > 0 && host.height > 0 && dialog.width > 0 && dialog.height > 0)
                        val params = requireNotNull(dialog.layoutParams as? WindowManager.LayoutParams)
                        val origin = IntArray(2); val offset = IntArray(2)
                        host.getLocationOnScreen(origin); dialog.getLocationOnScreen(offset)
                        val x = offset[0] - origin[0]; val y = offset[1] - origin[1]
                        check(x >= 0 && y >= 0 && x + dialog.width <= host.width && y + dialog.height <= host.height) {
                            "Dialog window is outside captured host: $name"
                        }
                        val bitmap = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(bitmap)
                            host.draw(canvas)
                            if ((params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                                canvas.drawColor(android.graphics.Color.argb((params.dimAmount * 255).toInt(), 0, 0, 0))
                            }
                            canvas.save(); canvas.translate(x.toFloat(), y.toFloat())
                            dialog.draw(canvas); canvas.restore()
                            File(output, "$name.png").outputStream().use {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                            val dm = activity.resources.displayMetrics
                            File(output, "$name-metrics.txt").writeText(
                                "SYNTHETIC full-window source rendering; no actual operation\n" +
                                "host=${host.width}x${host.height}; dialog=${dialog.width}x${dialog.height}; offset=$x,$y; " +
                                "density=${dm.density}; fontScale=${activity.resources.configuration.fontScale}; dim=${params.dimAmount}\n" +
                                "background=" + if (name == "reset") "synthetic capture scaffold using real shared chrome" else "real passive screen view with synthetic data")
                        } finally { bitmap.recycle() }
                    }
                }
            }
        } finally { fixture.content = null; image.recycle() }
    }

    @Composable private fun developBackground(name: String, editor: TrialStrengthEditor, image: Bitmap) {
        val recovery = name == "recovery"
        val state = DevelopMessageState(PhotoRollPhase.DISPLAYED,
            savePhase = if (recovery) PhotoRollSavePhase.HOLD else PhotoRollSavePhase.READY,
            exportPaused = recovery, canLeave = true, canRecover = recovery)
        PhotoRollDevelopView("SYNTHETIC.dng", PhotoRollPreviewMode.MANUAL_TRIAL, editor, { editor },
            phase = state.phase, stage = null, message = null,
            preview = image.takeUnless { recovery }, comparison = null, comparisonLabel = null,
            foreground = true, savePhase = state.savePhase, savePending = false,
            saveEnabled = !recovery, beginEnabled = !recovery,
            save = {}, begin = {}, back = {}, retry = {}, change = { _, _, _ -> }, reset = {}, apply = {}, cancel = {}, close = {},
            modifier = Modifier.fillMaxSize(), gpuReady = !recovery,
            productMessages = developMessages(state), productSave = developSavePresentation(state),
            info = { Text("合成画像による表示試験です。", style = CaptureDesign.Body) })
    }

    @Composable private fun importBackground() {
        CameraRollBrowser(PhotoRollListing(emptyList()), CameraRollAccess(true, true, true, false),
            PhotoRollKindFilter.ALL, folder = null, loading = false, opening = false, notice = null,
            mode = PhotoRollPreviewMode.MANUAL_TRIAL, enabled = true, refreshEnabled = true,
            permissionEnabled = true, filesEnabled = true, folderMenu = false,
            setFolderMenu = {}, close = {}, refresh = {}, chooseFolder = {}, chooseFilter = {},
            permission = {}, settings = {}, files = {}, more = {}, modifier = Modifier.fillMaxSize(), select = {})
    }

    @Composable private fun resetBackground(image: Bitmap) {
        // Explicit synthetic scaffold, not a claim to reproduce a live CaptureScreen/CameraX session.
        val input = CaptureChromeInput(CaptureMode.STILL, bound = true, cameraId = "0",
            manualExposure = false, photoFormat = "JPEG", captureReady = true, thermalSeverity = 0)
        Column(Modifier.fillMaxSize()) {
            CaptureStatusBar(input, settingsEnabled = true, settingsSelected = true, onSettings = {})
            Image(image.asImageBitmap(), "合成カメラ画像", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
            Text("撮影設定 · 合成画面", Modifier.padding(16.dp), style = CaptureDesign.Title, color = CaptureDesign.Text)
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                for (label in listOf("ISO", "SS", "EV", "FOCUS", "WB", "LOOK")) {
                    Text(label, style = CaptureDesign.Label, color = CaptureDesign.Muted)
                }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CaptureShutter(input, {}) }
        }
    }

    private fun awaitText(label: String) {
        fun contains(node: AccessibilityNodeInfo?): Boolean = node != null &&
            ((node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) ||
                (0 until node.childCount).any { contains(node.getChild(it)) })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(100) {
            instrumentation.waitForIdleSync()
            val root = fixture.automation.rootInActiveWindow
            check(root?.packageName != "android") { "System window obscures synthetic dialog; do not capture" }
            if (root?.packageName == instrumentation.targetContext.packageName && contains(root)) return
            SystemClock.sleep(50)
        }
        error("Missing visible dialog text: $label")
    }

    private fun syntheticImage(): Bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(android.graphics.Color.rgb(40, 65, 85))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(180, 170, 140) }
        canvas.drawRect(80f, 200f, 520f, 650f, paint)
        paint.color = android.graphics.Color.rgb(225, 210, 160)
        canvas.drawCircle(410f, 200f, 65f, paint)
        paint.color = android.graphics.Color.WHITE; paint.textSize = 26f
        canvas.drawText("SYNTHETIC / NO PHOTO", 25f, 55f, paint)
    }
}