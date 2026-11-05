package dev.cinematic.p0.rawcapture

/** Synthetic policy identities only. This file cannot register a production renderer. */
internal val syntheticOriginalDeclaration = OriginalRenderDeclaration(
    RenderPolicyVersion("synthetic-input", 1), RenderPolicyVersion("synthetic-raster", 1),
    RenderPolicyVersion("synthetic-detector", 1), RenderPolicyVersion("synthetic-metering", 1), -4.0, 4.0)

internal fun AutoDevelopRequest.forStrictOriginal() = copy(width = 0, height = 0,
    quality = AutoRenderQuality.FINAL_ORIGINAL_STRICT, face = RenderedFaceState.NOT_RUN,
    exposure = RenderedExposureState.UNKNOWN, profile = AutoInputProfile.UNKNOWN,
    originalDeclaration = syntheticOriginalDeclaration)

internal fun AutoDevelopRequest.forLegacyOriginal(width: Int, height: Int) = copy(width = width, height = height,
    quality = AutoRenderQuality.FINAL_ORIGINAL, face = RenderedFaceState.VERIFIED_NONE,
    exposure = RenderedExposureState.SUPPORTED, profile = AutoInputProfile.SUPPORTED, originalDeclaration = null)

internal fun AutoDevelopRequest.syntheticObservation(attemptId: String, output: JpegOutputDescription): OriginalRenderObservation {
    val d = requireNotNull(originalDeclaration)
    return OriginalRenderObservation(OriginalAttemptBinding(captureId, generation, attemptId, source.fingerprint,
        OriginalAttemptBinding.recipeHash(recipe), rendererContract), d.inputPolicy, d.geometryPolicy,
        RenderPolicyVersion("synthetic-profile", 1), AutoInputProfile.SUPPORTED, d.faceDetector, d.meteringPolicy, 0.0,
        OriginalRasterGeometry(output.width, output.height, 1, output.width, output.height, true))
}

internal fun AutoDevelopRequest.syntheticReceipt(attemptId: String, width: Int, height: Int, bytes: Long): AutoRenderReceipt {
    val output = JpegOutputDescription(width, height, 1, "sRGB", bytes)
    return AutoRenderReceipt(this, output, RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED,
        syntheticObservation(attemptId, output))
}
