package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
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
 * Synthetic read-only UI evidence: real PhotoRollDevelopView + pure product presentation.
 * No GPU session, RAW input, permission request, save, FGS, or production state transition.
 * Dialog images exercise the shared CaptureDialog fixture, not the save-recovery owner.
 * Software View-tree capture works on ATD; this is not GPU/display-compositor evidence.
 */
class DevelopMessagesScreenshotAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()

    private data class Case(val name: String, val state: DevelopMessageState,
        val expected: List<String>, val foreground: List<UiMessage> = emptyList(),
        val messagesOverride: ScreenMessages? = null)

    @Test fun captureProductMessageStatesWithSyntheticImage() {
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
        val ready = DevelopMessageState(PhotoRollPhase.DISPLAYED, PhotoRollSavePhase.READY)
        val memory = UiMessage(MessageSlot.PROBLEM, CaptureChromeTone.FAILURE,
            "メモリ不足", "develop.memory", title = "メモリ不足",
            primary = MessageControl(MessageAction.RETRY, "再試行"))
        val writing = UiMessage(MessageSlot.BANNER, CaptureChromeTone.WARNING,
            "JPEGを保存中です。アプリを離れても続きます。", "save.foreground_progress",
            primary = MessageControl(MessageAction.CANCEL_SAVE, "保存を取り消す"), inProgress = true)
        val activeSaveWithoutNotifications = ForegroundSaveMessageState(
            supported = true, active = true, held = false, stopping = false,
            foreground = true, notificationsAllowed = false, canRequestNotifications = true)
        val cases = listOf(
            Case("01-preparing", DevelopMessageState(PhotoRollPhase.CHECKING), listOf("画像を準備しています。")),
            Case("02-ready", ready, listOf("未保存")),
            Case("03-editing", ready.copy(editing = true), listOf("調整中")),
            Case("04-saving-paused", ready.copy(savePhase = PhotoRollSavePhase.SAVING, exportPaused = true),
                listOf("保存中 · プレビューを止めています", writing.body), listOf(writing)),
            Case("05-published-paused", ready.copy(savePhase = PhotoRollSavePhase.PUBLISHED, exportPaused = true,
                canResumePreview = true), listOf("保存済み", "保存のためプレビューを止めました")),
            Case("06-cancelled", ready.copy(savePhase = PhotoRollSavePhase.CANCELLED, exportPaused = true,
                canResumePreview = true), listOf("保存取消", "プレビューを再開")),
            Case("07-save-failed", ready.copy(savePhase = PhotoRollSavePhase.GPU_FAILED, exportPaused = true,
                canResumePreview = true), listOf("JPEGを保存できませんでした")),
            Case("08-low-memory", ready.copy(phase = PhotoRollPhase.BLOCKED, memoryProblem = memory),
                listOf("処理を続けられません", "原本は変更していません。")),
            Case("09-unconfirmed", ready.copy(savePhase = PhotoRollSavePhase.UNCONFIRMED, exportPaused = true,
                canRecover = true), listOf("保存未確認", "保存結果を確認・保存し直す")),
            Case("10-hold", ready.copy(savePhase = PhotoRollSavePhase.HOLD, exportPaused = true,
                canRecover = true), listOf("保存結果を確認できません", "保存結果を確認・保存し直す")),
            Case("11-rechecking", ready.copy(savePhase = PhotoRollSavePhase.HOLD, exportPaused = true,
                saving = true, rechecking = true), listOf("保存結果を確認しています。")),
            Case("12-published-feedback", ready.copy(savePhase = PhotoRollSavePhase.PUBLISHED, justPublished = true),
                listOf("JPEGを保存しました。")),
            Case("15-saving-notifications-disabled", ready.copy(savePhase = PhotoRollSavePhase.SAVING,
                exportPaused = true), listOf("保存中 · プレビューを止めています", writing.body,
                    "保存を取り消す", "通知を許可"),
                foregroundSaveMessages(activeSaveWithoutNotifications, activeOnly = true)),
            Case("16-cancelled-other-photo-saving", ready.copy(savePhase = PhotoRollSavePhase.CANCELLED,
                exportPaused = true, canResumePreview = true),
                listOf("保存取消", "保存のためプレビューを止めました", writing.body,
                    "保存を取り消す", "プレビューを再開"),
                foregroundSaveMessages(activeSaveWithoutNotifications.copy(notificationsAllowed = true), activeOnly = true)),
            // DEBUG never constructs an invalid UiMessage. These are valid policy-result fixtures,
            // not proof that a release build replaces invalid messages or preserves a real owner.
            Case("17-generic-failure-hold", ready.copy(savePhase = PhotoRollSavePhase.HOLD, exportPaused = true),
                listOf("保存保留", "現像を続けられません", "画面の表示を確認できません。一覧に戻ってください。"),
                messagesOverride = ScreenMessages(center = developFailureFallback(canLeave = true))),
            Case("18-generic-failure-navigation-disabled", ready.copy(savePhase = PhotoRollSavePhase.HOLD,
                exportPaused = true, savingRecipe = true, canLeave = false),
                listOf("設定保存中", "現像を続けられません", "画面の表示を確認できません。一覧に戻ってください。"),
                messagesOverride = ScreenMessages(center = developFailureFallback(canLeave = false))),
        )
        // Displayed fixtures have restored metadata and an accepted recipe/frame. No owner is run.
        val expectedControls = mapOf(
            "01" to listOf(false, false, false), "02" to listOf(true, true, false),
            "03" to listOf(false, true, false), "04" to listOf(false, false, false),
            "05" to listOf(true, false, true), "06" to listOf(true, false, true),
            "07" to listOf(true, false, false), "08" to listOf(false, false, false),
            "09" to listOf(true, false, false), "10" to listOf(false, false, false),
            "11" to listOf(false, false, false), "12" to listOf(true, true, false),
            "15" to listOf(false, false, false), "16" to listOf(true, false, true),
            "17" to listOf(false, false, false), "18" to listOf(false, false, false),
        )
        cases.forEach { case ->
            val snapshot = fixtureState(case.state)
            assertEquals("Preflight save/edit/visible-resume eligibility: ${case.name}",
                expectedControls.getValue(case.name.take(2)),
                listOf(fixtureCanSave(snapshot), fixtureCanBegin(snapshot), snapshot.exportPaused && snapshot.canResumePreview))
        }
        val shown = mutableStateOf(cases.first())
        val bound = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
            SharedOriginalReference("content://fixture/pr-c", OriginalFingerprint(100, "a".repeat(64)), OriginalGrant.USER_SELECTED)))
        val editor = bound.shown(bound.ticket, bound.requestedValues)
        fixture.content = {
            val current = shown.value
            val snapshot = fixtureState(current.state)
            val displayedEditor = when {
                snapshot.phase == PhotoRollPhase.CHECKING -> bound.showingDraft()
                snapshot.editing -> editor.begin(.01, true)
                else -> editor
            }
            CaptureTheme {
              Surface(Modifier.fillMaxSize()) {
               Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                PhotoRollDevelopView("SYNTHETIC.dng", PhotoRollPreviewMode.MANUAL_TRIAL,
                    displayedEditor, { displayedEditor }, current.state.phase, null, null,
                    image.takeIf { !current.state.exportPaused && current.state.phase == PhotoRollPhase.DISPLAYED },
                    null, null, snapshot.foreground && !snapshot.savingRecipe, snapshot.savePhase, snapshot.saving || snapshot.savingRecipe,
                    fixtureCanSave(snapshot), fixtureCanBegin(snapshot),
                    {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {},
                    modifier = Modifier.weight(1f),
                    gpuReady = snapshot.phase == PhotoRollPhase.DISPLAYED && !snapshot.exportPaused,
                    productMessages = current.messagesOverride ?: developMessages(snapshot, current.foreground),
                    productSave = developSavePresentation(snapshot),
                    info = { Text("合成画像による表示試験です。", style = CaptureDesign.Body) })
               }
              }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                val selected = cases.filter { InstrumentationRegistry.getArguments().getString("afterCase")?.let { prefix -> it.name.startsWith(prefix) } ?: true }
                check(selected.isNotEmpty())
                for (case in selected) {
                    scenario.onActivity { shown.value = case }
                    case.expected.forEach(::awaitText)
                    case.messagesOverride?.let {
                        assertEquals("Fallback does not replace the fixture's held save phase", PhotoRollSavePhase.HOLD, case.state.savePhase)
                        assertEquals(MessageAction.BACK_TO_LIST, it.center?.primary?.action)
                        assertEquals("Navigation eligibility remains an input, not a fallback decision",
                            case.state.canLeave, it.center?.primary?.enabled)
                        assertNull("Fallback never offers a retry or save action", it.center?.secondary)
                    }
                    instrumentation.waitForIdleSync()
                    val capture = fixture.captureSoftware()
                    try { writeImage(case.name, capture) } finally { capture.recycle() }
                }
            }
        } finally { image.recycle() }
    }

    @Test fun captureRecoveryAndStaticInfoDialogs() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val recovery = mutableStateOf(true)
        val dialogRoot = AtomicReference<View?>()
        fixture.content = {
            CaptureTheme {
              Surface(Modifier.fillMaxSize()) {
               Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                CaptureDialog(
                    if (recovery.value) MessageDialog("前回の保存結果を確認できません",
                        "保存先に JPEG がある可能性があります。保存し直す場合は、前回と同じ設定で別の JPEG を作ります。原本と前回の記録は保持します。",
                        MessageControl(MessageAction.CLOSE, "今は保存しない"),
                        MessageControl(MessageAction.CONFIRM, "重複の可能性はあるが保存し直す"))
                    else MessageDialog("原本と現像について", "原本は読み取り専用です。コピー・上書き・公開は行いません。",
                        MessageControl(MessageAction.CLOSE, "閉じる")),
                    onDismissRequest = {}, onAction = {},
                ) {
                    val view = LocalView.current
                    SideEffect { dialogRoot.set(view.rootView) }
                    if (recovery.value) CaptureTextAction(
                        MessageControl(MessageAction.RECHECK_SAVE, "もう一度確認"), {})
                    else {
                        Text("SYNTHETIC.dng", style = CaptureDesign.Body, color = CaptureDesign.Text)
                        Text("顔保護には対応していません。色は暫定で、24MP 以下の対応 DNG のみ読み込めます。",
                            style = CaptureDesign.Body, color = CaptureDesign.Muted)
                    }
                }
               }
              }
            }
        }
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            for (isRecovery in listOf(true, false)) {
                scenario.onActivity { recovery.value = isRecovery }
                val labels = if (isRecovery) listOf("前回の保存結果を確認できません", "今は保存しない",
                    "重複の可能性はあるが保存し直す", "もう一度確認") else listOf("原本と現像について", "閉じる")
                labels.forEach(::awaitText)
                instrumentation.waitForIdleSync()
                var capture: Bitmap? = null
                instrumentation.runOnMainSync {
                    val view = requireNotNull(dialogRoot.get())
                    check(view.width > 0 && view.height > 0)
                    capture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                        view.draw(Canvas(it))
                    }
                }
                requireNotNull(capture).let {
                    try { writeImage(if (isRecovery) "13-recovery-dialog" else "14-static-info", it) }
                    finally { it.recycle() }
                }
            }
        }
    }

    private fun fixtureState(state: DevelopMessageState) = state.copy(
        canLeave = !state.savingRecipe,
        canResumePreview = state.foreground && !state.saving && !state.savingRecipe && gpuSaveCanResumePreview(state.savePhase),
        retryEnabled = state.foreground && !state.saving && !state.savingRecipe,
    )

    private fun fixtureCanSave(state: DevelopMessageState) = state.foreground && !state.saving && !state.savingRecipe &&
        !state.editing && state.phase == PhotoRollPhase.DISPLAYED &&
        (!state.exportPaused || gpuSaveCanContinueWithoutPreview(state.savePhase)) &&
        state.savePhase in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED,
            PhotoRollSavePhase.UNCONFIRMED, PhotoRollSavePhase.GPU_FAILED, PhotoRollSavePhase.CANCELLED)

    private fun fixtureCanBegin(state: DevelopMessageState) = state.phase == PhotoRollPhase.DISPLAYED &&
        !state.exportPaused && !state.saving && !state.savingRecipe &&
        canBeginStrengthEdit(PhotoRollPreviewMode.MANUAL_TRIAL, state.foreground, state.saving, state.savePhase)

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

    private fun writeImage(name: String, bitmap: Bitmap) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = configured?.let(::File) ?: File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
        val directory = File(root, "ui-pr-c").apply { check(isDirectory || mkdirs()) }
        val dm = instrumentation.targetContext.resources.displayMetrics
        val fontScale = instrumentation.targetContext.resources.configuration.fontScale
        File(directory, "$name-metrics.txt").writeText("density=${dm.density}; dpi=${dm.densityDpi}; fontScale=$fontScale; display=${dm.widthPixels}x${dm.heightPixels}; bitmap=${bitmap.width}x${bitmap.height}")
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
