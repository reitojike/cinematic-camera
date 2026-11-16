package dev.cinematic.p0.photoroll

import android.content.res.AssetManager
import android.os.Looper
import dev.cinematic.p0.gpu.preview.GpuPreviewRuntime

internal enum class GpuPreviewExit { PHOTO, BACKGROUND, MEMORY_PRESSURE, ACTIVITY_DESTROYED }

/** Single policy point; revisit context retention only after Pixel measurements. */
internal fun destroyPreviewContext(reason: GpuPreviewExit): Boolean = when (reason) {
    GpuPreviewExit.PHOTO, GpuPreviewExit.BACKGROUND -> false
    GpuPreviewExit.MEMORY_PRESSURE, GpuPreviewExit.ACTIVITY_DESTROYED -> true
}

/** Activity-scoped, main-thread only. Sessions/callbacks never cross photo generations. */
internal class GpuPhotoRollOwner(private val assets: AssetManager) {
    private var runtime: GpuPreviewRuntime? = null
    private var session: GpuPhotoRollSession? = null
    private var destroyed = false
    internal val runtimeForDiagnostics: GpuPreviewRuntime? get() = runtime
    private fun requireMain() = check(Looper.myLooper() === Looper.getMainLooper()) { "Photo roll owner requires UI thread" }

    fun open(): GpuPhotoRollSession {
        requireMain()
        check(!destroyed) { "Photo roll owner is destroyed" }
        // Compose may construct the next session before disposing the previous one.
        session?.retireForReuse()
        val previous = runtime
        if (previous != null) synchronized(previous) {
            // The availability check, reservation and listener registration are atomic with
            // stopAccepting. A GL failure cannot strand a half-constructed UI generation.
            if (previous.reusable)
                return GpuPhotoRollSession(assets, runtime = previous).also { session = it }
        }
        val next = GpuPreviewRuntime(assets, previous).also { runtime = it }
        return synchronized(next) {
            GpuPhotoRollSession(assets, runtime = next).also { session = it }
        }
    }

    fun exit(reason: GpuPreviewExit) {
        requireMain()
        session?.retireForReuse()
        session = null
        if (destroyPreviewContext(reason)) {
            runtime?.let { it.close(); GpuPhotoRollRetiredOwners.retain(it) }
        }
        if (reason == GpuPreviewExit.ACTIVITY_DESTROYED) destroyed = true
    }
}
