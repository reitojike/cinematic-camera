package dev.cinematic.p0

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class DiagnosticTab(val label: String) { CONTROL("制御"), OUTPUT("出力"), CAPABILITIES("能力"), LOG("ログ") }

@Composable private fun DiagnosticText(value: String, color: Color = CaptureDesign.Text) =
    Text(value, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable private fun DiagnosticPair(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = CaptureDesign.Label, color = CaptureDesign.Muted)
        DiagnosticText(value)
        HorizontalDivider(color = CaptureDesign.Line)
    }
}

@Composable internal fun CaptureDiagnosticsSheet(probe: CameraProbe, enabled: Boolean, export: () -> Unit, nowMs: Long,
    settings: () -> Unit) {
    var tab by remember { mutableStateOf(DiagnosticTab.CONTROL) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Keep at least a readable body viewport after header, summary, tabs and footer.
        // Narrow/large-text layouts wrap the fixed sections, so scroll the whole sheet.
        val compact = captureNeedsFullScroll(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale.coerceAtLeast(1f), 520f)
        Column(if (compact) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = settings, shape = RectangleShape, contentPadding = PaddingValues(8.dp)) { Text("‹ 設定") }
                Text("診断", Modifier.weight(1f), color = CaptureDesign.Text)
                Text("CameraX 1.6.2\n${BuildConfig.VERSION_NAME}", style = CaptureDesign.Label, color = CaptureDesign.Muted)
            }
            Row(Modifier.fillMaxWidth()) {
                val phase = probe.controlReceipt.phase
                val summaries = listOf("EPOCH" to probe.currentEpoch.toString(), "API" to phase.name,
                    "PREVIEW" to if (probe.bound) "BOUND" else "STOP", "熱" to ProbeControls.thermalName(probe.thermalSeverity))
                summaries.forEachIndexed { index, (label, value) ->
                    Column(Modifier.weight(1f).heightIn(min = 64.dp).border(1.dp, CaptureDesign.Line).padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(label, style = CaptureDesign.Label, color = CaptureDesign.Muted)
                        Text(value, style = CaptureDesign.Value, color = if (index == 1) when (phase) {
                            ControlPhase.ACCEPTED -> CaptureDesign.Accepted
                            ControlPhase.UNKNOWN, ControlPhase.REJECTED -> CaptureDesign.Failed
                            ControlPhase.PENDING -> CaptureDesign.Amber
                            else -> CaptureDesign.Text
                        } else CaptureDesign.Text)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                DiagnosticTab.entries.forEach { candidate ->
                    Column(Modifier.weight(1f).background(CaptureDesign.Ink).captureControlSurface(candidate == tab, divider = false, roundTopLineToPixels = true)) {
                        Spacer(Modifier.fillMaxWidth().height(3.dp))
                        TextButton(onClick = { tab = candidate }, modifier = Modifier.fillMaxWidth().heightIn(min = 41.dp).semantics { selected = candidate == tab },
                            shape = RectangleShape) { Text(candidate.label, fontFamily = FontFamily.Monospace) }
                    }
                }
            }
            SelectionContainer(modifier = if (compact) Modifier else Modifier.weight(1f)) {
                Column((if (compact) Modifier else Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                    .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (tab) {
                        DiagnosticTab.CONTROL -> DiagnosticControls(probe, nowMs)
                        DiagnosticTab.OUTPUT -> {
                            CaptureExposureSummary(probe)
                            DiagnosticPair("出力の設定 / bind情報", probe.exposurePolicyStatus)
                            DiagnosticPair("解像度 / 動画組合せ", if (probe.mode == CaptureMode.STILL) probe.stillResolution else probe.videoSelection)
                            DiagnosticPair("撮影 / 接続", "${BuildConfig.VERSION_NAME} · ${probe.mode} · ID ${probe.selectedId ?: "—"}\nbound=${probe.bound} ready=${probe.controlReady}")
                            DiagnosticPair("出力向き", probe.outputOrientation.toString())
                            DiagnosticPair("録画 / 音声", "${probe.recordingStage}: ${probe.recordingStats}\n${probe.recordingAudioState}")
                            DiagnosticPair("OS / 制御状態", "${probe.thermalStatus}\n${probe.focusStatus}\n${probe.evStatus}\n${probe.lockStatus}")
                            DiagnosticPair("直近CaptureResult（intent別の採用値とは別）", probe.measured)
                            DiagnosticPair("状態", probe.status)
                        }
                        DiagnosticTab.CAPABILITIES -> {
                            DiagnosticPair("CameraX", probe.cameraXReports)
                            probe.reports.forEach { report ->
                                DiagnosticPair("Camera2 · ID ${report.id}", report.text)
                                report.manualBlockReason?.let { DiagnosticText("マニュアル非対応: $it", CaptureDesign.Failed) }
                            }
                        }
                        DiagnosticTab.LOG -> {
                            Text("直近ログ（アプリ専用領域）", style = CaptureDesign.Label, color = CaptureDesign.Muted)
                            // Retain raw text; free-form log content is not a typed acceptance receipt.
                            Text(androidx.compose.ui.text.buildAnnotatedString {
                                append(probe.lines)
                                Regex("\\bACCEPTED\\b|\\bAccepted=").findAll(probe.lines).forEach { match ->
                                    addStyle(androidx.compose.ui.text.SpanStyle(color = CaptureDesign.Accepted), match.range.first, match.range.last + 1)
                                }
                            }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = CaptureDesign.Muted)
                        }
                    }
                }
            }
            HorizontalDivider(color = CaptureDesign.Line)
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = probe::bind, enabled = enabled && !probe.bound, modifier = Modifier.weight(1f), shape = RectangleShape) { Text("Preview開始") }
                    OutlinedButton(onClick = probe::stop, enabled = enabled && probe.bound, modifier = Modifier.weight(1f), shape = RectangleShape) { Text("Preview停止") }
                }
                Button(onClick = export, enabled = enabled && !probe.exportingReport, modifier = Modifier.fillMaxWidth(), shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = CaptureDesign.Text, contentColor = CaptureDesign.Ink)) { Text("検証レポートを保存…") }
                Text("端末build・設定・ログを含みます。共有前に確認してください。画像・音声本体や自動アップロードはありません。",
                    style = CaptureDesign.Explanation, color = CaptureDesign.Muted)
            }
        }
    }
}

