package dev.cinematic.p0

import androidx.camera.core.ImageCapture
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private data class SettingsChange(val epoch: Int, val section: String, val target: String, val before: String)

internal fun Modifier.settingsOutline(pending: Boolean = false): Modifier = drawBehind {
    val width = 1.dp.toPx()
    drawRect(if (pending) CaptureDesign.Amber else CaptureDesign.Border,
        topLeft = Offset(width / 2, width / 2), size = Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)),
        style = Stroke(width, pathEffect = if (pending) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null))
}

@Composable private fun SettingNote(text: String) = Text(text, color = CaptureDesign.Muted, style = CaptureDesign.Explanation)

@Composable private fun SettingGroup(label: String, effect: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = CaptureDesign.Muted, style = CaptureDesign.Label)
            Text(effect, Modifier.border(1.dp, CaptureDesign.Border).padding(horizontal = 5.dp, vertical = 2.dp),
                color = CaptureDesign.Muted, style = CaptureDesign.Label)
        }
        content()
    }
}

@Composable private fun SettingChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier = Modifier,
    unsupported: Boolean = false, pending: Boolean = false, trailing: String? = null, action: () -> Unit) {
    val background = when { pending -> CaptureDesign.Amber.copy(alpha = .12f); selected -> CaptureDesign.Inverted; else -> Color.Transparent }
    val foreground = when { unsupported -> CaptureDesign.Disabled; pending -> CaptureDesign.Amber; selected -> CaptureDesign.Ink; else -> CaptureDesign.Text }
    TextButton(onClick = action, enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp).settingsOutline(pending).semantics { this.selected = selected },
        shape = RectangleShape, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background, disabledContentColor = foreground)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                textAlign = if (trailing == null) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
                textDecoration = if (unsupported) TextDecoration.LineThrough else TextDecoration.None)
            trailing?.let { Text(it, Modifier.padding(start = 8.dp), style = CaptureDesign.Label) }
        }
    }
}

@Composable private fun ToggleSetting(label: String, value: Boolean, enabled: Boolean, unsupported: Boolean = false, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(true, false).forEach { desired ->
            SettingChoice(if (desired) "ON" else "OFF", value == desired, enabled, Modifier.weight(1f).semantics { contentDescription = "$label ${if (desired) "ON" else "OFF"}" }, unsupported) {
                if (value != desired) change(desired)
            }
        }
    }
}

