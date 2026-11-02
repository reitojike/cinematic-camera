package dev.cinematic.p0

import android.util.Rational
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraInfo
import androidx.camera.core.DynamicRange
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.ViewPort
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

data class PreparedCine(
    val choice: VideoChoice,
    val recorder: Recorder,
    val video: VideoCapture<Recorder>,
    val session: SessionConfig,
)

object CineSessions {
    fun query(info: CameraInfo, preview: Preview, log: (String) -> Unit): List<PreparedCine> {
        // Portrait Activity coordinates: crop both use cases to the same sensor region.
        // FIT would ignore this ratio. UI panels and physical orientation never rebuild it.
        val viewPort = ViewPort.Builder(Rational(9, 16), Surface.ROTATION_0)
            .setScaleType(ViewPort.FILL_CENTER).build()
        val capabilities = Recorder.getVideoCapabilities(info)
        val result = mutableListOf<PreparedCine>()
        for (range in capabilities.supportedDynamicRanges) {
            val rangeName = when (range) {
                DynamicRange.SDR -> "SDR"
                DynamicRange.HLG_10_BIT -> "HLG10"
                DynamicRange.HDR10_10_BIT -> "HDR10"
                DynamicRange.HDR10_PLUS_10_BIT -> "HDR10+"
                DynamicRange.DOLBY_VISION_10_BIT -> "DolbyVision10"
                DynamicRange.DOLBY_VISION_8_BIT -> "DolbyVision8"
                else -> continue
            }
            val qualities = capabilities.getSupportedQualities(range)
            log("VIDEO_CAPABILITY range=$rangeName qualities=$qualities")
            for (quality in qualities) {
                try {
                    val qualityName = when (quality) {
                        Quality.UHD -> "UHD"; Quality.FHD -> "FHD"; Quality.HD -> "HD"; Quality.SD -> "SD"
                        else -> continue
                    }
                    val rank = when (quality) { Quality.UHD -> 4; Quality.FHD -> 3; Quality.HD -> 2; else -> 1 }
                    val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality))
                        .setAspectRatio(AspectRatio.RATIO_16_9).build()
                    val video = VideoCapture.Builder(recorder).setDynamicRange(range).build()
                    val probe = SessionConfig.Builder(preview, video).setViewPort(viewPort).build()
                    // Combination-specific query, not generic camera AE ranges. No per-UseCase target fps.
                    val frameRates = info.getSupportedFrameRateRanges(probe)
                    log("VIDEO_COMBINATION quality=$qualityName range=$rangeName fps=$frameRates")
                    for (fps in frameRates) {
                        if (fps.lower <= 0 || fps.upper <= 0) continue
                        val session = SessionConfig.Builder(preview, video).setViewPort(viewPort)
                            .setFrameRateRange(fps).build()
                        if (!info.isSessionConfigSupported(session)) continue
                        val key = "$rangeName/$qualityName/${fps.lower}-${fps.upper}"
                        result += PreparedCine(VideoChoice(key, rank, rangeName, fps.lower, fps.upper,
                            "$qualityName $rangeName ${if (fps.lower == fps.upper) fps.upper else "${fps.lower}–${fps.upper}"} fps"),
                            recorder, video, session)
                    }
                } catch (failure: Exception) {
                    log("VIDEO_CAPABILITY_ERROR range=$rangeName quality=$quality ${failure.javaClass.simpleName}: ${failure.message}")
                }
            }
        }
        return result
    }
}
