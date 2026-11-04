package dev.cinematic.p0

import androidx.camera.video.AudioStats
import java.util.Locale

/** Current state only. Bound video combinations are configuration, never measured output FPS. */
data class CaptureChromeInput(
    val mode: CaptureMode,
    val bound: Boolean = false,
    val cameraId: String? = null,
    val manualExposure: Boolean? = null,
    val exposureModePhase: ControlPhase? = null,
    val photoFormat: String? = null,
    val boundVideoChoice: VideoChoice? = null,
    val recordingStage: RecordingStage = RecordingStage.IDLE,
    val recordingDurationMs: Long? = null,
    val photoStage: PhotoStage = PhotoStage.IDLE,
    val stillPhase: StillExposurePhase? = null,
    val ownsStill: Boolean = false,
    val stillFilesTerminal: Boolean = false,
    val stillFilesSaved: Boolean? = null,
    val stillCapturePhase: ControlPhase? = null,
    val busy: Boolean = false,
    val captureReady: Boolean = false,
    val interactable: Boolean = true,
    val blockedReason: String? = null,
    val thermalSeverity: Int? = null,
    val audioWanted: Boolean = false,
    val audioPermissionDenied: Boolean = false,
    val audioState: Int? = null,
    val audioAmplitude: Double? = null,
    val recordingFailure: String? = null,
    val recordingSaveSucceeded: Boolean? = null,
)

enum class CaptureChromeTone { NORMAL, WARNING, FAILURE, RECORDING, ACCEPTED }
enum class ShutterColor { CONTROL, RECORDING, INACTIVE, EXPOSING }
data class CaptureShutterModel(
    val innerDp: Int,
    val square: Boolean,
    val ring: ShutterColor,
    val inner: ShutterColor,
    val alpha: Float,
    val enabled: Boolean,
    /** State label retained for accessibility/tests; no caption is drawn below the shutter. */
    val caption: String,
    val captionTone: CaptureChromeTone,
    val actionDescription: String,
)

fun captureShutterModel(input: CaptureChromeInput): CaptureShutterModel {
    val cine = input.mode == CaptureMode.CINE
    val stop = cine && input.recordingStage in setOf(RecordingStage.STARTING, RecordingStage.RECORDING)
    if (stop) return CaptureShutterModel(if (input.recordingStage == RecordingStage.RECORDING) 26 else 48,
        input.recordingStage == RecordingStage.RECORDING,
        if (input.recordingStage == RecordingStage.RECORDING) ShutterColor.RECORDING else ShutterColor.CONTROL,
        if (input.recordingStage == RecordingStage.RECORDING) ShutterColor.RECORDING else ShutterColor.CONTROL,
        1f, input.interactable, if (input.recordingStage == RecordingStage.RECORDING) "停止" else "開始中", CaptureChromeTone.WARNING,
        if (input.recordingStage == RecordingStage.STARTING) "録画開始中・録画停止" else "録画停止")
    if (cine && input.recordingStage in setOf(RecordingStage.STOPPING, RecordingStage.FINALIZING)) {
        val caption = if (input.recordingStage == RecordingStage.STOPPING) "停止処理中" else "保存処理中"
        return CaptureShutterModel(26, true, ShutterColor.INACTIVE, ShutterColor.INACTIVE,
            .6f, false, caption, CaptureChromeTone.WARNING, caption)
    }
    val severe = cine && input.thermalSeverity?.let(ProbeControls::shouldStopRecording) == true
    val enabled = input.interactable && input.captureReady && input.bound && !input.busy &&
        !input.ownsStill && !severe && (cine || input.photoStage !in setOf(PhotoStage.CAPTURING, PhotoStage.SAVING))
    val stillCaption = if (!cine && input.ownsStill) when (input.stillPhase) {
        StillExposurePhase.CAPTURE_APPLYING -> "撮影準備中"
        StillExposurePhase.CAPTURING -> if (input.photoStage == PhotoStage.SAVING) "保存中" else "撮影中"
        StillExposurePhase.UNKNOWN -> "復旧が必要"
        else -> "復帰待ち"
    } else null
    val preparationFailed = !cine && input.stillFilesTerminal && input.stillFilesSaved == false && input.stillCapturePhase == ControlPhase.UNKNOWN
    val failed = if (cine) input.recordingFailure != null || input.recordingSaveSucceeded == false
        else input.photoStage == PhotoStage.FAILED || input.stillFilesTerminal && input.stillFilesSaved == false
    val caption = when {
        stillCaption != null -> stillCaption
        !cine && input.photoStage == PhotoStage.CAPTURING -> "撮影中"
        !cine && input.photoStage == PhotoStage.SAVING -> "保存中"
        preparationFailed -> "撮影未開始"
        failed -> if (cine) { if (input.recordingSaveSucceeded == false) "保存失敗" else "録画失敗" } else "撮影/保存失敗"
        severe -> "録画不可"
        input.busy -> "設定反映中"
        !input.bound -> "未接続"
        !input.captureReady -> "撮影待ち"
        !cine && input.photoStage == PhotoStage.SAVED -> "保存済"
        cine && input.recordingSaveSucceeded == true -> "保存済"
        else -> ""
    }
    val unknownOwner = input.ownsStill && input.stillPhase == StillExposurePhase.UNKNOWN
    val tone = when {
        failed || unknownOwner || severe -> CaptureChromeTone.FAILURE
        caption == "保存済" -> CaptureChromeTone.ACCEPTED
        else -> CaptureChromeTone.WARNING
    }
    val exposing = !cine && input.photoStage in setOf(PhotoStage.CAPTURING, PhotoStage.SAVING) &&
        (!input.ownsStill || input.stillPhase == StillExposurePhase.CAPTURING)
    val action = if (cine) "録画開始" else "静止画撮影"
    val reason = input.blockedReason ?: caption.takeIf { it.isNotEmpty() } ?: "撮影条件の確認待ち"
    return CaptureShutterModel(if (cine) 48 else 58, false,
        ShutterColor.CONTROL, ShutterColor.CONTROL,
        if (severe) .35f else if (!enabled && !exposing && input.photoStage != PhotoStage.SAVING) .4f else 1f,
        enabled, caption, tone, if (enabled) "$action${if (caption.isNotEmpty()) "・$caption" else ""}" else "$action・操作不可: $reason")
}

