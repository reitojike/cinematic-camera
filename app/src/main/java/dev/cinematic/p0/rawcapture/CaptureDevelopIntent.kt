package dev.cinematic.p0.rawcapture

/** Capture-time opt-in only, committed before opening camera outputs. Not a render request or success.
 * No observed dimensions/profile/face/EV are invented here. A future bridge must establish those
 * under common admission, confirm complete capture + shared original, and enroll a real FINAL provider.
 * Absence (including legacy receipts) is opt-out; never fill it from current app settings. */
internal data class CaptureDevelopIntent(
    val recipe: RenderRecipeSnapshot,
    val rendererContract: String,
    val requestedQuality: AutoRenderQuality = AutoRenderQuality.FINAL_ORIGINAL,
) {
    init {
        require(rendererContract.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        require(requestedQuality == AutoRenderQuality.FINAL_ORIGINAL) { "Automatic capture intent requires original-quality output" }
    }
}
