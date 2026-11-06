package dev.cinematic.p0

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

private val PanelText = CaptureDesign.Text
private val PanelMuted = CaptureDesign.Muted
private val PanelWarning = CaptureDesign.Amber
private val PanelFailure = CaptureDesign.Failed

@Composable private fun PanelSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = PanelMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        HorizontalDivider(color = PanelMuted.copy(alpha = .3f))
        content()
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

@Composable internal fun CaptureDiagnosticsPanel(probe: CameraProbe, enabled: Boolean, export: () -> Unit, nowMs: Long) {
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
                Text("最新操作intent（未受付を含む）: ${probe.liveControlIntent ?: "なし"}",
                    fontSize = 11.sp, color = PanelMuted)
                Row(Modifier.fillMaxWidth()) {
                    listOf("API要求", "受付履歴", "直近実測").forEach { Text(it, Modifier.weight(1f), color = PanelMuted, fontSize = 11.sp) }
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
            PanelSection("主値のsource / 観測の期限") {
                val target = logicalCaptureTarget(probe.currentEpoch, probe.controlReceipt, probe.liveExposureTarget,
                    probe.stillExposure?.target, probe.appliedExposure)
                listOf(CaptureKey.ISO, CaptureKey.SHUTTER).forEach { key ->
                    Text(exposureDisplayDiagnostic(key, probe.previewExposureSamples, probe.currentEpoch, nowMs,
                        probe.exposureChoices, probe.bound, target), color = PanelMuted, fontSize = 11.sp)
                }
                Text("観測は元の取得時刻から2000ms以内だけ有効です。設定値・API受付・実測は別です。", color = PanelMuted, fontSize = 11.sp)
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
