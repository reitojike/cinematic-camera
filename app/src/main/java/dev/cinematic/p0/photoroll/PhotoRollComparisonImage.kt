package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
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
    afterDescription: String, beforeDescription: String?, modifier: Modifier = Modifier) {
    var comparing by remember(after, before, foreground) { mutableStateOf(false) }
    DisposableEffect(after, before, foreground) { onDispose { comparing = false } }
    val comparison = if (before != null && beforeDescription != null && foreground) Modifier
        .pointerInput(after, before, foreground) {
            detectTapGestures(onLongPress = { comparing = true }, onPress = {
                try { tryAwaitRelease() } finally { comparing = false }
            })
        }.semantics {
            stateDescription = if (comparing) beforeDescription else afterDescription
            customActions = listOf(CustomAccessibilityAction(
                if (comparing) "現像結果へ戻す" else "RAWの基準表示に切り替える") { comparing = !comparing; true })
        } else Modifier
    Column(modifier) {
        val showBefore = comparing && before != null && beforeDescription != null && foreground
        Image((if (showBefore) before!! else after).asImageBitmap(),
            if (showBefore) beforeDescription else afterDescription,
            Modifier.fillMaxWidth().weight(1f).then(comparison), contentScale = ContentScale.Fit)
        val afterCaption = if (before != null) "現像結果 · 長押し中はRAWの基準表示"
            else "現像結果 · 比較用の基準表示は利用できません"
        // Reserve the larger caption at the current width/font scale in both states. A long
        // Before label must never resize the FIT image frame while the finger is held down.
        Box(Modifier.fillMaxWidth()) {
            for (caption in listOfNotNull(beforeDescription, afterCaption)) Text(caption,
                Modifier.alpha(0f).clearAndSetSemantics { }, style = MaterialTheme.typography.bodySmall)
            Text(if (showBefore) beforeDescription!! else afterCaption, style = MaterialTheme.typography.bodySmall)
        }
    }
}