@Composable private fun DiagnosticControls(probe: CameraProbe, nowMs: Long) {
    val table = controlDiagnosticTable(probe.controlReceipt, probe.sample, probe.currentEpoch)
    DiagnosticText("epoch ${probe.currentEpoch} · API ${probe.controlReceipt.phase}\n${probe.controlReceipt.detail}")
    Row(Modifier.fillMaxWidth()) {
        listOf("項目", "API要求", "受付履歴", "直近実測").forEach {
            Text(it, Modifier.weight(1f).border(1.dp, CaptureDesign.Line).padding(4.dp), style = CaptureDesign.Label, color = CaptureDesign.Muted)
        }
    }
    table.rows.forEach { row ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            listOf(row.label, row.requested, row.acceptedHistory, row.observed).forEach { value ->
                Text(value, Modifier.weight(1f).fillMaxHeight().border(1.dp, CaptureDesign.Line).padding(4.dp), fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, color = CaptureDesign.Text)
            }
        }
    }
    DiagnosticText(when {
        !table.receiptCurrent -> "旧接続の受付は採用外です"
        !table.sampleCurrent -> "現epochのpreview実測待ちです"
        table.corresponds -> "現受付に対応するframeです。実測値・画質の一致は別評価です。"
        else -> "直近frameを表示中です。最新要求への対応・一致は未確認です。"
    }, CaptureDesign.Muted)
    val target = logicalCaptureTarget(probe.currentEpoch, probe.controlReceipt, probe.liveExposureTarget,
        probe.stillExposure?.target, probe.appliedExposure)
    listOf(CaptureKey.ISO, CaptureKey.SHUTTER).forEach { key ->
        DiagnosticText(exposureDisplayDiagnostic(key, probe.previewExposureSamples, probe.currentEpoch, nowMs,
            probe.exposureChoices, probe.bound, target), CaptureDesign.Muted)
        val sample = probe.previewExposureSamples.sample(key)
        val age = sample?.takeIf { probe.bound && it.epoch == probe.currentEpoch && nowMs >= it.receivedAtMs }
            ?.let { nowMs - it.receivedAtMs }
        if (age != null) {
            DiagnosticText("${key.name} 観測経過 ${age}ms / 2000ms${if (age > 2000) " · 期限切れ" else ""}",
                if (age >= 1500) CaptureDesign.Amber else CaptureDesign.Muted)
            LinearProgressIndicator(progress = { (age / 2000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (age >= 1500) CaptureDesign.Amber else CaptureDesign.Accepted, trackColor = CaptureDesign.Line)
        } else DiagnosticText("${key.name} 観測時刻未取得 / 現接続の観測待ち", CaptureDesign.Muted)
    }
    DiagnosticText("観測は元の取得時刻から2000ms以内だけ有効です。設定値・API受付・実測は別です。", CaptureDesign.Muted)
    DiagnosticPair("最新操作intent（未受付を含む）", probe.liveControlIntent?.toString() ?: "なし")
}
