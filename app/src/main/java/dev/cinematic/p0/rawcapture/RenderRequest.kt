package dev.cinematic.p0.rawcapture

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

internal enum class AutoRenderQuality { FINAL_ORIGINAL, PREVIEW, MANUAL_BOUNDED_1024_PRELIMINARY, PRIVATE_TRIAL_1024, FINAL_ORIGINAL_STRICT }
internal enum class AutoInputProfile { SUPPORTED, UNKNOWN }

/** Legacy FINAL has fixed dimensions; strict original uses 0/0 = not yet observed, never a fake size. */
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
    val originalDeclaration: OriginalRenderDeclaration? = null,
) {
    init {
        requireCaptureId(captureId)
        require(generation > 0 && rendererContract.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        if (quality == AutoRenderQuality.FINAL_ORIGINAL_STRICT) {
            require(originalDeclaration != null && width == 0 && height == 0)
            require(face == RenderedFaceState.NOT_RUN && exposure == RenderedExposureState.UNKNOWN && profile == AutoInputProfile.UNKNOWN)
        } else {
            require(originalDeclaration == null)
            JpegOutputDescription(width, height, 1, "sRGB", 1)
        }
    }
    /** Admits an attempt, not a completed result. Legacy FINAL is readable but cannot be enrolled. */
    val publishable get() = quality == AutoRenderQuality.FINAL_ORIGINAL_STRICT
    val contract get() = RendererPublicationContract(rendererContract, recipe.schema, originalDeclaration)
    val boundedManual get() = quality == AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY &&
        rendererContract == "stage-clear-bounded-jpeg-v1" && recipe.schema == "stage-clear-bounded-jpeg-v1" &&
        width == 1024 && height == 1024 && profile == AutoInputProfile.UNKNOWN &&
        face == RenderedFaceState.NOT_RUN && exposure == RenderedExposureState.UNKNOWN
    val boundedTrial get() = quality == AutoRenderQuality.PRIVATE_TRIAL_1024 &&
        rendererContract == "stage-clear-private-no-face-trial-v1" && recipe.schema == rendererContract &&
        width == 1024 && height == 1024 && profile == AutoInputProfile.UNKNOWN &&
        face == RenderedFaceState.NOT_RUN && exposure == RenderedExposureState.DIAGNOSTIC
    val interactive get() = boundedManual || boundedTrial
    val admissible get() = publishable || interactive
    fun accepts(receipt: AutoRenderReceipt): Boolean {
        if (receipt.request != this || receipt.output.orientation != 1 || receipt.output.color != "sRGB") return false
        if (publishable) return receipt.original?.matches(requireNotNull(originalDeclaration),
            RenderedJpegRequest(captureId, generation, source, recipe, receipt.output), rendererContract, receipt.face, receipt.exposure) == true
        // Validate old records for safe reading only; admissible excludes this legacy quality.
        if (quality == AutoRenderQuality.FINAL_ORIGINAL) return profile == AutoInputProfile.SUPPORTED &&
            face in setOf(RenderedFaceState.VERIFIED_NONE, RenderedFaceState.VERIFIED_FACES) && exposure == RenderedExposureState.SUPPORTED &&
            receipt.original == null && receipt.output.width == width && receipt.output.height == height &&
            receipt.face == face && receipt.exposure == exposure
        return admissible && receipt.original == null &&
        (if (boundedTrial) receipt.face == RenderedFaceState.NOT_RUN && receipt.exposure == RenderedExposureState.DIAGNOSTIC
        else receipt.face in setOf(RenderedFaceState.VERIFIED_NONE, RenderedFaceState.VERIFIED_FACES) &&
            receipt.exposure == RenderedExposureState.SUPPORTED) &&
        receipt.output.width <= width && receipt.output.height <= height && receipt.output.bytes <= 8L * 1024 * 1024
    }
    fun completed(output: JpegOutputDescription, observedFace: RenderedFaceState = face,
        observedExposure: RenderedExposureState = exposure, original: OriginalRenderCompletion? = null): CompletedRender {
        check(quality != AutoRenderQuality.PRIVATE_TRIAL_1024) { "Private trial cannot become CompletedRender" }
        if (quality == AutoRenderQuality.FINAL_ORIGINAL) throw IOException("Legacy original has no strict completion evidence")
        val request = RenderedJpegRequest(captureId, generation, source, recipe, output)
        if (publishable && (original?.declaration != originalDeclaration ||
            original?.matches(request, rendererContract, observedFace, observedExposure) != true)) throw IOException("Original completion evidence missing or invalid")
        if (!publishable && original != null) throw IOException("Original evidence cannot upgrade an interactive result")
        return CompletedRender(
        request, rendererContract,
        if (publishable) RenderedResultKind.COMPLETE_ORIGINAL else RenderedResultKind.COMPLETE_STAGE_CLEAR, observedFace, observedExposure, original,
        )
    }
}

/** Success is only a claim until the owner closes and the independent JPEG inspector agrees. */
internal data class AutoRenderReceipt(val request: AutoDevelopRequest, val output: JpegOutputDescription,
    val face: RenderedFaceState = request.face, val exposure: RenderedExposureState = request.exposure,
    val original: OriginalRenderObservation? = null)
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