data class CaptureStatusChip(
    val id: String,
    val text: String,
    val compact: String = text,
    val detail: String = text,
    val tone: CaptureChromeTone = CaptureChromeTone.NORMAL,
    val mode: Boolean = false,
)

private fun elapsedText(durationMs: Long?): String = durationMs?.takeIf { it >= 0 }?.let {
    "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}"
} ?: "—"

fun captureStatusChips(input: CaptureChromeInput): List<CaptureStatusChip> = buildList {
    val mode = when {
        input.exposureModePhase == ControlPhase.UNBOUND -> "—"
        input.manualExposure == true -> "MANUAL"
        input.manualExposure == false -> "AUTO"
        else -> "—"
    }
    val modeUncertain = input.exposureModePhase in setOf(ControlPhase.UNKNOWN, ControlPhase.REJECTED)
    val modeLabel = if (modeUncertain && mode != "—") "$mode?" else mode
    val compactMode = when (modeLabel) { "MANUAL" -> "MAN"; "MANUAL?" -> "MAN?"; "AUTO?" -> "AUTO\n?"; else -> modeLabel }
    val modeDetail = when (input.exposureModePhase) {
        ControlPhase.PENDING -> "露出mode要求: $mode。API受付待ち・実測一致は別です"
        ControlPhase.UNKNOWN, ControlPhase.REJECTED -> "露出mode不明。論理要求: $mode。API受付は未確認です"
        ControlPhase.UNBOUND -> "露出mode: 未接続・未確認"
        ControlPhase.ACCEPTED -> "露出mode: $mode。API受付・実測一致は別です"
        null -> "論理露出mode: $mode。API受付状態・実測一致は別です"
    }
    add(CaptureStatusChip("exposure", modeLabel, compactMode, modeDetail,
        tone = when {
            modeUncertain -> CaptureChromeTone.FAILURE
            input.exposureModePhase == ControlPhase.PENDING -> CaptureChromeTone.WARNING
            else -> CaptureChromeTone.NORMAL
        }, mode = true))
    val camera = input.cameraId?.takeIf { input.bound && it.isNotBlank() } ?: "—"
    add(CaptureStatusChip("camera", "CAM $camera", "C$camera", "接続中camera ID: $camera"))
    if (input.mode == CaptureMode.STILL) {
        val format = input.photoFormat?.takeIf { input.bound && it.isNotBlank() } ?: "—"
        add(CaptureStatusChip("format", format, if (format == "RAW+JPEG") "R+J" else format,
            "撮影形式の接続済み設定: $format。保存ファイルの検証結果ではありません"))
    } else {
        val video = input.boundVideoChoice?.takeIf { input.bound }
        val quality = when (video?.qualityRank) { 4 -> "UHD"; 3 -> "FHD"; 2 -> "HD"; 1 -> "SD"; else -> "—" }
        val fps = video?.takeIf { it.fpsLower > 0 && it.fpsUpper >= it.fpsLower }?.let {
            if (it.fpsLower == it.fpsUpper) it.fpsUpper.toString() else "${it.fpsLower}–${it.fpsUpper}"
        } ?: "—"
        val range = video?.dynamicRange?.takeIf { it.isNotBlank() } ?: "—"
        val detail = "動画bind組合せ: $quality / $fps fps / $range。実測FPS・実出力の証明ではありません"
        add(CaptureStatusChip("video_quality", "組 $quality", quality, detail))
        add(CaptureStatusChip("video_fps", "$fps fps", fps.replace("–", "–\n"), detail))
        add(CaptureStatusChip("video_range", range, range, detail))
    }
    when (input.recordingStage) {
        RecordingStage.STARTING -> add(CaptureStatusChip("operation", "録画開始中", "開始", "録画開始中・停止操作可能", CaptureChromeTone.WARNING))
        RecordingStage.RECORDING -> add(CaptureStatusChip("operation", "● REC ${elapsedText(input.recordingDurationMs)}", "●${elapsedText(input.recordingDurationMs)}",
            "録画中・経過 ${elapsedText(input.recordingDurationMs)}", CaptureChromeTone.RECORDING))
        RecordingStage.STOPPING -> add(CaptureStatusChip("operation", "停止処理中", "停止", tone = CaptureChromeTone.WARNING))
        RecordingStage.FINALIZING -> add(CaptureStatusChip("operation", "最終保存中", "保存", tone = CaptureChromeTone.WARNING))
        RecordingStage.IDLE -> if (input.mode == CaptureMode.STILL) {
            val waiting = if (input.ownsStill) when (input.stillPhase) {
                StillExposurePhase.CAPTURE_APPLYING -> "撮影準備中"
                StillExposurePhase.CAPTURING -> if (input.photoStage == PhotoStage.SAVING) "保存中" else "撮影中"
                StillExposurePhase.UNKNOWN -> null // Failure/recovery information belongs to the notice area.
                else -> "プレビュー復帰中"
            } else when (input.photoStage) { PhotoStage.CAPTURING -> "撮影中"; PhotoStage.SAVING -> "保存中"; else -> null }
            waiting?.let { add(CaptureStatusChip("operation", it, if (it == "プレビュー復帰中") "復帰" else it,
                tone = if (input.stillPhase == StillExposurePhase.UNKNOWN) CaptureChromeTone.FAILURE else CaptureChromeTone.WARNING)) }
        }
    }
    val thermal = input.thermalSeverity?.takeIf { it in 0..6 }
    val thermalText = thermal?.let(ProbeControls::thermalName) ?: "—"
    add(CaptureStatusChip("thermal", "熱 $thermalText", thermalText.take(3), "OS熱状態: $thermalText。温度℃ではありません",
        when (thermal) { in 3..6 -> CaptureChromeTone.FAILURE; 1, 2 -> CaptureChromeTone.WARNING; else -> CaptureChromeTone.NORMAL }))
    if (input.mode == CaptureMode.CINE) {
        val audio = when {
            !input.audioWanted -> CaptureStatusChip("audio", "MIC OFF", "OFF", "音声収録要求: OFF")
            input.audioPermissionDenied -> CaptureStatusChip("audio", "MIC 権限なし", "権限", "マイク権限なし・録画開始不可", CaptureChromeTone.FAILURE)
            input.recordingStage != RecordingStage.RECORDING -> CaptureStatusChip("audio",
                if (input.recordingStage == RecordingStage.STARTING) "MIC 準備中" else "MIC ON/待機", "ON",
                "音声収録要求: ON。${input.recordingStage}の処理状態です。直前のAudioStatsを現在値として表示しません")
            else -> {
                val label = when (input.audioState) {
                    AudioStats.AUDIO_STATE_ACTIVE -> "収録中"
                    AudioStats.AUDIO_STATE_DISABLED -> "OFF"
                    AudioStats.AUDIO_STATE_SOURCE_SILENCED -> "消音"
                    AudioStats.AUDIO_STATE_ENCODER_ERROR -> "符号化失敗"
                    AudioStats.AUDIO_STATE_SOURCE_ERROR -> "入力失敗"
                    AudioStats.AUDIO_STATE_MUTED -> "ミュート"
                    else -> "—"
                }
                val tone = when (input.audioState) {
                    AudioStats.AUDIO_STATE_ENCODER_ERROR, AudioStats.AUDIO_STATE_SOURCE_ERROR -> CaptureChromeTone.FAILURE
                    AudioStats.AUDIO_STATE_SOURCE_SILENCED, AudioStats.AUDIO_STATE_MUTED -> CaptureChromeTone.WARNING
                    else -> CaptureChromeTone.NORMAL
                }
                CaptureStatusChip("audio", "MIC $label", if (label == "符号化失敗") "符号化" else label,
                    "音声収録要求: ON。現在の音声状態: $label。音声品質の保証ではありません", tone)
            }
        }
        add(audio)
        if (input.audioWanted && input.recordingStage == RecordingStage.RECORDING) {
            val amplitude = input.audioAmplitude?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"
            add(CaptureStatusChip("audio_amplitude", "AMP $amplitude", amplitude, "AudioStats振幅: $amplitude。クリッピング判定ではありません"))
        }
    }
}.sortedBy { when (it.id) { "exposure" -> 0; "operation" -> 1; else -> 2 } }

