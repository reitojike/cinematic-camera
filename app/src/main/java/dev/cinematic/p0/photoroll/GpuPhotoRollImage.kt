package dev.cinematic.p0.photoroll

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.gpu.preview.GpuPreviewSurface
import dev.cinematic.p0.photoedit.PhotoEditValues

/** Matches the existing stable-caption/Fit image region. AS_SHOT is already physically oriented. */
@Composable internal fun GpuPhotoRollImage(session: GpuPhotoRollSession, values: PhotoEditValues,
    comparing: Boolean, enabled: Boolean, onComparisonChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var holding by remember(session, enabled) { mutableStateOf(false) }
    val before = enabled && (holding || comparing)
    val caption = if (before) "撮影時WBのRAW基準表示（R7・ルック・手動調整なし、暫定色）"
        else "現像結果 · 長押しで基準表示"
    SideEffect { session.submit(values, before) }
    Column(modifier.background(CaptureDesign.Ink)) {
        Text(caption, Modifier.fillMaxWidth().background(CaptureDesign.Ink.copy(alpha = .85f)).padding(8.dp),
            color = CaptureDesign.Muted, style = CaptureDesign.Explanation, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            GpuPreviewSurface(session.renderer, Modifier.fillMaxSize())
            // SurfaceView is below Compose; the same press/release and accessibility operations
            // remain in the Compose hit-test tree above it.
            Box(Modifier.fillMaxSize().pointerInput(session, enabled) {
                if (enabled) detectTapGestures(onLongPress = { holding = true }, onPress = {
                    try { tryAwaitRelease() } finally { holding = false }
                })
            }.semantics {
                contentDescription = "現像結果（表示用）"
                stateDescription = caption
                if (enabled) customActions = listOf(CustomAccessibilityAction(
                    if (before) "現像結果へ戻す" else "RAWの基準表示に切り替える") {
                    onComparisonChange(!before); true
                })
            })
        }
    }
}
