package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.photoedit.PhotoEditBar
import dev.cinematic.p0.photoedit.PhotoEditInputToken
import java.util.Locale

internal fun trialStrengthLabel(value: Double) = String.format(Locale.ROOT, "%.1f", value * 100).removeSuffix(".0")

/** Real private-trial controls. No temperature/brightness placeholders or settings-save action. */
@Composable
internal fun TrialStrengthEditorPane(
    state: TrialStrengthEditor,
    readState: () -> TrialStrengthEditor,
    foreground: Boolean,
    phase: PhotoRollPhase,
    stage: PhotoRollRenderStage?,
    message: String?,
    preview: Bitmap?,
    comparison: Bitmap?,
    comparisonLabel: String?,
    change: (PhotoEditInputToken, Double) -> Unit,
    reset: (PhotoEditInputToken) -> Unit,
    apply: (PhotoEditInputToken) -> Unit,
    cancel: (PhotoEditInputToken) -> Unit,
    close: (PhotoEditInputToken) -> Unit,
    retry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = state.session ?: return
    val token = session.inputToken
    BoxWithConstraints(modifier.fillMaxSize()) {
        val controlsMaxHeight = maxHeight * .5f
        Column(Modifier.fillMaxSize()) {
            val busy = phase in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING)
            Text(when (phase) {
                PhotoRollPhase.CHECKING -> "原本を確認中…"
                PhotoRollPhase.RENDERING -> stage?.label ?: "試験現像中…"
                PhotoRollPhase.DISPLAYED -> "表示画像の強度 ${state.displayedStrength?.let(::trialStrengthLabel) ?: "未確認"}"
                PhotoRollPhase.BLOCKED -> "試験現像を確認できません"
                else -> "試験表示の確認待ち"
            }, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (preview != null && phase == PhotoRollPhase.DISPLAYED && state.displayedStrength != null)
                    PhotoRollComparisonImage(preview, comparison, foreground,
                        "試験JPEG・強度${trialStrengthLabel(state.displayedStrength)}（顔保護なし・暫定色）",
                        comparisonLabel, Modifier.fillMaxSize())
                else Text(if (busy) "処理が完了すると結果を表示します" else "画像はまだ確認できません")
            }
            // Keep the image visible on short screens; controls and errors remain reachable at large font scales.
            Column(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState())) {
                PhotoEditBar("Stage Clear 強度（0〜100）", session.draft.strength,
                    valueNow = { readState().session?.draft?.strength ?: Double.NaN },
                    scale = session.scales.strength, interactionKey = token, enabled = foreground,
                    formatValue = ::trialStrengthLabel, onValueChange = { change(token, it) },
                    onValueChangeFinished = { apply(token) })
                Text(if (state.hasUnappliedChanges) "ドラッグ中は数値を変更し、指を離すと画像を更新します"
                    else "指定 ${trialStrengthLabel(session.draft.strength)} / 反映要求 ${trialStrengthLabel(state.requestedStrength)}",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { reset(token) }, enabled = foreground, modifier = Modifier.weight(1f)) { Text("既定50") }
                    TextButton(onClick = { cancel(token) }, enabled = foreground, modifier = Modifier.weight(1f)) { Text("取消") }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { close(token) }, enabled = foreground && !state.hasUnappliedChanges &&
                        phase == PhotoRollPhase.DISPLAYED && state.displayedStrength == state.requestedStrength,
                        modifier = Modifier.weight(1f)) { Text("調整を閉じる") }
                }
                if (phase == PhotoRollPhase.BLOCKED) {
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = retry, enabled = foreground) { Text("原本を確認して再試行") }
                }
                Text("左右タップは1刻み。0はRAW基準表示とは異なります。比較は画像長押しで確認できます", style = MaterialTheme.typography.bodySmall)
                Text("最大1024px・顔保護なし・診断露出/暫定色の試験表示です。共有保存なし。調整値は一時的で、再選択や画面再生成で既定50に戻ります",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
