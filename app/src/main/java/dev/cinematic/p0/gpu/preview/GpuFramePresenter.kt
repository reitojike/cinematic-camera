package dev.cinematic.p0.gpu.preview

import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GpuDisplayOptions
import dev.cinematic.p0.gpu.GpuMemoryLedger
import dev.cinematic.p0.gpu.GpuPreviewTarget
import dev.cinematic.p0.gpu.GpuTexture
import dev.cinematic.p0.gpu.StageClearGpuPreviewTarget

/** Window-only adapter. Borrows a texture synchronously; never retains, copies or closes it. */
internal class GpuFramePresenter(
    private val egl: EglContexts,
    private val ledger: GpuMemoryLedger,
    target: GpuPreviewTarget,
    private val display: GpuDisplayOptions,
) {
    private val destination: StageClearGpuPreviewTarget

    init {
        require(display.rotationDegrees in setOf(0, 90, 180, 270))
        destination = requireNotNull(target as? StageClearGpuPreviewTarget) { "GPU preview needs window dimensions" }
    }

    // Kept outside the submission timer, as before. Window ownership/swap/query stay in renderer.
    fun makeCurrent() = egl.makeCurrent(destination.window)

    fun present(image: GpuTexture) {
        image.requireContext(egl)
        require(image.memoryLedger === ledger) { "GPU frame belongs to another memory ledger" }
        present(egl, image, destination.width, destination.height, display.rotationDegrees,
            linearFiltering = destination.linearFiltering, viewportY = destination.viewportY)
    }
}
