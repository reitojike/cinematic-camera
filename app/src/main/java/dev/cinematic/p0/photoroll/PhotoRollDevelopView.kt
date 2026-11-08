package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import dev.cinematic.p0.captureChromeColor
import dev.cinematic.p0.CaptureChromeTone
import dev.cinematic.p0.CaptureStatusChip
import dev.cinematic.p0.StatusChip
import dev.cinematic.p0.StatusChipStyle
import dev.cinematic.p0.captureNeedsFullScroll
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.captureControlSurface
import dev.cinematic.p0.CaptureModeSwitchSurface
import dev.cinematic.p0.photoedit.*

internal fun developModeColor(mode: PhotoRollPreviewMode) =
    if (mode == PhotoRollPreviewMode.PRIVATE_TRIAL) CaptureDesign.Amber else CaptureDesign.Text

internal fun developShowsFaceWarning(mode: PhotoRollPreviewMode) = mode != PhotoRollPreviewMode.STAGE_CLEAR

internal fun developSaveLabel(mode: PhotoRollPreviewMode, phase: PhotoRollSavePhase?, pending: Boolean, editing: Boolean): String = when {
    mode == PhotoRollPreviewMode.PRIVATE_TRIAL -> "共有保存なし"
    pending || phase == PhotoRollSavePhase.SAVING -> "保存確認中"
    editing -> "調整中 · ×で保存へ"
    phase == PhotoRollSavePhase.READY -> "保存前"
    phase == PhotoRollSavePhase.PUBLISHED -> "公開確認済み · 開く前に照合"
    phase == PhotoRollSavePhase.UNCONFIRMED -> "保存未確認 · 再照合"
    phase == PhotoRollSavePhase.HOLD -> "保存保留"
    else -> "保存準備未確認"
}

