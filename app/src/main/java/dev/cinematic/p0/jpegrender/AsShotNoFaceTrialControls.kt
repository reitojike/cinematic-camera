package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.rawcapture.RenderRecipeSnapshot
import dev.cinematic.p0.rawcapture.RenderedExposureState
import dev.cinematic.p0.rawcapture.RenderedFaceState

/** Explicit private-trial policy. Empty masks mean protection is disabled, never detected absence. */
internal object AsShotNoFaceTrialControls : JpegControlsProvider {
    override val contract = "as-shot-p97-no-face-trial-v1"
    val POLICY = RenderRecipeSnapshot(contract,
        "ev=PC_CENTRAL_P97_DIAGNOSTIC;wb=AS_SHOT_BEFORE_W1;roi=none-explicit;face=NOT_RUN;mask=protection-disabled")

    override fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean): JpegControls {
        require(policy == POLICY) { "Unknown private-trial policy" }
        val diagnostic = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(image,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList(), shouldStop)
        return JpegControls(policy, diagnostic.exposure.externalBaseEv, diagnostic.foundationSkinRoi,
            FaceMask(image.width, image.height, FloatArray(image.pixelCount)),
            RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC)
    }
}
