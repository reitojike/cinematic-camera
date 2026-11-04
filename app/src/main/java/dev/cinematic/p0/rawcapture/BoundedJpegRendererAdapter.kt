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
) : RendererAdapter {
    override val contract = JpegGenerationRecipe.PUBLICATION_CONTRACT
    override val quality = AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY
    override fun supports(request: AutoDevelopRequest) = request.boundedManual && request.recipe == recipe.snapshot &&
        controls.contract == recipe.controls.schema
    override fun createAttempt(request: AutoDevelopRequest, attemptId: String, io: AutoRenderIo): AutoRenderAttempt {
        if (!supports(request)) throw AutoRenderUnsupported()
        // Factory must construct without opening resources, as with the existing adapter contract.
        val frameOwner = frames(request, io)
        val generation = JpegGenerationAttempt(JpegGenerationRequest(request.captureId, request.generation, request.source, recipe),
            object : PrivateJpegTarget {
                override val captureId = request.captureId
                override val generation = request.generation
                override fun createOutput() = io.openOutput()
                override fun openRead() = io.openOutputRead()
            }, frameOwner, controls, { reference ->
                if (reference != request.source) throw CaptureJournalConflict()
                io.openOriginal()
            }, encoder, inspector)
        return object : AutoRenderAttempt {
            override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                val result = generation.render(shouldStop)
                val render = result.render
                if (render.request.source != request.source || render.request.recipe != request.recipe ||
                    render.request.captureId != request.captureId || render.request.generation != request.generation ||
                    render.rendererContract != request.rendererContract) throw IOException("Foreign generated JPEG")
                return AutoRenderReceipt(request, render.request.output, render.face, render.exposure)
            }
            override fun requestStop() = frameOwner.requestStop()
            override fun close() {
                generation.releaseResources()
                frameOwner.close() // Only actual acknowledgement permits the backend/coordinator to release.
            }
        }
    }
}
