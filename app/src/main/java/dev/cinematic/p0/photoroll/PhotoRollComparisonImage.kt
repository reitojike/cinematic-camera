package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import dev.cinematic.p0.CaptureDesign
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Both images are independent UI-owned, physically oriented, same-size software sRGB copies. */
@Composable
internal fun PhotoRollComparisonImage(after: Bitmap, before: Bitmap?, foreground: Boolean,
    afterDescription: String, beforeDescription: String?, modifier: Modifier = Modifier,
    comparisonRequested: Boolean = false, onComparisonChange: ((Boolean) -> Unit)? = null,
    unapplied: Boolean = false) {
    var comparing by remember(after, before, foreground) { mutableStateOf(false) }
    DisposableEffect(after, before, foreground) { onDispose { comparing = false } }
    val showBefore = (comparing || comparisonRequested) && before != null && beforeDescription != null && foreground
    val comparison = if (before != null && beforeDescription != null && foreground) Modifier
        .pointerInput(after, before, foreground) {
            detectTapGestures(onLongPress = { comparing = true }, onPress = {
                try { tryAwaitRelease() } finally { comparing = false }
            })
        }.semantics {
            stateDescription = if (showBefore) beforeDescription else afterDescription
            customActions = listOf(CustomAccessibilityAction(
                if (showBefore) "現像結果へ戻す" else "RAWの基準表示に切り替える") { if (onComparisonChange != null) onComparisonChange(!showBefore) else comparing = !comparing; true })
        } else Modifier
    val afterCaption = when {
        unapplied -> "表示中 · 未反映の調整あり"
        before != null -> "現像結果 · 長押しで基準表示"
        else -> "現像結果 · 基準表示は利用できません"
    }
    Column(modifier.background(CaptureDesign.Ink)) {
        // Reserve both captions above the FIT image so comparison never changes its frame.
        Box(Modifier.fillMaxWidth().padding(8.dp)) {
            listOfNotNull(beforeDescription, afterCaption).forEach { caption ->
                Text(caption, Modifier.alpha(0f).clearAndSetSemantics { }, style = CaptureDesign.Explanation)
            }
            Text(if (showBefore) beforeDescription!! else afterCaption,
                color = if (unapplied && !showBefore) CaptureDesign.Amber else CaptureDesign.Muted, style = CaptureDesign.Explanation)
        }
        Image((if (showBefore) before!! else after).asImageBitmap(),
            if (showBefore) beforeDescription else afterDescription,
            Modifier.fillMaxWidth().weight(1f).then(comparison), contentScale = ContentScale.Fit)
    }
}
