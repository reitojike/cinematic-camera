package dev.cinematic.p0

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** Shared capture/editor ruler decoration. Input stays with each caller. */
@Composable
internal fun ControlRulerTicks(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val spacing = 9.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(CaptureDesign.Border, Offset(x, 0f), Offset(x, 6.dp.toPx()), 1.dp.toPx())
            x += spacing
        }
    }
}
