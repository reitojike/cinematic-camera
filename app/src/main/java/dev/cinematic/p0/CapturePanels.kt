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
