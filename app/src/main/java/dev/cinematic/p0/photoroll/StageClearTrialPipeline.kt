package dev.cinematic.p0.photoroll

import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7Base
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.raw.StageClearLook
import java.util.concurrent.CancellationException

/** Diagnostic as-shot input only. Empty masks explicitly disable, and never claim, face protection. */
internal object StageClearTrialPipeline {
    const val MAX_EDGE = 512
    const val LOOK_VERSION = "stage-clear-v1"
    const val STRENGTH = 0.5

    fun render(
        asShot: LinearSrgbImage,
        shouldStop: () -> Boolean,
        onStage: (PhotoRollRenderStage) -> Unit = {},
    ): LinearSrgbImage {
        fun stage(value: PhotoRollRenderStage) {
            if (shouldStop()) throw CancellationException("Stage Clear trial stopped")
            onStage(value)
            if (shouldStop()) throw CancellationException("Stage Clear trial stopped")
        }
        require(asShot.width <= MAX_EDGE && asShot.height <= MAX_EDGE) { "Trial input exceeds 512" }
        stage(PhotoRollRenderStage.INPUTS)
        val controls = R7PreviewInputs.prepare(
            R7PreviewInputs.AsShotInput(asShot, R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT),
            emptyList(), shouldStop,
        )
        stage(PhotoRollRenderStage.BASE)
        val base = R7Base.render(asShot, controls.foundationSkinRoi, controls.exposure.externalBaseEv, shouldStop)
        stage(PhotoRollRenderStage.LOOK)
        val noProtection = FaceMask(base.width, base.height, FloatArray(base.pixelCount))
        val result = StageClearLook.renderFromBase(base, noProtection, STRENGTH.toDouble(), shouldStop).image
        if (shouldStop()) throw CancellationException("Stage Clear trial stopped")
        return result
    }
}
