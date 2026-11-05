package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.*
import java.io.Closeable
import java.io.IOException

/** A resource-owning frame source is mandatory. AndroidAsShotJpegFrames alone does not acknowledge
 * failed native FD closes, so it cannot be silently wrapped as a successfully closed source here. */
internal interface OwnedJpegFrameSource : JpegFrameSource, Closeable {
    fun requestStop() {}
}

/** Connects #127 to the existing attempt IO/publisher owner. Explicit recipe and resource factories;
 * no default face/exposure values, no preview bitmap, no production enrollment. */
internal class BoundedJpegRendererAdapter(
    private val recipe: JpegGenerationRecipe,
    private val frames: (AutoDevelopRequest, AutoRenderIo) -> OwnedJpegFrameSource,
    private val controls: JpegControlsProvider,
    private val encoder: LinearJpegEncoder,
    private val inspector: JpegInspector,
    private val reuse: (AutoDevelopRequest) -> TrialFoundationReuse? = { null },
) : RendererAdapter {
    override val contract = if (recipe.isTrial) RendererPublicationContract(JpegGenerationRecipe.TRIAL_SCHEMA, JpegGenerationRecipe.TRIAL_SCHEMA)
        else JpegGenerationRecipe.PUBLICATION_CONTRACT
    override val quality = if (recipe.isTrial) AutoRenderQuality.PRIVATE_TRIAL_1024 else AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY
    private fun recipeFor(request: AutoDevelopRequest): JpegGenerationRecipe? {
        val selected = if (recipe.isTrial) {
            if (!request.boundedTrial) return null
            JpegGenerationRecipe.fromTrialSnapshot(request.recipe) ?: return null
        } else {
            if (!request.boundedManual || request.recipe != recipe.snapshot) return null
            recipe
        }
        return selected.takeIf { controls.contract == it.controls.schema }
    }
    override fun supports(request: AutoDevelopRequest) = recipeFor(request) != null
    override fun createAttempt(request: AutoDevelopRequest, attemptId: String, io: AutoRenderIo): AutoRenderAttempt {
        // Freeze this request's validated strength; another request cannot replace an attempt's recipe.
        val selectedRecipe = recipeFor(request) ?: throw AutoRenderUnsupported()
        val trialReuse = if (selectedRecipe.isTrial) reuse(request) else null
        // Factory must construct without opening resources, as with the existing adapter contract.
        val frameOwner = frames(request, io)
        val generation = JpegGenerationAttempt(JpegGenerationRequest(request.captureId, request.generation, request.source, selectedRecipe),
            object : PrivateJpegTarget {
                override val captureId = request.captureId
                override val generation = request.generation
                override fun createOutput() = io.openOutput()
                override fun openRead() = io.openOutputRead()
            }, frameOwner, controls, { reference ->
                if (reference != request.source) throw CaptureJournalConflict()
                io.openOriginal()
            }, encoder, inspector, trialReuse)
        return object : AutoRenderAttempt {
            override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                if (selectedRecipe.isTrial) {
                    val result = generation.renderTrial(shouldStop)
                    if (result.request.captureId != request.captureId || result.request.generation != request.generation ||
                        result.request.source != request.source || result.request.recipe != request.recipe) throw IOException("Foreign private trial JPEG")
                    return AutoRenderReceipt(request, result.request.output, result.face, result.exposure)
                }
                val result = generation.render(shouldStop)
                val render = result.render
                if (render.request.source != request.source || render.request.recipe != request.recipe ||
                    render.request.captureId != request.captureId || render.request.generation != request.generation ||
                    render.rendererContract != request.rendererContract) throw IOException("Foreign generated JPEG")
                return AutoRenderReceipt(request, render.request.output, render.face, render.exposure)
            }
            override fun requestStop() = frameOwner.requestStop()
            override fun close() {
                try {
                    generation.releaseResources()
                    frameOwner.close() // Only actual acknowledgement permits the backend/coordinator to release.
                } catch (failure: Throwable) { trialReuse?.invalidate(); throw failure }
            }
        }
    }
    companion object {
        fun privateTrial(reuse: (AutoDevelopRequest) -> TrialFoundationReuse? = { null }): BoundedJpegRendererAdapter = BoundedJpegRendererAdapter(JpegGenerationRecipe.trial(),
            { request, io -> AndroidOwnedJpegFrames(io, request.source) }, AsShotNoFaceTrialControls,
            AndroidSrgbJpegEncoder, AndroidJpegInspector(), reuse)
    }
}
