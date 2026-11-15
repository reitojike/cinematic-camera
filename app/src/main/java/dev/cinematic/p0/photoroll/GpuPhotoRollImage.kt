package dev.cinematic.p0.photoroll

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import dev.cinematic.p0.DevelopCaption
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.gpu.preview.GpuPreviewSurface
import dev.cinematic.p0.photoedit.PhotoEditValues

/** Matches the existing stable-caption/Fit image region. AS_SHOT is already physically oriented. */
@Composable internal fun GpuPhotoRollImage(session: GpuPhotoRollSession, values: PhotoEditValues,
    comparing: Boolean, enabled: Boolean, onComparisonChange: (Boolean) -> Unit, modifier: Modifier = Modifier,
    updating: Boolean = false, initialGuide: InitialPreviewFrame? = null, header: (@Composable (Boolean) -> Unit)? = null) {
    var holding by remember(session, enabled) { mutableStateOf(false) }
    val before = enabled && (holding || comparing)
    val caption = initialGuide?.let { developCaption(false, false).copy(body = it.label) }
        ?: developCaption(before, updating)
    SideEffect { session.submit(values, before) }
    Column(modifier.background(CaptureDesign.Ink)) {
        if (header != null) header(before) else DevelopCaption(caption, Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            var fixedHeight by remember(session, constraints.maxWidth) { mutableIntStateOf(constraints.maxHeight) }
            val surfaceHeight = maxOf(fixedHeight, constraints.maxHeight)
            SideEffect {
                fixedHeight = surfaceHeight
                session.renderer.setPresentationHeight(constraints.maxHeight)
            }
            // Native surface stays large; the hit-test overlay below stays in the visible region.
            Layout(content = { GpuPreviewSurface(session.renderer, Modifier.fillMaxSize()) },
                modifier = Modifier.fillMaxSize()) { measurables, bounds ->
                val surface = measurables.single().measure(Constraints.fixed(bounds.maxWidth, surfaceHeight))
                layout(bounds.maxWidth, bounds.maxHeight) { surface.place(0, 0) }
            }
            initialGuide?.let { guide ->
                Image(guide.bitmap.asImageBitmap(), guide.label,
                    Modifier.fillMaxSize().background(Color.Black), contentScale = ContentScale.Fit)
            }
            // SurfaceView is below Compose; the same press/release and accessibility operations
            // remain in the Compose hit-test tree above it.
            Box(Modifier.fillMaxSize().pointerInput(session, enabled) {
                if (enabled) detectTapGestures(onLongPress = { holding = true }, onPress = {
                    try { tryAwaitRelease() } finally { holding = false }
                })
            }.semantics {
                contentDescription = "現像結果（表示用）"
                stateDescription = caption.body
                if (enabled) customActions = listOf(CustomAccessibilityAction(
                    if (before) "現像結果へ戻す" else "RAWの基準表示に切り替える") {
                    onComparisonChange(!before); true
                })
            })
        }
    }
}
