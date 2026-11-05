package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.raw.PhotoAdjustments
import java.io.IOException

/**
 * Manual saving of a preliminary original-size result. The serial owner constructs this only after
 * rendering every sample, closing all owners, inspecting the JPEG and rehashing the original.
 * It is deliberately not CompletedRender, and cannot be enrolled in the automatic renderer path.
 */
internal data class ManualOriginalTrialRender(
    override val request: RenderedJpegRequest,
    val geometry: OriginalRasterGeometry,
    val outputFingerprint: OriginalFingerprint,
    val appliedStrength: Double,
    val externalEv: Double,
    val adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
) : JpegPublicationRender {
    override val rendererContract get() = CONTRACT.renderer
    override val kind get() = RenderedResultKind.MANUAL_ORIGINAL_TRIAL
    override val face get() = RenderedFaceState.NOT_RUN
    override val exposure get() = RenderedExposureState.DIAGNOSTIC
    val profile get() = AutoInputProfile.UNKNOWN

    init {
        require(request.captureId.startsWith("TRIAL24_")) { "Manual trial name must identify trial quality" }
        require(appliedStrength.isFinite() && appliedStrength in 0.0..1.0 && externalEv.isFinite())
        require(request.recipe == recipe(appliedStrength, adjustments)) { "Manual trial recipe differs from applied edits" }
        require(request.source.fingerprint.bytes <= 64L * 1024 * 1024)
        require(request.output.width in 1..8192 && request.output.height in 1..8192 &&
            request.output.width.toLong() * request.output.height <= 24_000_000)
        require(geometry.covers(request.output)) { "Manual trial must cover the complete original raster" }
        require(outputFingerprint.bytes == request.output.bytes)
    }

    override fun requirePublishable(supportedContracts: Set<RendererPublicationContract>) {
        if (CONTRACT !in supportedContracts) throw IOException("Manual original trial publication is not enrolled")
    }

    override fun requireFingerprint(fingerprint: OriginalFingerprint) {
        if (fingerprint != outputFingerprint) throw IOException("Manual trial output fingerprint differs")
    }

    companion object {
        val CONTRACT = RendererPublicationContract("manual-original24-trial-v2", "stage-clear-private-original24-v2")
        fun recipe(strength: Double, adjustments: PhotoAdjustments = PhotoAdjustments.NONE): RenderRecipeSnapshot {
            require(strength.isFinite() && strength in 0.0..1.0)
            return RenderRecipeSnapshot(CONTRACT.recipeSchema,
                "strength=${java.lang.Double.toHexString(strength)};quality=95;input=as-shot-integer-dng24-v1;face=NOT_RUN;ev=CENTRAL_P97_DIAGNOSTIC;profile=preliminary;" +
                    "adjust=${PhotoAdjustments.VERSION};warmth=${java.lang.Double.toHexString(adjustments.warmth)};exposureEv=${java.lang.Double.toHexString(adjustments.exposureEv)}")
        }

        /** Restore only this exact recipe; unknown keys, policies or noncanonical numbers are rejected. */
        fun adjustmentsFromRecipe(snapshot: RenderRecipeSnapshot, strength: Double): PhotoAdjustments {
            fun number(key: String): Double = snapshot.payload.split(';').singleOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')?.toDoubleOrNull() ?: throw IOException("Manual trial adjustment missing")
            val adjustments = try { PhotoAdjustments(number("warmth"), number("exposureEv")) }
                catch (e: IllegalArgumentException) { throw IOException("Invalid manual trial adjustments", e) }
            if (snapshot != recipe(strength, adjustments)) throw IOException("Noncanonical manual trial recipe")
            return adjustments
        }
    }
}
