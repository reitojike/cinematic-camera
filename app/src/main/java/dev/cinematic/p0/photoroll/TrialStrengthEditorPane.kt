package dev.cinematic.p0.photoroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.CaptureRulerHeading
import dev.cinematic.p0.photoedit.*
import java.util.Locale

internal fun trialStrengthLabel(value: Double) = String.format(Locale.ROOT, "%.1f", value * 100).removeSuffix(".0")
internal fun editValueLabel(parameter: PhotoEditParameter, value: Double): String = when (parameter) {
    PhotoEditParameter.STRENGTH -> trialStrengthLabel(value)
    PhotoEditParameter.TEMPERATURE -> String.format(Locale.ROOT, "%+.0f", value)
    PhotoEditParameter.BRIGHTNESS -> String.format(Locale.ROOT, "%+.1f", value)
}

/** Only presentation changes; session token, current draft reader and release-to-apply stay caller-owned. */
@Composable internal fun TrialStrengthEditorPane(state: TrialStrengthEditor, readState: () -> TrialStrengthEditor,
    parameter: PhotoEditParameter, foreground: Boolean, phase: PhotoRollPhase,
    change: (PhotoEditInputToken, PhotoEditParameter, Double) -> Unit, reset: (PhotoEditInputToken) -> Unit,
    apply: (PhotoEditInputToken) -> Unit, close: (PhotoEditInputToken) -> Unit, modifier: Modifier = Modifier) {
    val session = state.session ?: return
    val token = session.inputToken
    val value = when (parameter) { PhotoEditParameter.STRENGTH -> session.draft.strength
        PhotoEditParameter.TEMPERATURE -> session.draft.temperature; PhotoEditParameter.BRIGHTNESS -> session.draft.brightness } ?: return
    val scale = when (parameter) { PhotoEditParameter.STRENGTH -> session.scales.strength
        PhotoEditParameter.TEMPERATURE -> session.scales.temperature; PhotoEditParameter.BRIGHTNESS -> session.scales.brightness } ?: return
    val displayed = state.displayedValues?.let { when (parameter) {
        PhotoEditParameter.STRENGTH -> it.strength
        PhotoEditParameter.TEMPERATURE -> it.temperature
        PhotoEditParameter.BRIGHTNESS -> it.brightness
    } }
    val title = when (parameter) { PhotoEditParameter.STRENGTH -> "STRENGTH"
        PhotoEditParameter.TEMPERATURE -> "色温度 · 相対（K値ではありません）"; PhotoEditParameter.BRIGHTNESS -> "明るさ · EV" }
    Column(modifier.fillMaxWidth().heightIn(min = 104.dp).background(CaptureDesign.Ruler).border(1.dp, CaptureDesign.Line)) {
        CaptureRulerHeading(title, onClose = { close(token) },
            closeEnabled = foreground && !state.hasUnappliedChanges && phase == PhotoRollPhase.DISPLAYED &&
                state.displayedValues == state.requestedValues,
            displayLabel = if (parameter == PhotoEditParameter.TEMPERATURE) "色温度 · 相対" else title) {
            TextButton(onClick = { reset(token) }, enabled = foreground, shape = RectangleShape) { Text("RESET", style = CaptureDesign.Label) }
        }
        if (parameter == PhotoEditParameter.TEMPERATURE) Text("相対調整です（K値ではありません）",
            Modifier.padding(horizontal = 12.dp), style = CaptureDesign.Explanation, color = CaptureDesign.Muted)
        PhotoEditBar(title, value,
            valueNow = { readState().session?.draft?.let { when (parameter) { PhotoEditParameter.STRENGTH -> it.strength
                PhotoEditParameter.TEMPERATURE -> it.temperature; PhotoEditParameter.BRIGHTNESS -> it.brightness } } ?: Double.NaN },
            scale = scale, interactionKey = token, enabled = foreground, formatValue = { editValueLabel(parameter, it) },
            onValueChange = { change(token, parameter, it) }, onValueChangeFinished = { apply(token) },
            valueColor = if (value != displayed) CaptureDesign.Amber else CaptureDesign.Text)
    }
}