/** Retain the first valid AUTO observation as the editor's starting choice. */
internal fun captureAutoRulerSeed(seed: ManualExposure?, fresh: ManualExposure?): ManualExposure? = seed ?: fresh

/** Six keys share one horizontal row at every orientation. */
internal fun captureKeyRows(): List<List<CaptureKey>> = listOf(CaptureKey.entries.toList())

/** The compact visual label keeps the full control name for accessibility. */
internal fun captureKeyVisualLabel(key: CaptureKey): String = if (key == CaptureKey.SHUTTER) "SS" else key.name

/** Reserve room for the ruler while giving every key row the same maximum height. */
internal fun captureKeyMaxHeight(previewHeightDp: Float, rowCount: Int, rulerOpen: Boolean): Float =
    ((previewHeightDp - if (rulerOpen) 104f else 0f).coerceAtLeast(0f) / rowCount.coerceAtLeast(1))

/** The accessibility state reports the current mode, independent of the next action. */
internal fun captureModeSwitchState(mode: CaptureMode): String = mode.name

/** Spoken values retain seconds even though the SS control omits units visually. */
internal fun captureShutterDescription(value: String): String = "$value 秒"

/** A rotated switch label uses the segment's height as its text width. */
internal fun captureModeLabelRoom(cell: androidx.compose.ui.unit.IntSize, horizontal: Boolean): androidx.compose.ui.unit.IntSize =
    if (horizontal) androidx.compose.ui.unit.IntSize(cell.height, cell.width) else cell

/** Keep an AUTO freshness warning visible without the obsolete edit/apply explanation. */
internal fun captureRulerTitle(label: String, waiting: Boolean): String =
    if (waiting) "$label 待機" else label

internal fun captureRulerDescription(label: String, waiting: Boolean): String =
    if (waiting) "$label AUTO実測待ち、選択一時停止" else label

/** Landscape rotates the rail's physical height into the visible width of each key. */
internal fun captureKeyCommonHeight(measuredHeights: Collection<Float>): Float = measuredHeights.maxOrNull() ?: 0f
internal fun captureKeyHeight(naturalHeight: Float, commonHeight: Float, horizontal: Boolean): Float =
    if (horizontal) maxOf(naturalHeight, commonHeight) else naturalHeight
