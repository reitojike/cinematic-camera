package dev.cinematic.p0.gpu.preview

import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Renderer/photo ownership stays with the screen; this composable owns only the surface lease. */
@Composable
internal fun GpuPreviewSurface(renderer: GpuPreviewRenderer, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    val refreshRate = LocalView.current.display?.refreshRate ?: 60f
    DisposableEffect(renderer, owner) {
        val observer = LifecycleEventObserver { _, event ->
            GpuSurfaceProbe.event("compose.lifecycle") { "event=$event" }
            if (event == Lifecycle.Event.ON_START) renderer.setStarted(true)
            if (event == Lifecycle.Event.ON_STOP) renderer.setStarted(false)
        }
        owner.lifecycle.addObserver(observer)
        renderer.setStarted(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            GpuSurfaceProbe.event("compose.dispose")
            owner.lifecycle.removeObserver(observer); renderer.setStarted(false)
        }
    }
    AndroidExternalSurface(modifier = modifier) {
        onSurface { surface, width, height ->
            GpuSurfaceProbe.event("compose.surface.created") { "surface=${System.identityHashCode(surface)}" }
            renderer.attachSurface(surface, width, height, refreshRate)
            surface.onChanged { w, h -> renderer.resizeSurface(this, w, h) }
            surface.onDestroyed {
                GpuSurfaceProbe.event("compose.surface.destroyed.begin") { "surface=${System.identityHashCode(this)}" }
                renderer.detachSurface(this)
                GpuSurfaceProbe.event("compose.surface.destroyed.end") { "surface=${System.identityHashCode(this)}" }
            }
        }
    }
}
