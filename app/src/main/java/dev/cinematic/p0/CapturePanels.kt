package dev.cinematic.p0

import androidx.camera.core.ImageCapture
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PanelText = Color(0xFFE4E6E8)
private val PanelMuted = Color(0xFFA3AAB1)
private val PanelWarning = Color(0xFFE6A35C)
private val PanelFailure = Color(0xFFEF8A74)

@Composable private fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = PanelMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        HorizontalDivider(color = PanelMuted.copy(alpha = .3f))
        content()
    }
}

@Composable internal fun CaptureSettingsPanel(probe: CameraProbe, enabled: Boolean, diagnostics: () -> Unit, reset: () -> Unit) {
    val editable = enabled && probe.stillExposure?.ownsCapture != true
    if (!editable) Text("撮影・録画・設定処理中です。設定変更は完了後に行えます。", color = PanelWarning, fontSize = 12.sp)
    PanelSection(if (probe.mode == CaptureMode.STILL) "STILL / 保存" else "CINE / 動画組合せ") {
        if (probe.mode == CaptureMode.STILL) {
            Text("原画角を保持 · 比率クロップ保存は未実装", color = PanelMuted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ImageCapture.OUTPUT_FORMAT_JPEG, ImageCapture.OUTPUT_FORMAT_RAW, ImageCapture.OUTPUT_FORMAT_RAW_JPEG).forEach { format ->
                    FilterChip(selected = probe.format == format, onClick = { probe.selectFormat(format) },
                        enabled = editable && format in probe.formats, label = { Text(CameraProbe.formatName(format)) })
                }
            }
            val missing = listOf(ImageCapture.OUTPUT_FORMAT_RAW, ImageCapture.OUTPUT_FORMAT_RAW_JPEG).filter { it !in probe.formats }
            if (missing.isNotEmpty()) Text("非対応: ${missing.joinToString { CameraProbe.formatName(it) }}（CameraX能力）", color = PanelMuted, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(probe.highResolution, probe::chooseHighResolution, enabled = editable)
                Text("API対応の高解像度を優先", fontSize = 13.sp)
            }
        } else {
            Text("選択した組合せ / bind結果。実ファイルの解像度・fpsとは別です。", color = PanelMuted, fontSize = 11.sp)
            probe.videoChoices.forEach { option ->
                FilterChip(selected = probe.bound && probe.activeVideoChoice?.key == option.key,
                    onClick = { probe.selectVideo(option.key) }, enabled = editable, label = { Text(option.label, fontSize = 12.sp) })
            }
            if (probe.videoChoices.isEmpty()) Text("対応組合せは未取得です", color = PanelMuted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(probe.audioWanted, probe::setAudio, enabled = editable)
                Text("音声収録 · 開始時にマイク許可", fontSize = 13.sp)
            }
        }
    }
    PanelSection("CAMERA") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            probe.reports.forEach { report -> FilterChip(selected = report.id == probe.selectedId,
                onClick = { probe.selectCamera(report.id) }, enabled = editable, label = { Text("ID ${report.id}") }) }
        }
        Text("能力は診断で確認できます。選択と接続成功は別の状態です。", color = PanelMuted, fontSize = 11.sp)
    }
    PanelSection("照明対策 / LOCK") {
        Text("照明対策はAUTO AE時のみ有効 · LED除去保証なし", color = PanelMuted, fontSize = 11.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = probe.overrides.antiBanding == null, onClick = { probe.selectAntiBanding(null) },
                enabled = editable && probe.controlReady, label = { Text("既定") })
            probe.selectedReport?.overrides?.antiBandingModes?.sorted()?.forEach { value ->
                FilterChip(selected = probe.overrides.antiBanding == value, onClick = { probe.selectAntiBanding(value) },
                    enabled = editable && probe.controlReady, label = { Text(SensorModeNames.antiBanding(value)) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = probe.autoLocks.exposure, onClick = probe::toggleExposureLock,
                enabled = editable && probe.controlReady && !probe.manualExposure && probe.selectedReport?.locks?.exposure == true,
                label = { Text("AE LOCK") })
            FilterChip(selected = probe.autoLocks.whiteBalance, onClick = probe::toggleWhiteBalanceLock,
                enabled = editable && probe.controlReady && probe.selectedReport?.locks?.whiteBalance == true &&
                    (probe.overrides.whiteBalance == null || probe.overrides.whiteBalance == 1), label = { Text("WB LOCK") })
        }
        if (probe.manualExposure) Text("AE LOCKは露出AUTO時のみ操作できます", color = PanelMuted, fontSize = 11.sp)
        if (probe.overrides.whiteBalance != null && probe.overrides.whiteBalance != 1)
            Text("WB LOCKはWB AUTO時のみ操作できます", color = PanelMuted, fontSize = 11.sp)
        if (probe.selectedReport?.locks?.exposure != true || probe.selectedReport?.locks?.whiteBalance != true)
            Text("このカメラでは一部LOCKが非対応、または能力未取得です", color = PanelMuted, fontSize = 11.sp)
        Text("LOCKは当該接続だけの要求です。受付と実測は診断で区別します。", color = PanelMuted, fontSize = 11.sp)
    }
    PanelSection("管理 / 診断") {
        TextButton(onClick = reset, enabled = editable && probe.bound) { Text("撮影制御をリセット…", color = PanelWarning) }
        TextButton(onClick = diagnostics, enabled = enabled || probe.stillExposure?.presentation()?.recoveryRequired == true) { Text("診断・能力・検証レポート ›") }
    }
}

