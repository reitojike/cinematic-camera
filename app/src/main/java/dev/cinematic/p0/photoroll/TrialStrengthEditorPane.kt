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
import dev.cinematic.p0.photoedit.PhotoEditParameter
import java.util.Locale

internal fun trialStrengthLabel(value: Double) = String.format(Locale.ROOT, "%.1f", value * 100).removeSuffix(".0")

/** One temporary strength edit for trial and completed renderers. Save stays outside this pane. */
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
    change: (PhotoEditInputToken, PhotoEditParameter, Double) -> Unit,
    reset: (PhotoEditInputToken) -> Unit,
    apply: (PhotoEditInputToken) -> Unit,
    cancel: (PhotoEditInputToken) -> Unit,
    close: (PhotoEditInputToken) -> Unit,
    retry: () -> Unit,
    saveable: Boolean = false,
    trialQuality: Boolean = false,
    adjustments: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val session = state.session ?: return
    val token = session.inputToken
    val displayedStrength = state.displayedStrength
    BoxWithConstraints(modifier.fillMaxSize()) {
        val controlsMaxHeight = maxHeight * .5f
        Column(Modifier.fillMaxSize()) {
            val busy = phase in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING)
            Text(when (phase) {
                PhotoRollPhase.CHECKING -> "原本を確認中"
                PhotoRollPhase.RENDERING -> stage?.label ?: "現像中"
                PhotoRollPhase.DISPLAYED -> "表示画像の強度 ${state.displayedStrength?.let(::trialStrengthLabel) ?: "未確認"}"
                PhotoRollPhase.BLOCKED -> "現像できません"
                else -> "画像の確認待ち"
            }, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (preview != null && phase == PhotoRollPhase.DISPLAYED && displayedStrength != null)
                    PhotoRollComparisonImage(preview, comparison, foreground,
                        if (trialQuality) "原寸保存前の縮小プレビュー・強度${trialStrengthLabel(displayedStrength)}"
                        else if (saveable) "現像プレビュー・強度${trialStrengthLabel(displayedStrength)}"
                        else "試験JPEG・強度${trialStrengthLabel(displayedStrength)}（保存不可・顔未適用）",
                        comparisonLabel, Modifier.fillMaxSize())
                else Text(if (busy) "処理が終わると結果を表示します" else "画像はまだ確認できません")
            }
            // Keep the image visible on short screens; controls and errors remain reachable at large font scales.
            Column(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight).verticalScroll(rememberScrollState())) {
                PhotoEditBar("Stage Clear 強度（0〜100）", session.draft.strength,
                    valueNow = { readState().session?.draft?.strength ?: Double.NaN },
                    scale = session.scales.strength, interactionKey = token, enabled = foreground,
                    formatValue = ::trialStrengthLabel,
                    onValueChange = { change(token, PhotoEditParameter.STRENGTH, it) },
                    onValueChangeFinished = { apply(token) })
                if (adjustments) {
                    val warmth = requireNotNull(session.draft.temperature)
                    val exposureEv = requireNotNull(session.draft.brightness)
                    PhotoEditBar("色温度（相対・K値ではありません）", warmth,
                        valueNow = { readState().session?.draft?.temperature ?: Double.NaN },
                        scale = requireNotNull(session.scales.temperature), interactionKey = token, enabled = foreground,
                        formatValue = { String.format(Locale.ROOT, "%+.0f", it) },
                        onValueChange = { change(token, PhotoEditParameter.TEMPERATURE, it) },
                        onValueChangeFinished = { apply(token) })
                    PhotoEditBar("明るさ（EV）", exposureEv,
                        valueNow = { readState().session?.draft?.brightness ?: Double.NaN },
                        scale = requireNotNull(session.scales.brightness), interactionKey = token, enabled = foreground,
                        formatValue = { String.format(Locale.ROOT, "%+.1f", it) },
                        onValueChange = { change(token, PhotoEditParameter.BRIGHTNESS, it) },
                        onValueChangeFinished = { apply(token) })
                }
                Text(if (state.hasUnappliedChanges) "ドラッグ中は数値を変更し、指を離すと画像を更新します"
                    else "指定 ${trialStrengthLabel(session.draft.strength)} / 現像要求 ${trialStrengthLabel(state.requestedStrength)}",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { reset(token) }, enabled = foreground, modifier = Modifier.weight(1f)) {
                        Text(if (adjustments) "調整をリセット" else "強度50に戻す") }
                    TextButton(onClick = { cancel(token) }, enabled = foreground, modifier = Modifier.weight(1f)) { Text("取消") }
                }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { close(token) }, enabled = foreground && !state.hasUnappliedChanges &&
                        phase == PhotoRollPhase.DISPLAYED && state.displayedStrength == state.requestedStrength,
                        modifier = Modifier.weight(1f)) {
                        Text(if (saveable) "調整を閉じて保存へ" else "編集を閉じる")
                    }
                }
                if (phase == PhotoRollPhase.BLOCKED) {
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = retry, enabled = foreground) { Text("原本を確認して再試行") }
                }
                Text("左右タップは1刻み。強度0はRAW基準表示とは異なります。比較は画像を長押しして確認できます。",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (trialQuality) "試験品質です。顔保護なし・診断露出・暫定色で、完成品質ではありません。設定は一時的です。調整を閉じ、表示した強度のJPEGを手動保存してください。"
                    else if (saveable) "設定はこの画面内で一時的です。調整を閉じ、表示した強度のJPEGを保存してください。"
                    else "最大1024px・顔保護なし・診断露出・暫定色の試験表示です。共有保存はできません。設定は一時的で、再選択・画面再生成で強度50に戻ります。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
