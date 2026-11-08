package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import dev.cinematic.p0.CaptureDesign
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
    unapplied: Boolean = false, fillImageWidth: Boolean = false, stableCaption: Boolean = false) {
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
    val caption = @Composable {
        Text(if (showBefore) beforeDescription!! else afterCaption,
            Modifier.fillMaxWidth().background(CaptureDesign.Ink.copy(alpha = .85f)).padding(8.dp),
            color = if (unapplied && !showBefore) CaptureDesign.Amber else CaptureDesign.Muted,
            style = CaptureDesign.Explanation, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
    if (fillImageWidth || stableCaption) {
        Column(modifier.background(CaptureDesign.Ink)) {
            caption()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (fillImageWidth) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center) {
                    Image((if (showBefore) before!! else after).asImageBitmap(),
                        if (showBefore) beforeDescription else afterDescription,
                        Modifier.fillMaxWidth().aspectRatio(after.width.toFloat() / after.height).then(comparison),
                        contentScale = ContentScale.FillWidth)
                } else Image((if (showBefore) before!! else after).asImageBitmap(),
                    if (showBefore) beforeDescription else afterDescription,
                    Modifier.fillMaxSize().then(comparison), contentScale = ContentScale.Fit)
            }
        }
    } else Column(modifier.background(CaptureDesign.Ink)) {
        // Existing standalone comparison keeps its FIT presentation.
        Box(Modifier.fillMaxWidth().padding(8.dp)) {
            listOfNotNull(beforeDescription, afterCaption).forEach { value ->
                Text(value, Modifier.alpha(0f).clearAndSetSemantics { }, style = CaptureDesign.Explanation)
            }
            Text(if (showBefore) beforeDescription!! else afterCaption,
                color = if (unapplied && !showBefore) CaptureDesign.Amber else CaptureDesign.Muted, style = CaptureDesign.Explanation)
        }
        Image((if (showBefore) before!! else after).asImageBitmap(),
            if (showBefore) beforeDescription else afterDescription,
            Modifier.fillMaxWidth().weight(1f).then(comparison), contentScale = ContentScale.Fit)
    }
}
