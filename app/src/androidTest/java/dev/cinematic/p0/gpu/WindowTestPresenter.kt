package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.preview.GpuFramePresenter

internal fun windowTestPresenter(
    egl: EglContexts,
    ledger: GpuMemoryLedger,
): (GpuPreviewTarget, GpuDisplayOptions) -> GpuPreviewPresenter =
    { target, display -> GpuFramePresenter(egl, ledger, target, display) }
