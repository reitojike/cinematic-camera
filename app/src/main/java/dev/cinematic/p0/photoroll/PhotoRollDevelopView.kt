package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
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
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.photoedit.*

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
    modifier: Modifier = Modifier, info: @Composable () -> Unit) {
    var information by remember { mutableStateOf(false) }
    var parameter by remember { mutableStateOf(PhotoEditParameter.STRENGTH) }
    var comparing by remember(preview, comparison, foreground) { mutableStateOf(false) }
    val session = state.session
    val adjustments = mode == PhotoRollPreviewMode.MANUAL_TRIAL
    val compareEnabled = foreground && preview != null && comparison != null && comparisonLabel != null && phase == PhotoRollPhase.DISPLAYED
    val requested = session?.draft ?: state.requestedValues
    val unapplied = state.displayedValues != requested
    val saveLabel = developSaveLabel(mode, savePhase, savePending, session != null)
    val saveColor = when {
        savePending || savePhase == PhotoRollSavePhase.SAVING -> CaptureDesign.Amber
        savePhase == PhotoRollSavePhase.PUBLISHED -> CaptureDesign.Accepted
        savePhase in setOf(PhotoRollSavePhase.UNCONFIRMED, PhotoRollSavePhase.HOLD) -> CaptureDesign.Failed
        saveEnabled -> CaptureDesign.Inverted
        else -> CaptureDesign.Disabled
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Expanded source details must not squeeze the editor or footer out of reach.
        val compact = information || maxHeight < (560 * LocalDensity.current.fontScale).dp || maxWidth < (360 * LocalDensity.current.fontScale).dp
        Column(Modifier.fillMaxSize().background(CaptureDesign.Ink).then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, shape = RectangleShape, modifier = Modifier.semantics { contentDescription = "一覧へ戻る" }) { Text("‹") }
                Text(when (mode) { PhotoRollPreviewMode.STAGE_CLEAR -> "DNG 現像"; PhotoRollPreviewMode.PRIVATE_TRIAL -> "試験 1024px"
                    PhotoRollPreviewMode.DNG_TRIAL -> "DNG 試験表示"; PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "Stage Clear 試験"
                    else -> "DNG 試験現像" }, Modifier.border(1.dp, CaptureDesign.Amber).padding(4.dp), style = CaptureDesign.Label, color = CaptureDesign.Amber)
                Text(name, Modifier.padding(8.dp), style = CaptureDesign.Label, color = CaptureDesign.Text)
                Text(saveLabel, Modifier.border(1.dp, saveColor).padding(4.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = CaptureDesign.Label, color = saveColor)
                Text("顔保護なし", Modifier.padding(8.dp), style = CaptureDesign.Label, color = CaptureDesign.Amber)
                TextButton(onClick = { information = !information }, shape = RectangleShape,
                    modifier = Modifier.semantics { contentDescription = "原本情報と試験品質の注意" }) { Text("ⓘ") }
            }
            if (information) Column(Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = CaptureDesign.Explanation, color = CaptureDesign.Text)
                Text("原本は読み取り専用です。コピー・上書き・公開は行いません。", style = CaptureDesign.Explanation, color = CaptureDesign.Muted)
                info()
            }
            Box(if (compact) Modifier.fillMaxWidth().height(260.dp) else Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (preview != null && phase == PhotoRollPhase.DISPLAYED) PhotoRollComparisonImage(preview, comparison, foreground,
                    "現像結果（表示用）", comparisonLabel, Modifier.fillMaxSize(), comparing, { comparing = it }, unapplied)
                else if (phase == PhotoRollPhase.BLOCKED) Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(message ?: "現像を開始・完了できません", color = CaptureDesign.Failed, style = CaptureDesign.Explanation)
                    TextButton(onClick = retry, enabled = foreground, shape = RectangleShape) { Text("原本を確認して再試行") }
                } else if (phase in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING)) Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(PhotoRollRenderStage.DECODE to "DECODE", PhotoRollRenderStage.INPUTS to "測光", PhotoRollRenderStage.BASE to "R7土台",
                            PhotoRollRenderStage.LOOK to "LOOK", PhotoRollRenderStage.DISPLAY to "表示").forEach { (candidate, label) ->
                            Text(label, style = CaptureDesign.Label, color = if (candidate == stage) CaptureDesign.Amber else CaptureDesign.Muted)
                        }
                    }
                    Text(if (phase == PhotoRollPhase.CHECKING) "原本を確認中…" else stage?.label ?: "DNGを処理中…",
                        Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
                } else Text(if (phase == PhotoRollPhase.PAUSED) "処理を中断しました" else "原本の確認待ち", color = CaptureDesign.Muted)
            }
            if (message != null && phase != PhotoRollPhase.BLOCKED) Text(message, Modifier.fillMaxWidth().padding(8.dp), color = CaptureDesign.Muted, style = CaptureDesign.Explanation)
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
                PhotoEditParameter.entries.forEach { key ->
                    val supported = mode in setOf(PhotoRollPreviewMode.MANUAL_TRIAL, PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.PRIVATE_TRIAL) && (key == PhotoEditParameter.STRENGTH || adjustments)
                    val value = when (key) { PhotoEditParameter.STRENGTH -> requested.strength; PhotoEditParameter.TEMPERATURE -> requested.temperature; PhotoEditParameter.BRIGHTNESS -> requested.brightness }
                    val displayed = state.displayedValues?.let { when (key) { PhotoEditParameter.STRENGTH -> it.strength; PhotoEditParameter.TEMPERATURE -> it.temperature; PhotoEditParameter.BRIGHTNESS -> it.brightness } }
                    val dirty = supported && value != displayed
                    TextButton(onClick = { parameter = key; if (session == null) begin() }, enabled = supported && foreground && (session != null || beginEnabled),
                        modifier = Modifier.weight(1f).heightIn(min = 72.dp).border(1.dp, CaptureDesign.Line), shape = RectangleShape, contentPadding = PaddingValues(4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(when (key) { PhotoEditParameter.STRENGTH -> "STRENGTH"; PhotoEditParameter.TEMPERATURE -> "色温度"; PhotoEditParameter.BRIGHTNESS -> "明るさ" }, style = CaptureDesign.Label)
                            Text(if (supported && value != null) editValueLabel(key, value) else "—", style = CaptureDesign.Value, color = if (dirty) CaptureDesign.Amber else CaptureDesign.Text)
                            Text(when { !supported -> "未対応"; dirty -> "指定 · 未反映"; key == PhotoEditParameter.TEMPERATURE -> "相対"; key == PhotoEditParameter.BRIGHTNESS -> "EV"; else -> "表示中" },
                                style = CaptureDesign.Label, color = if (dirty) CaptureDesign.Amber else CaptureDesign.Muted)
                            if (supported && dirty) Text(
                                displayed?.let { "表示中 ${editValueLabel(key, it)}" } ?: "表示中の値なし",
                                style = CaptureDesign.Label, color = CaptureDesign.Muted)
                        }
                    }
                }
                TextButton(onClick = { comparing = !comparing }, enabled = compareEnabled, modifier = Modifier.weight(1f).heightIn(min = 72.dp), shape = RectangleShape) { Text("比較", style = CaptureDesign.Label) }
            }
            if (session != null) key(parameter) { TrialStrengthEditorPane(state, readState, parameter, foreground, phase, change, reset, apply, close) }
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
                Row(Modifier.width(100.dp).heightIn(min = 48.dp).border(1.dp, CaptureDesign.Border)) {
                    listOf(false, true).forEach { before -> TextButton(onClick = { comparing = before }, enabled = compareEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).background(if (comparing == before) CaptureDesign.Selected else CaptureDesign.Ink)
                            .semantics { selected = comparing == before }, shape = RectangleShape, contentPadding = PaddingValues(2.dp)) { Text(if (before) "比較" else "表示", style = CaptureDesign.Label) } }
                }
            }
            if (phase == PhotoRollPhase.PAUSED) TextButton(onClick = retry, enabled = foreground, shape = RectangleShape) { Text("原本を確認して再試行") }
        }
    }
}