@Composable internal fun CaptureSettingsSheet(probe: CameraProbe, enabled: Boolean, close: () -> Unit,
    diagnostics: () -> Unit, reset: () -> Unit) {
    val editable = enabled && probe.stillExposure?.ownsCapture != true
    var change by remember { mutableStateOf<SettingsChange?>(null) }
    // Only an operation started by this sheet can supply a before/after explanation.
    val pending = change?.takeIf { it.epoch == probe.currentEpoch && probe.busy }
    LaunchedEffect(probe.currentEpoch, probe.busy) {
        if (change?.epoch != probe.currentEpoch || !probe.busy) change = null
    }
    fun reconnect(section: String, before: String, target: String, action: () -> Unit) {
        val oldEpoch = probe.currentEpoch
        action()
        change = if (probe.currentEpoch != oldEpoch && probe.busy)
            SettingsChange(probe.currentEpoch, section, target, before) else null
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxHeight < 240.dp
    Column(if (compact) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("撮影設定", Modifier.weight(1f), color = CaptureDesign.Text, fontSize = 15.sp)
            Text("${probe.mode} · ID ${probe.selectedId ?: "—"}", style = CaptureDesign.Label, color = CaptureDesign.Muted)
            TextButton(onClick = close, modifier = Modifier.size(44.dp).semantics { contentDescription = "閉じる" },
                contentPadding = PaddingValues(0.dp)) { Text("×", fontSize = 22.sp) }
        }
        HorizontalDivider(color = CaptureDesign.Line)
        if (!editable && pending == null) Text("撮影・録画・設定処理中です。設定変更は完了後に行えます。",
            Modifier.fillMaxWidth().background(CaptureDesign.Amber.copy(alpha = .08f)).padding(8.dp),
            color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
        Column((if (compact) Modifier else Modifier.weight(1f).verticalScroll(rememberScrollState())).padding(horizontal = 16.dp)
            .alpha(if (!editable && pending == null) .45f else 1f)) {
            if (probe.mode == CaptureMode.STILL) {
                SettingGroup("保存形式", "再接続") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(ImageCapture.OUTPUT_FORMAT_JPEG, ImageCapture.OUTPUT_FORMAT_RAW, ImageCapture.OUTPUT_FORMAT_RAW_JPEG).forEach { format ->
                            val label = CameraProbe.formatName(format)
                            SettingChoice(label, probe.format == format, editable && format in probe.formats, Modifier.weight(1f),
                                unsupported = format !in probe.formats && pending == null,
                                pending = pending?.section == "保存形式" && pending.target == label) {
                                reconnect("保存形式", CameraProbe.formatName(probe.format), label) { probe.selectFormat(format) }
                            }
                        }
                    }
                    SettingNote("RAWはDNG。カメラロールから試験現像できます")
                    val missing = listOf(ImageCapture.OUTPUT_FORMAT_RAW, ImageCapture.OUTPUT_FORMAT_RAW_JPEG).filter { it !in probe.formats }
                    if (missing.isNotEmpty() && pending == null) SettingNote("非対応: ${missing.joinToString { CameraProbe.formatName(it) }}（CameraX能力）")
                }
                SettingGroup("解像度", "再接続") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(false, true).forEach { high ->
                            val label = if (high) "高解像度優先" else "標準"
                            SettingChoice(label, probe.highResolution == high, editable, Modifier.weight(1f),
                                pending = pending?.section == "解像度" && pending.target == label) {
                                reconnect("解像度", if (probe.highResolution) "高解像度優先" else "標準", label) { probe.chooseHighResolution(high) }
                            }
                        }
                    }
                    SettingNote("API対応の高解像度を優先。公称画素数は保証しません")
                }
            } else {
                SettingGroup("動画組合せ", "再接続") {
                    probe.videoChoices.forEach { option ->
                        val current = probe.bound && probe.activeVideoChoice?.key == option.key
                        val applying = pending?.section == "動画組合せ" && pending.target == option.label
                        SettingChoice(option.label, current, editable, Modifier.fillMaxWidth(), pending = applying,
                            trailing = when { applying -> "適用中"; current -> "現在"; else -> null }) {
                            reconnect("動画組合せ", probe.activeVideoChoice?.label ?: "未接続", option.label) { probe.selectVideo(option.key) }
                        }
                    }
                    if (probe.videoChoices.isEmpty()) SettingNote("対応組合せは未取得です")
                    SettingNote("選択した組合せ / bind結果。実ファイルの解像度・fpsとは別です。")
                }
                SettingGroup("音声", "録画開始時") {
                    ToggleSetting("音声収録", probe.audioWanted, editable, change = probe::setAudio)
                    SettingNote("音声収録 · 開始時にマイク許可")
                }
            }
            SettingGroup("カメラ", "再接続") {
                // Wrap large capability lists without squeezing their touch targets.
                probe.reports.chunked(3).forEach { reports ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        reports.forEach { report ->
                            SettingChoice("ID ${report.id}", report.id == probe.selectedId, editable, Modifier.weight(1f),
                                pending = pending?.section == "カメラ" && pending.target == "ID ${report.id}") {
                                reconnect("カメラ", "ID ${probe.selectedId ?: "—"}", "ID ${report.id}") { probe.selectCamera(report.id) }
                            }
                        }
                    }
                }
                SettingNote("能力は診断で確認できます。選択と接続成功は別の状態です。")
            }
            SettingGroup("照明対策", "即時 · AUTO露出のみ") {
                val modes = probe.selectedReport?.overrides?.antiBandingModes ?: emptySet()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf<Int?>(null, 0, 1, 2, 3).forEach { value ->
                        val supported = value == null || value in modes
                        val label = when (value) { null -> "既定"; 0 -> "OFF"; 1 -> "50"; 2 -> "60"; else -> "AUTO" }
                        SettingChoice(label, probe.overrides.antiBanding == value, editable && probe.controlReady && supported,
                            Modifier.weight(1f), unsupported = !supported) { probe.selectAntiBanding(value) }
                    }
                }
                SettingNote("照明対策はAUTO AE時のみ有効 · LED除去保証なし")
            }
            SettingGroup("一時ロック", "即時 · この接続のみ") {
                Text("AE-L", style = CaptureDesign.Label, color = CaptureDesign.Muted)
                ToggleSetting("AE LOCK", probe.autoLocks.exposure, editable && probe.controlReady && !probe.manualExposure && probe.selectedReport?.locks?.exposure == true,
                    unsupported = probe.selectedReport?.locks?.exposure != true) { probe.toggleExposureLock() }
                Text("AWB-L", style = CaptureDesign.Label, color = CaptureDesign.Muted)
                ToggleSetting("WB LOCK", probe.autoLocks.whiteBalance, editable && probe.controlReady && probe.selectedReport?.locks?.whiteBalance == true &&
                    (probe.overrides.whiteBalance == null || probe.overrides.whiteBalance == 1),
                    unsupported = probe.selectedReport?.locks?.whiteBalance != true) { probe.toggleWhiteBalanceLock() }
                if (probe.manualExposure) SettingNote("AE LOCKは露出AUTO時のみ操作できます")
                if (probe.overrides.whiteBalance != null && probe.overrides.whiteBalance != 1) SettingNote("WB LOCKはWB AUTO時のみ操作できます")
                if (probe.selectedReport?.locks?.exposure != true || probe.selectedReport?.locks?.whiteBalance != true)
                    SettingNote("このカメラでは一部LOCKが非対応、または能力未取得です")
                SettingNote("LOCKは当該接続だけの要求です。受付と実測は診断で区別します。")
            }
            if (probe.mode == CaptureMode.STILL) SettingGroup("比率", "未実装") { SettingNote("原画角 4:3 を保持") }
        }
        pending?.let { current ->
            Text("適用中：${current.section} ${current.before} → ${current.target} · ${if (probe.bound) "接続後の設定確認中" else "再接続中（プレビュー一時停止・ロック解除）"}",
                Modifier.fillMaxWidth().settingsOutline(true).background(CaptureDesign.Amber.copy(alpha = .08f)).padding(8.dp),
                color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
        }
        HorizontalDivider(color = CaptureDesign.Line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = reset, enabled = editable && probe.bound, modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                shape = RectangleShape, border = BorderStroke(1.dp, CaptureDesign.Border), contentPadding = PaddingValues(6.dp)) {
                Text("撮影制御をリセット…", color = CaptureDesign.Amber, fontSize = 12.sp)
            }
            OutlinedButton(onClick = diagnostics, enabled = enabled || probe.stillExposure?.presentation()?.recoveryRequired == true,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "診断・能力・検証レポート" },
                shape = RectangleShape, border = BorderStroke(1.dp, CaptureDesign.Border)) { Text("診断 ›") }
        }
    }
    }
}

@Composable internal fun CaptureResetDialog(dismiss: () -> Unit, reset: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Surface(color = CaptureDesign.Panel, shape = RectangleShape, border = BorderStroke(1.dp, CaptureDesign.Border)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("撮影制御をリセット", color = CaptureDesign.Text, fontSize = 16.sp)
                listOf("露出 AUTO", "EV 0.0", "FOCUS AF", "WB 標準", "照明対策 既定", "ロック 解除").chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) { row.forEach { label ->
                        Text(label, Modifier.weight(1f).border(1.dp, CaptureDesign.Border).padding(6.dp), color = CaptureDesign.Text, style = CaptureDesign.Label)
                    } }
                }
                SettingNote("露出だけのAUTOとは別の操作です。")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f), shape = RectangleShape) { Text("取消") }
                    Button(onClick = reset, modifier = Modifier.weight(1f), shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = CaptureDesign.Amber, contentColor = CaptureDesign.Ink)) { Text("全リセット") }
                }
            }
        }
    }
}
