package dev.cinematic.p0

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Test-only display reconstruction from main 53e68ffc CaptureScreen/CaptureSettingsSheet.
 * Existing draw fragments retain strings, styles and placement; probe values/actions are synthetic.
 * This is NOT an actual camera/settings screen capture. No production state or permissions run.
 */
@Composable internal fun BoxScope.LegacyCaptureMessagesFixture(name: String, horizontal: Boolean, rotation: Modifier) {
    if (name == "01-connecting" || name == "09-connecting-thermal")
        Text("接続待ち・プレビュー停止中", Modifier.align(Alignment.Center).then(rotation)
            .background(CaptureDesign.Ink.copy(alpha = .7f)).padding(12.dp), color = CaptureDesign.Text, fontSize = 13.sp)
    if (name == "02-control-recovery") Column(Modifier.align(Alignment.Center).then(rotation)
        .background(CaptureDesign.Ink.copy(alpha = .8f)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("設定状態を確認・復旧してください", color = CaptureDesign.Failed, fontSize = 13.sp)
        TextButton(onClick = {}) { Text("再接続") }
    }
    if (name == "03-capture-recovery") Column(Modifier.align(Alignment.Center).widthIn(max = 300.dp).then(rotation)
        .background(CaptureDesign.Ink.copy(alpha = .9f)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("撮影状態が不明です。停止して接続をやり直してください", color = CaptureDesign.Failed, fontSize = 12.sp)
        TextButton(onClick = {}) { Text("停止・復旧") }
    }
    val banner = when (name) {
        "04-thermal", "09-connecting-thermal" -> "熱: OS SEVERE・録画開始不可"
        "05-microphone" -> "マイク許可なし: 録画は未開始。権限を許可するか設定で音声OFFにしてください"
        "06-failure" -> "capture: synthetic failure"
        "07-progress" -> "ISO・WB変更中"
        "08-saved" -> "保存しました"
        else -> null
    }
    val bannerColor = when (name) {
        "06-failure" -> CaptureDesign.Failed
        "04-thermal", "09-connecting-thermal", "05-microphone" -> CaptureDesign.Amber
        else -> CaptureDesign.Muted
    }
    banner?.let { Text(it, Modifier.align(if (horizontal) Alignment.Center else Alignment.TopCenter)
        .padding(top = if (horizontal) 0.dp else 48.dp).widthIn(max = if (horizontal) 220.dp else 380.dp)
        .then(rotation).background(CaptureDesign.Ink.copy(alpha = .8f)).padding(8.dp), color = bannerColor, fontSize = 12.sp) }
}

@Composable internal fun LegacySettingsNote(name: String) {
    if (name == "11-settings-busy") Text("撮影・録画・設定処理中です。設定変更は完了後に行えます。",
        Modifier.fillMaxWidth().background(CaptureDesign.Amber.copy(alpha = .08f)).padding(8.dp),
        color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
    if (name == "12-settings-applying") Text("適用中：保存形式 JPEG → DNG · 再接続中（プレビュー一時停止・ロック解除）",
        Modifier.fillMaxWidth().captureOutline(true).background(CaptureDesign.Amber.copy(alpha = .08f)).padding(8.dp),
        color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
}

@Composable internal fun LegacyCaptureResetDialog(dismiss: () -> Unit, reset: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Surface(color = CaptureDesign.Panel, shape = RectangleShape, border = BorderStroke(1.dp, CaptureDesign.Border)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("撮影制御をリセット", color = CaptureDesign.Text, fontSize = 16.sp)
                listOf("露出 AUTO", "EV 0.0", "FOCUS AF", "WB 標準", "照明対策 既定", "ロック 解除").chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) { row.forEach { label ->
                        Text(label, Modifier.weight(1f).border(1.dp, CaptureDesign.Border).padding(6.dp), color = CaptureDesign.Text, style = CaptureDesign.Label)
                    } }
                }
                Text("露出だけのAUTOとは別の操作です。", color = CaptureDesign.Muted, style = CaptureDesign.Explanation)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f), shape = RectangleShape) { Text("取消") }
                    Button(onClick = reset, modifier = Modifier.weight(1f), shape = RectangleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = CaptureDesign.Amber, contentColor = CaptureDesign.Ink)) { Text("全リセット") }
                }
            }
        }
    }
}
