package dev.cinematic.p0.photoroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.captureRulerDescription
import dev.cinematic.p0.photoedit.*
import java.util.Locale

internal fun trialStrengthLabel(value: Double) = String.format(Locale.ROOT, "%.0f", value * 100)
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
    val temperatureTitle = "色温度 · 相対（K値ではありません）"
    val title = when (parameter) { PhotoEditParameter.STRENGTH -> "強さ"
        PhotoEditParameter.TEMPERATURE -> temperatureTitle; PhotoEditParameter.BRIGHTNESS -> "明るさ · EV" }
    Column(modifier.fillMaxWidth().heightIn(min = 104.dp).background(CaptureDesign.Ruler).border(1.dp, CaptureDesign.Line)) {
        // Unlike the short capture keys, this title contains the only temperature qualification.
        // Give it the remaining width and wrapping; do not shrink away enlarged-text accessibility.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val titleWidth = with(density) { (maxWidth - 116.dp).coerceAtLeast(1.dp).roundToPx() }
            // Reserve the longest title's measured height for every parameter, not a hidden note row.
            val titleHeight = measurer.measure(temperatureTitle, style = CaptureDesign.Label,
                constraints = Constraints(maxWidth = titleWidth)).size.height
            val headingHeight = with(density) { titleHeight.toDp() }.coerceAtLeast(48.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = headingHeight), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { reset(token) }, enabled = foreground, shape = RectangleShape,
                    modifier = Modifier.width(72.dp), contentPadding = PaddingValues(4.dp)) {
                    Text("RESET", style = CaptureDesign.Label)
                }
                Text(title, Modifier.weight(1f).semantics { contentDescription = captureRulerDescription(title, false) },
                    style = CaptureDesign.Label, color = CaptureDesign.Text, textAlign = TextAlign.Center)
                TextButton(onClick = { close(token) },
                    enabled = foreground && !state.hasUnappliedChanges && phase == PhotoRollPhase.DISPLAYED &&
                        state.displayedValues == state.requestedValues,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = "目盛りを閉じる" },
                    contentPadding = PaddingValues(0.dp), shape = RectangleShape) {
                    Text("×", style = CaptureDesign.Title)
                }
            }
        }
        PhotoEditBar(title, value,
            valueNow = { readState().session?.draft?.let { when (parameter) { PhotoEditParameter.STRENGTH -> it.strength
                PhotoEditParameter.TEMPERATURE -> it.temperature; PhotoEditParameter.BRIGHTNESS -> it.brightness } } ?: Double.NaN },
            scale = scale, interactionKey = token to parameter, enabled = foreground, formatValue = { editValueLabel(parameter, it) },
            onValueChange = { change(token, parameter, it) }, onValueChangeFinished = { apply(token) },
            baseline = if (parameter == PhotoEditParameter.STRENGTH) .5 else 0.0,
            quantization = if (parameter == PhotoEditParameter.STRENGTH) .01 else null,
            valueColor = if (value != displayed) CaptureDesign.Amber else CaptureDesign.Text)
    }
}
