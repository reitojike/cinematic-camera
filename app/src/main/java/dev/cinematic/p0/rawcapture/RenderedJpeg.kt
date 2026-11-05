package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.io.InputStream
import java.net.URI

internal enum class OriginalGrant { APP_OWNED, USER_SELECTED }

/** A saved reference is not a grant: the provider must still permit each read. No write capability. */
internal data class SharedOriginalReference(val uri: String, val fingerprint: OriginalFingerprint, val grant: OriginalGrant) {
    init {
        val parsed = try { URI(uri) } catch (e: Exception) { throw IllegalArgumentException("Invalid original URI", e) }
        require(uri.toByteArray(Charsets.UTF_8).size <= 1024 && parsed.scheme == "content" &&
            !parsed.rawAuthority.isNullOrEmpty() && !parsed.rawPath.isNullOrEmpty() && parsed.rawFragment == null &&
            parsed.rawUserInfo == null) { "Invalid shared original reference" }
    }
}

/** Bounded canonical payload belongs to the renderer adapter; unknown schemas are never interpreted here. */
internal data class RenderRecipeSnapshot(val schema: String, val payload: String) {
    init {
        require(schema.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(payload.toByteArray(Charsets.UTF_8).size in 1..1024)
    }
}

internal data class JpegOutputDescription(val width: Int, val height: Int, val orientation: Int, val color: String, val bytes: Long) {
    init {
        require(width in 1..32768 && height in 1..32768 && width.toLong() * height <= 100_000_000)
        require(orientation in 1..8 && color.length in 1..32 && bytes in 1..MAX_JPEG_BYTES)
    }
    companion object { const val MAX_JPEG_BYTES = 32L * 1024 * 1024 }
}

internal data class RenderedJpegRequest(
    val captureId: String,
    val generation: Long,
    val source: SharedOriginalReference,
    val recipe: RenderRecipeSnapshot,
    val output: JpegOutputDescription,
) {
    init {
        requireCaptureId(captureId)
        require(generation > 0)
        require(output.orientation == 1 && output.color == "sRGB") { "Only physically oriented SDR sRGB output is supported" }
    }
}

internal enum class RenderedResultKind { COMPLETE_STAGE_CLEAR, TRIAL_PREVIEW }
internal enum class RenderedFaceState { VERIFIED_FACES, VERIFIED_NONE, NOT_RUN, FAILED }
internal enum class RenderedExposureState { SUPPORTED, DIAGNOSTIC, UNKNOWN }
internal data class RendererPublicationContract(val renderer: String, val recipeSchema: String)

/** Only the renderer integration may report success. No production renderer is enrolled by this PR. */
internal data class CompletedRender(
    val request: RenderedJpegRequest,
    val rendererContract: String,
    val kind: RenderedResultKind,
    val face: RenderedFaceState,
    val exposure: RenderedExposureState,
) {
    init { require(rendererContract.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) }
    fun requirePublishable(supportedContracts: Set<RendererPublicationContract>) {
        if (RendererPublicationContract(rendererContract, request.recipe.schema) !in supportedContracts || kind != RenderedResultKind.COMPLETE_STAGE_CLEAR ||
            face !in setOf(RenderedFaceState.VERIFIED_FACES, RenderedFaceState.VERIFIED_NONE) ||
            exposure != RenderedExposureState.SUPPORTED) throw IOException("Render result is not supported for publication")
    }
}

internal data class JpegInspection(val output: JpegOutputDescription, val fingerprint: OriginalFingerprint)

/** Validates and hashes the same complete encoded bytes. Reject partial decode, unknown color or metadata. */
internal fun interface JpegInspector {
    fun inspect(openReadOnly: () -> InputStream, shouldStop: () -> Boolean): JpegInspection
    /** Known length is a bound, not a content/codec success claim. Legacy adapters must still check it. */
    fun inspect(openReadOnly: () -> InputStream, expectedBytes: Long, shouldStop: () -> Boolean): JpegInspection {
        if (expectedBytes !in 1..JpegOutputDescription.MAX_JPEG_BYTES) throw IOException("Invalid expected JPEG length")
        return inspect(openReadOnly, shouldStop).also {
            if (it.output.bytes != expectedBytes || it.fingerprint.bytes != expectedBytes) throw IOException("JPEG length mismatch")
        }
    }
}

/** Producer has completed and closed the private JPEG before handing it over. Paths are never caller input. */
internal interface RenderedJpegInput {
    fun openJpeg(): InputStream
    fun openOriginal(reference: SharedOriginalReference): InputStream
}
