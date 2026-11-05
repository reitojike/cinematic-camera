package dev.cinematic.p0.rawcapture

/** Capture-time opt-in only, committed before opening camera outputs. Not a render request or success.
 * No observed dimensions/profile/face/EV are invented here. Strict declarations freeze requirements,
 * not provider enrollment or success. Execution still needs source/resource checks and a real producer.
 * Absence (including legacy receipts) is opt-out; never fill it from current app settings. */
internal data class CaptureDevelopIntent(
    val recipe: RenderRecipeSnapshot,
    val rendererContract: String,
    val requestedQuality: AutoRenderQuality = AutoRenderQuality.FINAL_ORIGINAL,
    val originalDeclaration: OriginalRenderDeclaration? = null,
) {
    init {
        require(rendererContract.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(requestedQuality in setOf(AutoRenderQuality.FINAL_ORIGINAL, AutoRenderQuality.FINAL_ORIGINAL_STRICT))
        require((requestedQuality == AutoRenderQuality.FINAL_ORIGINAL_STRICT) == (originalDeclaration != null)) {
            "Strict capture intent requires its frozen declaration; legacy intent cannot be upgraded"
        }
    }
}

/** Pure snapshot bridge, not submission or fresh URI/content/grant validation. The future IO owner
 * must re-read durable records and retain existing execution-time original checks. A new capture has
 * one immutable intent at generation 1; edits use the separate manual identity/generation owner. */
internal fun CaptureReceipt.originalDevelopRequest(shared: DngPublicationRecord): AutoDevelopRequest? {
    val intent = developIntent ?: return null
    if (intent.requestedQuality != AutoRenderQuality.FINAL_ORIGINAL_STRICT ||
        phase != CaptureReceiptPhase.FILES_RECEIVED || receivedBytes.keys != expectedFormats ||
        originalFingerprint == null || shared.phase != DngPublicationPhase.SHARED_ONLY ||
        shared.target.captureId != captureId || shared.fingerprint != originalFingerprint) return null
    return AutoDevelopRequest(captureId, 1,
        SharedOriginalReference(requireNotNull(shared.uri), originalFingerprint, OriginalGrant.APP_OWNED),
        intent.recipe, intent.rendererContract, 0, 0, AutoRenderQuality.FINAL_ORIGINAL_STRICT,
        RenderedFaceState.NOT_RUN, RenderedExposureState.UNKNOWN, AutoInputProfile.UNKNOWN, intent.originalDeclaration)
}
