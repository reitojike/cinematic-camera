package dev.cinematic.p0.photoedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.util.Locale

/** Caller connects apply/reset/cancel to its real render owner; this component submits no fake result. */
@Composable
internal fun PhotoAdjustmentControls(
    state: PhotoAdjustmentRenderState,
    readState: () -> PhotoAdjustmentRenderState,
    enabled: Boolean,
    change: (PhotoEditInputToken, PhotoEditParameter, Double) -> Unit,
    apply: (PhotoEditInputToken) -> Unit,
    reset: (PhotoEditInputToken) -> Unit,
    cancel: (PhotoEditInputToken) -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session
    val token = session.inputToken
    Column(modifier) {
        for (parameter in PhotoEditParameter.entries) {
            val scale = session.scales[parameter] ?: continue
            val value = session.draft[parameter] ?: continue
            val label = when (parameter) {
                PhotoEditParameter.STRENGTH -> "Stage Clear 強度"
                PhotoEditParameter.TEMPERATURE -> "色温度（相対） 寒色 ↔ 暖色"
                PhotoEditParameter.BRIGHTNESS -> "明るさ（EV）"
            }
            PhotoEditBar(label, value, valueNow = { readState().session.draft[parameter] ?: Double.NaN },
                scale, token, enabled && session.active,
                formatValue = { String.format(Locale.ROOT, "%.1f", if (parameter == PhotoEditParameter.STRENGTH) it * 100 else it) },
                onValueChange = { change(token, parameter, it) }, onValueChangeFinished = { apply(token) })
        }
        Text("色温度0は撮影時の色合い。左右タップで微調整し、指を離すと画像を更新します")
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = { reset(token) }, enabled = enabled && session.active) { Text("調整をリセット") }
            TextButton(onClick = { cancel(token) }, enabled = enabled && session.active) { Text("取消") }
        }
    }
}
