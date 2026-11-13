package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/**
 * Baseline 53e68ffc: unmodified old PhotoRollDevelopView with the same synthetic display inputs.
 * The old GpuPhotoRollEditor/ForegroundSaveNotice overlays and dialogs are presentation-only
 * fixture reproductions copied from that baseline. No owner, FGS, permission or RAW operation.
 * This is not an old app end-to-end/state-transition reproduction. Cases 17/18 did not exist.
 */
class DevelopBeforeScreenshotAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    private data class Case(val name: String, val phase: PhotoRollPhase = PhotoRollPhase.DISPLAYED,
        val savePhase: PhotoRollSavePhase? = PhotoRollSavePhase.READY, val exportPaused: Boolean = false,
        val editing: Boolean = false, val saving: Boolean = false, val memory: Boolean = false,
        val activeSave: Boolean = false, val notificationsAllowed: Boolean = true, val expected: String)

    @Test fun captureBaselineProductStates() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (y in 0 until height) {
                paint.color = Color.rgb(30 + y * 120 / height, 85 + y * 80 / height, 170)
                canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint)
            }
            paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
            canvas.drawCircle(300f, 400f, 160f, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 30f
            canvas.drawText("SYNTHETIC / NO PHOTO", 28f, 55f, paint)
        }

        val cases = listOf(
            Case("before01-preparing", PhotoRollPhase.CHECKING, savePhase = null, expected = "画像を準備中…"),
            Case("before02-ready", expected = "保存前"),
            Case("before03-editing", editing = true, expected = "調整中 · ×で保存へ"),
            Case("before04-saving-paused", savePhase = PhotoRollSavePhase.SAVING, exportPaused = true,
                activeSave = true, expected = "JPEGを保存中です。アプリを離れても継続します。"),
            Case("before05-published-paused", savePhase = PhotoRollSavePhase.PUBLISHED, exportPaused = true,
                expected = "公開確認済み · 開く前に照合"),
            Case("before06-cancelled", savePhase = PhotoRollSavePhase.CANCELLED, exportPaused = true,
                expected = "保存取消済み · 再試行"),
            Case("before07-save-failed", savePhase = PhotoRollSavePhase.GPU_FAILED, exportPaused = true,
                expected = "GPU 保存失敗 · 再試行"),
            Case("before08-low-memory", phase = PhotoRollPhase.BLOCKED, memory = true, expected = "メモリ不足"),
            Case("before09-unconfirmed", savePhase = PhotoRollSavePhase.UNCONFIRMED, exportPaused = true,
                expected = "保存未確認 · 再照合"),
            Case("before10-hold", savePhase = PhotoRollSavePhase.HOLD, exportPaused = true, expected = "保存保留"),
            Case("before11-rechecking", savePhase = PhotoRollSavePhase.HOLD, exportPaused = true,
                saving = true, expected = "保存確認中"),
            // Baseline has no dedicated publication toast; capture the same PUBLISHED state without it.
            Case("before12-published-no-toast", savePhase = PhotoRollSavePhase.PUBLISHED,
                expected = "公開確認済み · 開く前に照合"),
            Case("before15-saving-notifications-disabled", savePhase = PhotoRollSavePhase.SAVING,
                exportPaused = true, activeSave = true, notificationsAllowed = false, expected = "保存通知を許可"),
            Case("before16-cancelled-other-photo-saving", savePhase = PhotoRollSavePhase.CANCELLED,
                exportPaused = true, activeSave = true, expected = "プレビューを再開"),
        )
        val shown = mutableStateOf(cases.first())
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
            SharedOriginalReference("content://fixture/pr-c", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))
        val editor = gpuPresentedEditor(bound, bound.requestedValues, false)
        fixture.content = {
            val c = shown.value
            val displayedEditor = if (c.phase == PhotoRollPhase.CHECKING) bound.showingDraft() else if (c.editing) editor.begin(.01, true) else editor
            val memoryProblem = if (c.memory) gpuPhotoRollMemoryProblem(
                dev.cinematic.p0.jpegrender.original.Original24MemoryUnavailable(), true) else null
            val message = memoryProblem?.body ?: saveMessage(c) ?: if (c.exportPaused)
                "保存のため GPU プレビューを終了しました。保存後にプレビューを再開できます" else null
            CaptureTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                PhotoRollDevelopView("SYNTHETIC.dng", PhotoRollPreviewMode.MANUAL_TRIAL,
                    displayedEditor, { displayedEditor }, c.phase, null, message,
                    image.takeIf { !c.exportPaused && c.phase == PhotoRollPhase.DISPLAYED },
                    null, null, true, c.savePhase, c.saving,
                    c.phase == PhotoRollPhase.DISPLAYED && !c.saving && !c.editing &&
                        (!c.exportPaused || gpuSaveCanContinueWithoutPreview(c.savePhase)) &&
                        c.savePhase in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED,
                            PhotoRollSavePhase.UNCONFIRMED, PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED),
                    c.phase == PhotoRollPhase.DISPLAYED && !c.exportPaused && !c.saving &&
                        canBeginStrengthEdit(PhotoRollPreviewMode.MANUAL_TRIAL, true, c.saving, c.savePhase),
                    {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {},
                    modifier = Modifier.weight(1f), memoryProblem = memoryProblem,
                    gpuReady = c.phase == PhotoRollPhase.DISPLAYED && !c.exportPaused,
                    statusOverlay = { LegacyOverlay(c) },
                    info = { Text("GPU の診断用プレビューです。通常版の既定は CPU です。原本と保存前後の照合は変更しません。") })
            } } }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                val selectedCases = cases.filter { InstrumentationRegistry.getArguments().getString("beforeCase")?.let { selected -> selected == it.name } ?: true }
                check(selectedCases.isNotEmpty()) { "Unknown beforeCase" }
                for (c in selectedCases) {
                    scenario.onActivity { shown.value = c }
                    awaitText(c.expected)
                    instrumentation.waitForIdleSync()
                    val capture = fixture.captureSoftware()
                    try { writeImage(c.name, capture) } finally { capture.recycle() }
                }
            }
        } finally { image.recycle() }
    }

    private fun saveMessage(c: Case): String? = when (c.savePhase) {
        PhotoRollSavePhase.CANCELLED -> "保存を取り消しました。同じ設定で再試行するか、プレビューを再開して設定を変更できます"
        else -> null
    }

    @Composable private fun LegacyOverlay(c: Case) {
        if (c.activeSave) Column(Modifier.fillMaxWidth().heightIn(max = 144.dp)
            .verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text("JPEGを保存中です。アプリを離れても継続します。")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = {}) { Text("保存を取り消す") }
                if (!c.notificationsAllowed) TextButton(onClick = {}) { Text("保存通知を許可") }
            }
            if (!c.notificationsAllowed) Text("保存通知は無効です。取消はこの画面から行えます。")
        }
        if (c.savePhase in setOf(PhotoRollSavePhase.HOLD, PhotoRollSavePhase.UNCONFIRMED)) {
            Text("前回の保存結果を確認できません。保存先に JPEG がある可能性があります。")
            TextButton(enabled = !c.saving, onClick = {}) { Text("保存結果を確認・保存し直す") }
        }
        if (c.exportPaused) {
            Text(saveMessage(c) ?: "保存用に GPU プレビューを終了しました")
            if (c.savePhase in setOf(PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED))
                Text("下の再試行ボタンで、同じ保存を再試行します")
            TextButton(enabled = !c.saving && gpuSaveCanResumePreview(c.savePhase), onClick = {}) {
                Text("プレビューを再開")
            }
        }
    }

    @Test fun captureBaselineDialogs() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recovery = mutableStateOf(true)
        val dialogRoot = AtomicReference<View?>()
        fixture.content = {
            CaptureTheme { Surface(Modifier.fillMaxSize()) {
                if (recovery.value) AlertDialog(onDismissRequest = {},
                    title = { Text("前回の保存結果を確認できません") },
                    text = {
                        val view = LocalView.current
                        SideEffect { dialogRoot.set(view.rootView) }
                        Text("保存先に JPEG がある可能性があります。保存し直す場合は、前回と同じ設定で別の JPEG を作ります。原本と前回の記録は保持します。")
                    },
                    confirmButton = { TextButton(onClick = {}) { Text("重複の可能性はあるが保存し直す") } },
                    dismissButton = { Column {
                        TextButton(onClick = {}) { Text("もう一度確認") }
                        TextButton(onClick = {}) { Text("今は保存しない") }
                    } })
                else AlertDialog(onDismissRequest = {},
                    containerColor = CaptureDesign.Ink, titleContentColor = CaptureDesign.Text, textContentColor = CaptureDesign.Muted,
                    title = { Text("原本情報と試験品質の注意") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val view = LocalView.current
                        SideEffect { dialogRoot.set(view.rootView) }
                        Text("SYNTHETIC.dng", style = CaptureDesign.Explanation)
                        Text("原本は読み取り専用です。コピー・上書き・公開は行いません。", style = CaptureDesign.Explanation)
                        Text("GPU の診断用プレビューです。通常版の既定は CPU です。原本と保存前後の照合は変更しません。")
                    } }, confirmButton = { TextButton(onClick = {}) { Text("閉じる") } })
            } }
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            for (isRecovery in listOf(true, false)) {
                scenario.onActivity { recovery.value = isRecovery }
                awaitText(if (isRecovery) "前回の保存結果を確認できません" else "原本情報と試験品質の注意")
                instrumentation.waitForIdleSync()
                writeBounds(if (isRecovery) "before13-recovery-dialog" else "before14-static-info", requireNotNull(dialogRoot.get()))
                var capture: Bitmap? = null
                instrumentation.runOnMainSync {
                    val view = requireNotNull(dialogRoot.get())
                    check(view.width > 0 && view.height > 0)
                    capture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
                }
                requireNotNull(capture).let {
                    try { writeImage(if (isRecovery) "before13-recovery-dialog" else "before14-static-info", it) }
                    finally { it.recycle() }
                }
            }
        }
    }

    private fun awaitText(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun contains(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if ((node.text?.toString() == label || node.contentDescription?.toString() == label) && node.isVisibleToUser) return true
            return (0 until node.childCount).any { contains(node.getChild(it)) }
        }
        repeat(100) {
            instrumentation.waitForIdleSync()
            val root = fixture.automation.rootInActiveWindow
            fun systemUiAnr(node: AccessibilityNodeInfo?): Boolean = node != null &&
                (node.text?.toString() == "System UI isn't responding" ||
                    (0 until node.childCount).any { systemUiAnr(node.getChild(it)) })
            check(!systemUiAnr(root)) { "System UI ANR obscures synthetic UI; preserve failure evidence" }
            if (root?.packageName == instrumentation.targetContext.packageName && contains(root)) return
            SystemClock.sleep(50)
        }
        fixture.failure(label)
        fail("Missing visible synthetic message: $label")
    }

    private fun writeBounds(name: String, view: View) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val lines = mutableListOf<String>()
        instrumentation.runOnMainSync {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val dm = view.resources.displayMetrics
            val visibleFrame = android.graphics.Rect(); view.getWindowVisibleDisplayFrame(visibleFrame)
            lines += "displayPixels=${dm.widthPixels}x${dm.heightPixels},windowVisibleFrame=$visibleFrame"
            lines += "root=${loc.toList()},size=${view.width}x${view.height},density=${dm.density},densityDpi=${dm.densityDpi},fontScale=${view.resources.configuration.fontScale}"
        }
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
            if (!node.text.isNullOrBlank()) lines += "text=${node.text},bounds=$bounds,visible=${node.isVisibleToUser},enabled=${node.isEnabled}"
            (0 until node.childCount).forEach { visit(node.getChild(it)) }
        }
        visit(fixture.automation.rootInActiveWindow)
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = configured?.let(::File) ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
        File(root, "ui-pr-c-before").apply { check(isDirectory || mkdirs()) }.resolve("$name-bounds.txt").writeText(lines.joinToString("\n"))
    }
    private fun writeImage(name: String, bitmap: Bitmap) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = configured?.let(::File) ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
        val directory = File(root, "ui-pr-c-before").apply { check(isDirectory || mkdirs()) }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