@Composable internal fun PhotoRollDevelopView(name: String, mode: PhotoRollPreviewMode, state: TrialStrengthEditor,
    readState: () -> TrialStrengthEditor, phase: PhotoRollPhase, stage: PhotoRollRenderStage?, message: String?,
    preview: Bitmap?, comparison: Bitmap?, comparisonLabel: String?, foreground: Boolean,
    savePhase: PhotoRollSavePhase?, savePending: Boolean, saveEnabled: Boolean, beginEnabled: Boolean,
    save: () -> Unit, begin: () -> Unit, back: () -> Unit, retry: () -> Unit,
    change: (PhotoEditInputToken, PhotoEditParameter, Double) -> Unit, reset: (PhotoEditInputToken) -> Unit,
    apply: (PhotoEditInputToken) -> Unit, cancel: (PhotoEditInputToken) -> Unit, close: (PhotoEditInputToken) -> Unit,
    modifier: Modifier = Modifier, statusOverlay: @Composable () -> Unit = {}, fillImageWidth: Boolean = false, info: @Composable () -> Unit) {
    var information by remember { mutableStateOf(false) }
    var parameter by remember { mutableStateOf(PhotoEditParameter.STRENGTH) }
    var comparing by remember(preview, comparison, foreground) { mutableStateOf(false) }
    val session = state.session
    val adjustments = mode == PhotoRollPreviewMode.MANUAL_TRIAL
    val compareEnabled = foreground && preview != null && comparison != null && comparisonLabel != null && phase == PhotoRollPhase.DISPLAYED
    val requested = session?.draft ?: state.requestedValues
    val unapplied = mode in setOf(PhotoRollPreviewMode.MANUAL_TRIAL, PhotoRollPreviewMode.STAGE_CLEAR,
        PhotoRollPreviewMode.PRIVATE_TRIAL) && state.displayedValues != requested
    val saveLabel = developSaveLabel(mode, savePhase, savePending, session != null)
    val saveTone = when {
        savePending || savePhase == PhotoRollSavePhase.SAVING -> CaptureChromeTone.WARNING
        savePhase == PhotoRollSavePhase.PUBLISHED -> CaptureChromeTone.ACCEPTED
        savePhase in setOf(PhotoRollSavePhase.UNCONFIRMED, PhotoRollSavePhase.HOLD) -> CaptureChromeTone.FAILURE
        saveEnabled -> CaptureChromeTone.READY
        else -> CaptureChromeTone.DISABLED
    }
    val saveColor = captureChromeColor(saveTone)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Expanded source details must not squeeze the editor or footer out of reach.
        val compact = captureNeedsFullScroll(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, 560f)
        Column(Modifier.fillMaxSize().background(CaptureDesign.Ink).then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, shape = RectangleShape, modifier = Modifier.semantics { contentDescription = "一覧へ戻る" }) { Text("‹") }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    val modeLabel = when (mode) { PhotoRollPreviewMode.STAGE_CLEAR -> "DNG 現像"; PhotoRollPreviewMode.PRIVATE_TRIAL -> "試験 1024px"
                        PhotoRollPreviewMode.DNG_TRIAL -> "DNG 試験表示"; PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "Stage Clear 試験"
                        else -> "DNG 試験現像" }
                    StatusChip(CaptureStatusChip("mode", modeLabel, mode = true,
                        tone = if (mode == PhotoRollPreviewMode.PRIVATE_TRIAL) CaptureChromeTone.WARNING else CaptureChromeTone.NORMAL),
                        style = StatusChipStyle.EDITOR_BADGE)
                    StatusChip(CaptureStatusChip("name", name, mode = true), style = StatusChipStyle.EDITOR_LABEL)
                    StatusChip(CaptureStatusChip("save", saveLabel, tone = saveTone),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = StatusChipStyle.EDITOR_BADGE)
                    if (developShowsFaceWarning(mode)) StatusChip(CaptureStatusChip("face-warning", "顔保護なし", tone = CaptureChromeTone.WARNING),
                        style = StatusChipStyle.EDITOR_LABEL)
                }
                TextButton(onClick = { information = !information }, shape = RectangleShape,
                    modifier = Modifier.semantics { contentDescription = "原本情報と試験品質の注意" }) { Text("ⓘ") }
            }
            Box((if (compact) Modifier.fillMaxWidth().height(260.dp) else Modifier.fillMaxWidth().weight(1f))
                .semantics { contentDescription = "現像画像の表示領域" }, contentAlignment = Alignment.Center) {
                val updating = phase in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING, PhotoRollPhase.PAUSED)
                if (preview != null && phase != PhotoRollPhase.BLOCKED) PhotoRollComparisonImage(preview,
                    comparison.takeIf { compareEnabled }, foreground, "現像結果（表示用）", comparisonLabel,
                    Modifier.fillMaxSize(), comparing && compareEnabled, { comparing = it }, unapplied || updating,
                    fillImageWidth = fillImageWidth, stableCaption = true)
                else if (phase != PhotoRollPhase.BLOCKED) Text(if (updating) "画像を準備中…" else "原本の確認待ち", color = CaptureDesign.Muted)
                Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(CaptureDesign.Ink.copy(alpha = .9f))) {
                    statusOverlay()
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(CaptureDesign.Ink.copy(alpha = .9f))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
                    if (phase == PhotoRollPhase.BLOCKED) {
                        Text(message ?: "現像を開始・完了できません", Modifier.padding(8.dp),
                            color = CaptureDesign.Failed, style = CaptureDesign.Explanation)
                        TextButton(onClick = retry, enabled = foreground, shape = RectangleShape) { Text("原本を確認して再試行") }
                    }
                    if (updating) Text(if (preview != null) "更新中 · 直前の表示です" else
                        if (phase == PhotoRollPhase.CHECKING) "原本を確認中…" else stage?.label ?: "DNGを処理中…",
                        Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
                    if (message != null && phase != PhotoRollPhase.BLOCKED && !updating) TextButton(
                        onClick = { information = true }, shape = RectangleShape) {
                        Text(message, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = CaptureDesign.Muted, style = CaptureDesign.Explanation)
                    }
                    if (phase == PhotoRollPhase.PAUSED) TextButton(onClick = retry, enabled = foreground,
                        shape = RectangleShape) { Text("原本を確認して再試行") }
                    if (session != null) key(parameter) {
                        Box(Modifier.fillMaxWidth().semantics { contentDescription = "現像調整パネル" }) {
                            TrialStrengthEditorPane(state, readState, parameter, foreground, phase, change, reset, apply, close)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                PhotoEditParameter.entries.forEach { key ->
                    val supported = mode in setOf(PhotoRollPreviewMode.MANUAL_TRIAL, PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.PRIVATE_TRIAL) && (key == PhotoEditParameter.STRENGTH || adjustments)
                    val value = when (key) { PhotoEditParameter.STRENGTH -> requested.strength; PhotoEditParameter.TEMPERATURE -> requested.temperature; PhotoEditParameter.BRIGHTNESS -> requested.brightness }
                    val displayed = state.displayedValues?.let { when (key) { PhotoEditParameter.STRENGTH -> it.strength; PhotoEditParameter.TEMPERATURE -> it.temperature; PhotoEditParameter.BRIGHTNESS -> it.brightness } }
                    val dirty = supported && value != displayed
                    val keyEnabled = supported && foreground && (session != null || beginEnabled)
                    TextButton(onClick = { parameter = key; if (session == null) begin() }, enabled = keyEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 72.dp).captureControlSurface(session != null && parameter == key)
                            .semantics { selected = session != null && parameter == key }, shape = RectangleShape, contentPadding = PaddingValues(4.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = CaptureDesign.Text, disabledContentColor = CaptureDesign.Disabled)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when (key) { PhotoEditParameter.STRENGTH -> "STRENGTH"; PhotoEditParameter.TEMPERATURE -> "色温度"; PhotoEditParameter.BRIGHTNESS -> "明るさ" }, style = CaptureDesign.Label)
                            Text(if (supported && value != null) editValueLabel(key, value) else "—", style = CaptureDesign.Value, color = if (!keyEnabled) CaptureDesign.Disabled else if (dirty) CaptureDesign.Amber else CaptureDesign.Text)
                            Text(when { !supported -> "未対応"; dirty -> "指定 · 未反映"; key == PhotoEditParameter.TEMPERATURE -> "相対"; key == PhotoEditParameter.BRIGHTNESS -> "EV"; else -> "表示中" },
                                style = CaptureDesign.Label, color = if (!keyEnabled) CaptureDesign.Disabled else if (dirty) CaptureDesign.Amber else CaptureDesign.Muted)
                            Text(if (supported && dirty) displayed?.let { "表示中 ${editValueLabel(key, it)}" } ?: "表示中の値なし" else " ",
                                style = CaptureDesign.Label, color = if (keyEnabled) CaptureDesign.Muted else CaptureDesign.Disabled)
                        }
                    }
                }
                TextButton(onClick = { comparing = !comparing }, enabled = compareEnabled, modifier = Modifier.weight(1f).heightIn(min = 72.dp).captureControlSurface(selected = false), shape = RectangleShape) { Text("比較", style = CaptureDesign.Label) }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { session?.let { cancel(it.inputToken) } }, enabled = foreground && session != null,
                    modifier = Modifier.width(88.dp), shape = RectangleShape) { Text("取消") }
                Box(Modifier.size(76.dp).semantics { contentDescription = when (savePhase) {
                    PhotoRollSavePhase.PUBLISHED -> "保存したJPEGを照合して開く"; PhotoRollSavePhase.UNCONFIRMED -> "同じ保存を再照合"; else -> "JPEGを保存" }
                    stateDescription = saveLabel
                    if (!saveEnabled) disabled() }.clickable(enabled = saveEnabled, role = Role.Button, onClick = save), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val dashed = savePending || savePhase == PhotoRollSavePhase.SAVING
                        drawCircle(saveColor, radius = size.minDimension / 2 - 2.dp.toPx(), style = Stroke(2.dp.toPx(), pathEffect =
                            if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null))
                        drawCircle(saveColor.copy(alpha = if (saveEnabled) .85f else .15f), radius = size.minDimension / 2 - 8.dp.toPx())
                    }
                    Text(if (savePhase == PhotoRollSavePhase.PUBLISHED) "↗" else "↓", color = if (saveEnabled) CaptureDesign.Ink else saveColor, fontSize = 26.sp)
                }
                CaptureModeSwitchSurface(comparing, Modifier.alpha(if (compareEnabled) 1f else .4f)) {
                    listOf(false, true).forEach { before -> TextButton(onClick = { comparing = before }, enabled = compareEnabled,
                        modifier = Modifier.weight(1f).fillMaxHeight().semantics { selected = comparing == before },
                        shape = RectangleShape, contentPadding = PaddingValues(2.dp)) {
                        Text(if (before) "比較" else "表示", style = CaptureDesign.Label,
                            color = if (!compareEnabled) CaptureDesign.Disabled else if (comparing == before) CaptureDesign.Ink else CaptureDesign.Muted)
                    } }
                }
            }
        }
    }
    if (information) AlertDialog(onDismissRequest = { information = false },
        containerColor = CaptureDesign.Ink, titleContentColor = CaptureDesign.Text, textContentColor = CaptureDesign.Muted,
        title = { Text("原本情報と試験品質の注意") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = CaptureDesign.Explanation)
            Text("原本は読み取り専用です。コピー・上書き・公開は行いません。", style = CaptureDesign.Explanation)
            message?.let { Text(it, style = CaptureDesign.Explanation) }
            info()
        } }, confirmButton = { TextButton(onClick = { information = false }) { Text("閉じる") } })

}
