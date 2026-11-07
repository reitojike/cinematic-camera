package dev.cinematic.p0

import kotlin.math.min

data class CaptureDimensions(val previewHeight: Float, val settingsHeight: Float)

/** Panels overlay a stable preview; only the capture rail reserves layout space. */
object CaptureLayout {
    const val CAPTURE_RAIL = 96f
    fun previewHeight(available: Float): Float = (available - CAPTURE_RAIL).coerceAtLeast(0f)
    fun measure(available: Float, panel: CapturePanel): CaptureDimensions = CaptureDimensions(
        previewHeight(available), if (panel == CapturePanel.SETTINGS) sheetHeight(available) else 0f)
    fun previewTop(mode: CaptureMode): Float = if (mode == CaptureMode.STILL) 44f else 0f
    fun fit(width: Float, height: Float, imageWidth: Float, imageHeight: Float): Pair<Float, Float> {
        require(listOf(width, height, imageWidth, imageHeight).all { it.isFinite() && it > 0 })
        val scale = min(width / imageWidth, height / imageHeight)
        return imageWidth * scale to imageHeight * scale
    }
    fun sheetHeight(available: Float): Float = min(CaptureDesign.SettingsHeight, previewHeight(available))
}

/** Diagnostics must never cover the stop rail once recording preparation begins. */
fun recordingPanel(panel: CapturePanel, stage: RecordingStage): CapturePanel =
    if (panel == CapturePanel.DIAGNOSTICS && stage != RecordingStage.IDLE) CapturePanel.NONE else panel

/** The caller retains each screen's thresholds and font-scale policy. */
internal fun captureNeedsFullScroll(widthDp: Float, heightDp: Float, fontScale: Float,
    minHeightDp: Float, minWidthDp: Float = 360f): Boolean =
    heightDp < minHeightDp * fontScale || widthDp < minWidthDp * fontScale
