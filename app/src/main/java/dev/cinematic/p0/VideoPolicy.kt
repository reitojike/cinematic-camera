package dev.cinematic.p0

enum class CaptureMode { STILL, CINE }

data class VideoChoice(
    val key: String,
    val qualityRank: Int,
    val dynamicRange: String,
    val fpsLower: Int,
    val fpsUpper: Int,
    val label: String,
)

/** Preference selection never silently rewrites a user's saved request. */
object VideoPolicy {
    fun ordered(choices: List<VideoChoice>, requested: String?): List<VideoChoice> {
        val baseline = choices.filter { it.dynamicRange == "SDR" }.sortedWith(
            compareByDescending<VideoChoice> { it.qualityRank }
                .thenByDescending { it.fpsUpper }
                .thenByDescending { it.fpsLower == it.fpsUpper }
                .thenByDescending { it.fpsLower })
        val preferred = choices.find { it.key == requested }
        return if (preferred == null) baseline else listOf(preferred) + baseline.filter { it.key != preferred.key }
    }

    fun fallbackReason(requested: String?, applied: String): String =
        if (requested == null) "Initial capability-based selection; actual output not yet measured"
        else if (requested == applied) "Saved user request selected; actual output not yet measured"
        else "Saved request $requested unavailable; applied $applied. Saved request was retained."
}

enum class RecordingStage { IDLE, STARTING, RECORDING, STOPPING, FINALIZING }

/** Audio can fail while Recorder still returns a valid video and an existing audio track. */
class AudioQualityNotes(private val requested: Boolean) {
    private var firstError: String? = null
    private var wasSilenced = false
    fun observe(hasError: Boolean, errorCause: String?, sourceSilenced: Boolean) {
        if (!requested) return
        if (hasError && firstError == null) firstError = errorCause?.take(200) ?: "audio source/encoder error"
        if (sourceSilenced) wasSilenced = true
    }
    fun warning(hasAudioTrack: Boolean): String {
        if (!requested) return ""
        val warnings = buildList {
            firstError?.let { add("audio failed during recording: $it") }
            if (wasSilenced) add("audio source was silenced during recording")
            if (!hasAudioTrack) add("audio was requested but MP4 has no audio track")
        }
        return if (warnings.isEmpty()) "" else " AUDIO_WARNING: ${warnings.joinToString("; ")}"
    }
}

/** A new recording cannot begin until the previous output has finished publication/cleanup. */
class RecordingGate {
    var stage = RecordingStage.IDLE; private set
    var token: String? = null; private set
    val busy get() = stage != RecordingStage.IDLE

    fun begin(value: String) {
        check(!busy) { "Previous recording has not finalized" }
        token = value; stage = RecordingStage.STARTING
    }
    fun started(value: String) {
        if (token == value && stage == RecordingStage.STARTING) stage = RecordingStage.RECORDING
    }
    fun stop(): Boolean {
        if (stage != RecordingStage.STARTING && stage != RecordingStage.RECORDING) return false
        stage = RecordingStage.STOPPING
        return true
    }
    fun finalize(value: String): Boolean {
        if (token != value || stage == RecordingStage.IDLE || stage == RecordingStage.FINALIZING) return false
        stage = RecordingStage.FINALIZING
        return true
    }
    fun complete(value: String) {
        if (token == value) { stage = RecordingStage.IDLE; token = null }
    }
}
