package dev.cinematic.p0.photoroll

import android.content.res.AssetManager
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoedit.PhotoEditValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** One selected photo owns its GL callbacks. Closed generations can never publish to a new photo. */
internal class GpuPhotoRollSession(assets: AssetManager,
    engineFactory: (EglContexts, GpuMemoryLedger) -> StageClearGpu = { egl, ledger -> DefaultStageClearGpu(egl, ledger) },
) {
    private val closed = AtomicBoolean()
    private val mutableFailure = MutableStateFlow<Throwable?>(null)
    val failure = mutableFailure.asStateFlow()
    private val mutablePresented = MutableStateFlow<PhotoEditValues?>(null)
    val presented = mutablePresented.asStateFlow()
    val renderer = GpuPreviewRenderer(assets,
        onError = { if (!closed.get()) mutableFailure.value = it },
        engineFactory = engineFactory,
        recoverContextOnSubmit = false,
        onPresented = { params, display ->
            if (!closed.get() && !display.showBefore) mutablePresented.value = PhotoEditValues(params.strength,
                params.adjustments.warmth, params.adjustments.exposureEv)
        })

    fun submit(values: PhotoEditValues, before: Boolean = false) {
        if (!closed.get() && mutableFailure.value == null) renderer.submit(
            GpuRenderParams(values.strength, PhotoAdjustmentPolicy.adjustments(values)), GpuDisplayOptions(before, 0))
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            mutablePresented.value = null
            renderer.close()
            GpuPhotoRollRetiredOwners.retain(renderer)
        }
    }
}

/** A lifecycle close is only a request. A later owner must observe its completion before opening.
 * Failed/stalled owners stay retained; replacing the screen must not conceal an ownership failure. */
internal object GpuPhotoRollRetiredOwners {
    private val pending = mutableSetOf<GpuPreviewRenderer>()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized fun retain(renderer: GpuPreviewRenderer) {
        pending += renderer
        cleanup.launch {
            // Success is reclaimed even when no later photo is opened. Failure stays observable
            // at the next explicit retry/open, and is never confused with accepted shutdown.
            if (runCatching { renderer.awaitClosed(2_000) }.getOrDefault(false)) {
                synchronized(this@GpuPhotoRollRetiredOwners) { pending.remove(renderer) }
            }
        }
    }
    fun releaseBeforeOpen() {
        val owners = synchronized(this) { pending.toList() }
        owners.forEach { owner ->
            if (!owner.awaitClosed(2_000)) throw GpuSurfaceTeardownException("Previous GPU preview release is incomplete")
            synchronized(this) { pending.remove(owner) }
        }
    }
}
