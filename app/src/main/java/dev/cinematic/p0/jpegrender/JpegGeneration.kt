package dev.cinematic.p0.jpegrender

import dev.cinematic.p0.raw.*
import dev.cinematic.p0.rawcapture.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/** Explicit bounded export recipe, never a PhotoRoll Preview/Bitmap. No production provider is enrolled. */
internal class JpegGenerationRecipe(
    val inputContract: String,
    val controls: RenderRecipeSnapshot,
    val strength: Double = .5,
    val quality: Int = 95,
) {
    val snapshot: RenderRecipeSnapshot
    init {
        require(inputContract.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(strength.isFinite() && strength in 0.0..1.0 && quality in 1..100)
        require(controls.payload.toByteArray(Charsets.UTF_8).size <= 512)
        val policy = Base64.getUrlEncoder().withoutPadding().encodeToString(controls.payload.toByteArray(Charsets.UTF_8))
        snapshot = RenderRecipeSnapshot(SCHEMA, "input=$inputContract;controls=${controls.schema};policy=$policy;edge=$MAX_EDGE;" +
            "strength=${java.lang.Double.toHexString(strength)};quality=$quality;color=sRGB-SDR;pixelProfile=preliminary")
    }
    companion object {
        const val SCHEMA = "stage-clear-bounded-jpeg-v1"
        const val RENDERER = "stage-clear-bounded-jpeg-v1"
        const val MAX_EDGE = 1024
        const val MAX_BYTES = 8L * 1024 * 1024
        val PUBLICATION_CONTRACT = RendererPublicationContract(RENDERER, SCHEMA)
    }
}

internal data class JpegGenerationRequest(
    val captureId: String, val generation: Long, val source: SharedOriginalReference, val recipe: JpegGenerationRecipe,
) {
    init { requireCaptureId(captureId); require(generation > 0) }
}

/** Caller owns one private attempt. No arbitrary output path, original write, public URI or delete API. */
internal interface PrivateJpegTarget {
    val captureId: String
    val generation: Long
    fun createOutput(): OutputStream
    fun openRead(): InputStream
}

internal data class JpegFrame(val image: LinearSrgbImage, val sourceWidth: Int, val sourceHeight: Int, val physicallyOriented: Boolean)
internal interface JpegFrameSource {
    val contract: String
    /** Return only after decoder-owned resources have closed. Failure must not return a frame. */
    fun load(source: SharedOriginalReference, longEdge: Int, shouldStop: () -> Boolean): JpegFrame
}

internal data class JpegControls(
    val policy: RenderRecipeSnapshot,
    val externalEv: Double,
    val foundationRoi: FloatPlane,
    val faces: FaceMask,
    val faceState: RenderedFaceState,
    val exposureState: RenderedExposureState,
) {
    fun validate(image: LinearSrgbImage, expectedPolicy: RenderRecipeSnapshot) {
        if (policy != expectedPolicy || !externalEv.isFinite() || exposureState != RenderedExposureState.SUPPORTED ||
            faceState !in setOf(RenderedFaceState.VERIFIED_FACES, RenderedFaceState.VERIFIED_NONE)) {
            throw IOException("JPEG requires the fixed policy, verified face inputs and supported exposure")
        }
        faces.requireAligned(image)
        require(foundationRoi.width == image.width && foundationRoi.height == image.height)
        var hasFace = false
        repeat(image.pixelCount) { p ->
            require(foundationRoi[p] in 0f..1f)
            if (faces[p] > .5f) hasFace = true
            if (faceState == RenderedFaceState.VERIFIED_NONE) require(faces[p] == 0f && foundationRoi[p] == 0f)
        }
        require(faceState != RenderedFaceState.VERIFIED_FACES || hasFace) { "Verified faces need an aligned protection mask" }
    }
}

internal interface JpegControlsProvider {
    val contract: String
    /** Use the frozen policy, never mutable UI defaults; close provider resources before returning. */
    fun prepare(image: LinearSrgbImage, policy: RenderRecipeSnapshot, shouldStop: () -> Boolean): JpegControls
}

internal fun interface LinearJpegEncoder {
    /** Synchronous writes only. Caller closes output; false/exception never means completed. */
    fun encode(image: LinearSrgbImage, quality: Int, output: OutputStream, shouldStop: () -> Boolean): Boolean
}

/** Completed bytes at the recorded bounded resolution; neither full-resolution nor Pixel quality acceptance. */
internal data class GeneratedJpeg(val render: CompletedRender, val jpegFingerprint: OriginalFingerprint)
