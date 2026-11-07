package dev.cinematic.p0

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared paint only; callers retain click, selection semantics and disabled behavior. */
internal fun Modifier.captureControlSurface(selected: Boolean, decorationAlpha: Float = 1f): Modifier =
    background(if (selected) CaptureDesign.Selected else Color.Transparent).drawWithContent {
        drawContent()
        if (selected) drawRect(CaptureDesign.Text.copy(alpha = decorationAlpha), size = Size(size.width, 3.dp.toPx()))
        drawRect(Color.White.copy(alpha = .1f * decorationAlpha), topLeft = Offset(size.width - 1.dp.toPx(), 0f),
            size = Size(1.dp.toPx(), size.height))
    }

/** Shared switch frame and animated thumb, without imposing toggle or direct-selection input. */
@Composable
internal fun CaptureModeSwitchSurface(secondSelected: Boolean, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(modifier.width(100.dp).height(48.dp)
        .background(Color(0xFF0D0F10), RoundedCornerShape(6.dp))
        .border(1.dp, Color(0xFF5A6066), RoundedCornerShape(6.dp)).padding(2.dp)) {
        val thumbWidth = maxWidth / 2
        val thumbOffset by animateDpAsState(if (secondSelected) thumbWidth else 0.dp, tween(180), label = "mode-thumb")
        Box(Modifier.offset(x = thumbOffset).width(thumbWidth).fillMaxHeight()
            .background(CaptureDesign.Inverted, RoundedCornerShape(4.dp)))
        Row(Modifier.fillMaxSize(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, content = content)
    }
}
