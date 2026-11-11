package dev.cinematic.p0.gpu.preview

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
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
    // SurfaceHolder callbacks are owned by this View/renderer pair. In Compose 1.12.1,
    // AndroidExternalSurface's reusable AndroidView installs callbacks only in its factory;
    // renderer replacement can reuse an already-created Surface without notifying the new owner.
    // The standard AndroidView non-reuse path gives each renderer a fresh SurfaceView lifetime.
    // https://developer.android.com/reference/kotlin/androidx/compose/ui/viewinterop/package-summary
    key(renderer) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                SurfaceView(context).apply {
                    holder.setFormat(PixelFormat.OPAQUE)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        private var attached: Surface? = null
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            GpuSurfaceProbe.event("compose.surface.created") {
                                "renderer=${System.identityHashCode(renderer)} surface=${System.identityHashCode(holder.surface)}"
                            }
                            val frame = holder.surfaceFrame
                            attachOrResize(holder.surface, frame.width(), frame.height())
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            attachOrResize(holder.surface, width, height)
                        }
                        private fun attachOrResize(surface: Surface, width: Int, height: Int) {
                            if (width <= 0 || height <= 0) return
                            if (attached === surface) renderer.resizeSurface(surface, width, height)
                            else {
                                attached = surface
                                renderer.attachSurface(surface, width, height, refreshRate)
                            }
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            val surface = attached ?: return
                            attached = null
                            GpuSurfaceProbe.event("compose.surface.destroyed.begin") {
                                "renderer=${System.identityHashCode(renderer)} surface=${System.identityHashCode(surface)}"
                            }
                            // Timeout remains a visible GPU error. Never throw from Android's
                            // destruction callback or silently clear an incomplete cleanup barrier.
                            val completed = renderer.detachSurface(surface)
                            GpuSurfaceProbe.event("compose.surface.destroyed.end") {
                                "renderer=${System.identityHashCode(renderer)} surface=${System.identityHashCode(surface)} completed=$completed"
                            }
                        }
                    })
                }
            },
            onReset = null,
        )
    }
}
