package dev.cinematic.p0

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ChromeText = Color(0xFFE4E6E8)
private fun CaptureChromeTone.color(): Color = when (this) {
    CaptureChromeTone.NORMAL -> Color(0xFFA3AAB1)
    CaptureChromeTone.WARNING -> Color(0xFFE6A35C)
    CaptureChromeTone.FAILURE -> Color(0xFFEF8A74)
    CaptureChromeTone.RECORDING -> Color(0xFFFF7A6E)
    CaptureChromeTone.ACCEPTED -> Color(0xFF7CC4A5)
}
private fun ShutterColor.color(): Color = when (this) {
    ShutterColor.CONTROL -> Color(0xFFCFD3D6)
    ShutterColor.RECORDING -> Color(0xFFE5483B)
    ShutterColor.INACTIVE -> Color(0xFF4A5056)
    ShutterColor.EXPOSING -> Color(0xFF8D9397)
}

/** Fixed rail and button centre. Progress is shown in the status bar, never below the button. */
@Composable
fun CaptureShutter(input: CaptureChromeInput, onClick: () -> Unit, modifier: Modifier = Modifier, rotation: Float = 0f) {
    val model = captureShutterModel(input)
    Box(modifier.width(76.dp).height(96.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(76.dp).clip(CircleShape).alpha(model.alpha)
            .border(3.dp, model.ring.color(), CircleShape)
            .clickable(enabled = model.enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = model.actionDescription }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(model.innerDp.dp).background(model.inner.color(), if (model.square) RoundedCornerShape(4.dp) else CircleShape))
        }
    }
}
/** Scrollable chip content leaves the existing 44dp settings action permanently reachable. */
@Composable
fun CaptureStatusBar(
    input: CaptureChromeInput,
    settingsEnabled: Boolean,
    settingsSelected: Boolean,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    horizontal: Boolean = false,
) {
    Row(modifier.fillMaxWidth().height(44.dp).background(Color(0xFF0A0B0C).copy(alpha = .5f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            captureStatusChips(input).forEach { chip -> StatusChip(chip, rotation, horizontal) }
        }
        TextButton(onClick = onSettings, enabled = settingsEnabled, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(8.dp)) {
            Icon(painterResource(android.R.drawable.ic_menu_preferences), contentDescription = "撮影設定",
                tint = if (settingsSelected) CaptureChromeTone.WARNING.color() else ChromeText,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation })
        }
    }
}

@Composable
private fun StatusChip(chip: CaptureStatusChip, rotation: Float, horizontal: Boolean) {
    val color = if (chip.mode && chip.tone == CaptureChromeTone.NORMAL) ChromeText else chip.tone.color()
    val background = when (chip.tone) {
        CaptureChromeTone.RECORDING -> Color(0xFFE5483B).copy(alpha = .16f)
        CaptureChromeTone.FAILURE -> color.copy(alpha = .12f)
        else -> Color.Transparent
    }
    val border = when {
        chip.mode -> color
        chip.tone == CaptureChromeTone.RECORDING -> Color(0xFFE5483B)
        chip.tone == CaptureChromeTone.NORMAL -> Color(0xFF2C3034)
        else -> color
    }
    // Measure and rotate the complete badge, including its border and padding, around one centre.
    Box(Modifier.semantics { contentDescription = chip.detail }
        .layout { measurable, constraints ->
            val badge = measurable.measure(controlScaleTextConstraints(constraints, horizontal))
            val placement = controlScaleTextPlacement(androidx.compose.ui.unit.IntSize(badge.width, badge.height), horizontal)
            layout(placement.size.width, placement.size.height) { badge.place(placement.offset.x, placement.offset.y) }
        }.graphicsLayer { rotationZ = rotation }
        .background(background).border(1.dp, border).padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center) {
        Text(if (horizontal) chip.compact else chip.text,
            color = color, fontSize = (if (horizontal) 11 else if (chip.mode) 14 else 12).sp,
            fontFamily = FontFamily.Monospace, fontWeight = if (chip.mode) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = if (horizontal && chip.compact.contains('\n')) 2 else 1,
            softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
