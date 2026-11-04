package dev.cinematic.p0.rawcapture

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

internal enum class AutoRenderQuality { FINAL_ORIGINAL, PREVIEW, MANUAL_BOUNDED_1024_PRELIMINARY }
internal enum class AutoInputProfile { SUPPORTED, UNKNOWN }

/** FINAL dimensions are exact; explicitly bounded manual dimensions are upper bounds, never original size. */
internal data class AutoDevelopRequest(
    val captureId: String,
    val generation: Long,
    val source: SharedOriginalReference,
    val recipe: RenderRecipeSnapshot,
    val rendererContract: String,
    val width: Int,
    val height: Int,
    val quality: AutoRenderQuality,
    val face: RenderedFaceState,
    val exposure: RenderedExposureState,
    val profile: AutoInputProfile,
) {
    init {
        requireCaptureId(captureId)
        require(generation > 0 && rendererContract.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        JpegOutputDescription(width, height, 1, "sRGB", 1) // Shared bounded dimension contract, not an output receipt.
    }
    val publishable get() = quality == AutoRenderQuality.FINAL_ORIGINAL && profile == AutoInputProfile.SUPPORTED &&
        face in setOf(RenderedFaceState.VERIFIED_NONE, RenderedFaceState.VERIFIED_FACES) && exposure == RenderedExposureState.SUPPORTED
    val contract get() = RendererPublicationContract(rendererContract, recipe.schema)
    val boundedManual get() = quality == AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY &&
        rendererContract == "stage-clear-bounded-jpeg-v1" && recipe.schema == "stage-clear-bounded-jpeg-v1" &&
        width == 1024 && height == 1024 && profile == AutoInputProfile.UNKNOWN &&
        face == RenderedFaceState.NOT_RUN && exposure == RenderedExposureState.UNKNOWN
    val admissible get() = publishable || boundedManual
    fun accepts(receipt: AutoRenderReceipt): Boolean = receipt.request == this && admissible &&
        receipt.output.orientation == 1 && receipt.output.color == "sRGB" &&
        receipt.face in setOf(RenderedFaceState.VERIFIED_NONE, RenderedFaceState.VERIFIED_FACES) &&
        receipt.exposure == RenderedExposureState.SUPPORTED &&
        (if (boundedManual) receipt.output.width <= width && receipt.output.height <= height &&
            receipt.output.bytes <= 8L * 1024 * 1024 else receipt.output.width == width && receipt.output.height == height &&
            receipt.face == face && receipt.exposure == exposure)
    fun completed(output: JpegOutputDescription, observedFace: RenderedFaceState = face,
        observedExposure: RenderedExposureState = exposure) = CompletedRender(
        RenderedJpegRequest(captureId, generation, source, recipe, output), rendererContract,
        RenderedResultKind.COMPLETE_STAGE_CLEAR, observedFace, observedExposure,
    )
}

/** Success is only a claim until the owner closes and the independent JPEG inspector agrees. */
internal data class AutoRenderReceipt(val request: AutoDevelopRequest, val output: JpegOutputDescription,
    val face: RenderedFaceState = request.face, val exposure: RenderedExposureState = request.exposure)
internal class AutoRenderUnsupported : IOException("Renderer cannot satisfy this fixed request")

/**
 * Construct without opening resources; acquire inside render, retaining every handle for close retry.
 * Own read-only DNG FD/streams and this attempt's private output only. Never touch MediaStore/preview.
 * Poll shouldStop between stages (including native progress); a blocked kernel need not stop instantly.
 * close returning is resource acknowledgement, not cancellation. Never report PREVIEW as FINAL.
 */
internal interface AutoRenderAttempt : Closeable {
    fun render(shouldStop: () -> Boolean): AutoRenderReceipt
    /** Optional native CancellationSignal bridge. Thread-safe/idempotent; never close or publish here. */
    fun requestStop() {}
}

/** A PhotoRollRenderer/512px Preview is deliberately not an AutoRenderAttempt or a success receipt. */
internal interface RendererAdapter {
    val contract: RendererPublicationContract
    val quality: AutoRenderQuality
    /** Pure metadata/capability query. Never decode, open a source or perform IO here. */
    fun supports(request: AutoDevelopRequest): Boolean
    fun createAttempt(request: AutoDevelopRequest, attemptId: String, io: AutoRenderIo): AutoRenderAttempt
}

internal interface AutoReadOnlyDescriptor : Closeable { val fd: Int }
internal interface AutoRenderIo {
    fun openOriginal(): InputStream
    fun openOriginalDescriptor(): AutoReadOnlyDescriptor
    fun openOutput(): OutputStream
    /** Same private attempt after successful writer close, never a preview/source/public URI. */
    fun openOutputRead(): InputStream = throw IOException("Attempt readback unavailable")
}

/** Enrolment is explicit. No production adapters are registered by default. */
internal class AutoRendererCatalog(adapters: List<RendererAdapter> = emptyList()) {
    private val adapters = adapters.toList().also { require(it.map { adapter -> adapter.contract }.distinct().size == it.size) }
    fun find(request: AutoDevelopRequest): RendererAdapter? = adapters.singleOrNull {
        it.contract == request.contract && it.quality == request.quality && request.admissible && it.supports(request)
    }
}