@Composable internal fun CaptureExposureSummary(probe: CameraProbe) {
    val state = probe.stillExposure ?: return
    val shown = state.presentation()
    PanelSection("STILL / 設定と保存状態") {
        Text("${shown.stage} · ${shown.phase}", color = if (shown.recoveryRequired) PanelFailure else PanelWarning, fontSize = 12.sp)
        Text("撮影target: ${shown.captureTarget}\npreview target: ${shown.previewTarget}", color = PanelText, fontSize = 12.sp)
        Text("preview API受付: ${shown.previewApi}\n撮影API受付: ${shown.captureApi}", color = PanelMuted, fontSize = 11.sp)
        Text("直近preview実測: ${shown.previewActual}\n直近撮影実測: ${shown.stillActual}\n保存結果: ${shown.files}", color = PanelMuted, fontSize = 11.sp)
        Text("${state.detail}。受付・実測・保存は別結果で、明るさ一致は未検証です。", color = PanelMuted, fontSize = 11.sp)
        if (shown.recoveryRequired) TextButton(onClick = probe::stop) { Text("停止・復旧", color = PanelFailure) }
    }
}

@Composable internal fun CaptureDiagnosticsPanel(probe: CameraProbe, enabled: Boolean, export: () -> Unit) {
    CaptureExposureSummary(probe)
    PanelSection("接続 / レポート") {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = probe::bind, enabled = enabled && !probe.bound) { Text("Preview開始") }
            TextButton(onClick = probe::stop, enabled = enabled && probe.bound) { Text("Preview停止") }
            TextButton(onClick = export, enabled = enabled && !probe.exportingReport) { Text("レポート保存") }
        }
        Text("端末build・設定・ログを含みます。共有前に確認してください。画像・音声本体や自動アップロードはありません。", color = PanelMuted, fontSize = 12.sp)
    }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PanelSection("要求 / 受付履歴 / 直近preview実測") {
                val table = controlDiagnosticTable(probe.controlReceipt, probe.sample, probe.currentEpoch)
                Text("epoch ${probe.currentEpoch} · API ${probe.controlReceipt.phase}\n${probe.controlReceipt.detail}", fontSize = 12.sp)
                Row(Modifier.fillMaxWidth()) {
                    listOf("設定要求", "受付履歴", "直近実測").forEach { Text(it, Modifier.weight(1f), color = PanelMuted, fontSize = 11.sp) }
                }
                table.rows.forEach { row ->
                    Text(row.label, color = PanelMuted, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(row.requested, row.acceptedHistory, row.observed).forEach { Text(it, Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                    }
                    HorizontalDivider(color = PanelMuted.copy(alpha = .15f))
                }
                Text(when {
                    !table.receiptCurrent -> "旧接続の受付は採用外です"
                    !table.sampleCurrent -> "現epochのpreview実測待ちです"
                    table.corresponds -> "現受付に対応するframeです。実測値・画質の一致は別評価です。"
                    else -> "直近frameを表示中です。最新要求への対応・一致は未確認です。"
                }, color = PanelMuted, fontSize = 11.sp)
            }
            PanelSection("出力の設定 / bind情報") {
                Text(probe.exposurePolicyStatus, fontSize = 11.sp, color = PanelMuted)
                Text(if (probe.mode == CaptureMode.STILL) probe.stillResolution else probe.videoSelection,
                    fontSize = 11.sp, color = PanelMuted)
            }
            PanelSection("撮影 / 録画 / OS") {
                Text("${BuildConfig.VERSION_NAME} · ${probe.mode} · ID ${probe.selectedId ?: "—"}\nbound=${probe.bound} ready=${probe.controlReady}\n${probe.outputOrientation}", fontSize = 12.sp)
                Text("${probe.recordingStage}: ${probe.recordingStats}\n${probe.recordingAudioState}", fontSize = 12.sp)
                Text("${probe.thermalStatus}\n${probe.focusStatus}\n${probe.evStatus}\n${probe.lockStatus}", fontSize = 12.sp)
            }
            var details by remember { mutableStateOf(false) }
            TextButton(onClick = { details = !details }) { Text(if (details) "詳細・能力・ログを閉じる" else "詳細・能力・ログを表示") }
            if (details) PanelSection("詳細（参考）") {
                Text("直近CaptureResult（intent別の採用値とは別）:\n${probe.measured}\n${probe.status}", fontSize = 11.sp)
                Text("CameraX:\n${probe.cameraXReports}\nCamera2:\n${probe.reports.joinToString("\n\n") { it.text }}", fontSize = 11.sp)
                Text("直近ログ（アプリ専用領域）\n${probe.lines}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
