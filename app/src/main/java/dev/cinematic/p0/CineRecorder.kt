package dev.cinematic.p0

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.video.AudioStats
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors

class CineRecorder(context: Context, private val event: (String) -> Unit, private val changed: () -> Unit) {
    private val app = context.applicationContext
    private val main = ContextCompat.getMainExecutor(app)
    private val gate = RecordingGate()
    private var recording: Recording? = null
    private var stopRequestedAtMs: Long? = null
    var stage by mutableStateOf(RecordingStage.IDLE); private set
    var stats by mutableStateOf("No recording yet; quality/audio/device validation pending"); private set
    var startSequence by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var audioAmplitude by mutableStateOf<Double?>(null); private set
    var audioState by mutableStateOf<Int?>(null); private set
    val busy get() = gate.busy

    private fun refresh() { stage = gate.stage; changed() }

    fun start(recorder: Recorder, audio: Boolean, requested: String) {
        if (busy) return
        if (Build.VERSION.SDK_INT < 29) { event("UNSUPPORTED shared video saving requires Android 10+"); return }
        if (audio && ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            event("Audio permission missing; grant microphone or explicitly choose audio off")
            return
        }
        val token = UUID.randomUUID().toString()
        val appRecordStart = SystemClock.elapsedRealtime()
        val audioNotes = AudioQualityNotes(audio)
        stopRequestedAtMs = null
        startSequence += 1
        durationMs = 0; audioAmplitude = null; audioState = null
        gate.begin(token); refresh()
        var output: PendingVideoOutput? = null
        fun finalize(error: String?) {
            if (!gate.finalize(token)) return
            val terminalAt = SystemClock.elapsedRealtime()
            val stopToTerminalMs = stopRequestedAtMs?.let { terminalAt - it }
            event("VIDEO_TIMING token=$token appRequestToTerminalMs=${terminalAt - appRecordStart} stopRequestToTerminalMs=$stopToTerminalMs; event notification, not codec/sensor latency")
            recording = null; refresh()
            val sink = output
            io.execute {
                val result = runCatching {
                    check(sink != null) { error ?: "No recording output created" }
                    VideoPublication.finish(sink, error)
                }
                val ioFinishedAt = SystemClock.elapsedRealtime()
                main.execute {
                    gate.complete(token); refresh()
                    event("VIDEO_TIMING token=$token terminalToIoFinishedMs=${ioFinishedAt - terminalAt} appRequestToIoFinishedMs=${ioFinishedAt - appRecordStart} ioFinishedToUiNotificationMs=${SystemClock.elapsedRealtime() - ioFinishedAt} outputSuccess=${result.isSuccess}; app stages only")
                    result.fold(onSuccess = { file ->
                        val warning = audioNotes.warning(file.hasAudio)
                        stats = "Saved ${file.width}×${file.height}, ${file.durationMs} ms, sample fps=${file.sampleFps}, audioTrack=${file.hasAudio}$warning"
                        event("VIDEO_SAVED token=$token uri=${sink?.uri} bytes=${file.bytes} actualSize=${file.width}x${file.height} rotation=${file.rotation} durationMs=${file.durationMs} sampleFrames=${file.sampledFrames} sampledFps=${file.sampleFps} codec=${file.codec} ${file.color} audioTrack=${file.hasAudio} requested=[$requested] qualityValidation=UNVERIFIED$warning")
                    }, onFailure = { failure ->
                        stats = "Recording/save failed: ${failure.message}"
                        event("ERROR VIDEO_FINALIZE token=$token ${failure.javaClass.simpleName}: ${failure.message}")
                    })
                }
            }
        }
        try {
            val created = PendingVideoOutput.create(app)
            output = created
            val options = FileDescriptorOutputOptions.Builder(created.descriptor).build()
            var pending = recorder.prepareRecording(app, options)
            if (audio) pending = pending.withAudioEnabled()
            event("VIDEO_BEGIN token=$token requested=[$requested] audioRequested=$audio pendingUri=${created.uri}")
            recording = pending.start(main) recordEvent@ { update ->
                if (gate.token != token) return@recordEvent
                val audioStats = update.recordingStats.audioStats
                audioNotes.observe(audioStats.hasError(), audioStats.errorCause?.message,
                    audioStats.audioState == AudioStats.AUDIO_STATE_SOURCE_SILENCED)
                when (update) {
                    is VideoRecordEvent.Start -> {
                        gate.started(token); refresh()
                        event("VIDEO_START token=$token afterAppRecordRequestMs=${SystemClock.elapsedRealtime() - appRecordStart}; event notification, not sensor latency")
                    }
                    is VideoRecordEvent.Status -> {
                        if (gate.token != token) return@recordEvent
                        val stat = update.recordingStats
                        durationMs = stat.recordedDurationNanos / 1_000_000
                        audioAmplitude = stat.audioStats.audioAmplitude
                        audioState = stat.audioStats.audioState
                        stats = "${stage.name}: ${stat.recordedDurationNanos / 1_000_000} ms, ${stat.numBytesRecorded} bytes; audioState=${stat.audioStats.audioState}, amplitude=${stat.audioStats.audioAmplitude} (not a clipping test)"
                    }
                    is VideoRecordEvent.Finalize -> {
                        val error = if (update.hasError()) "Recorder error=${update.error}: ${update.cause?.message}" else null
                        event("VIDEO_RECORDER_FINALIZE token=$token error=${update.error}; validating output before publication")
                        finalize(error)
                    }
                }
            }
            if (gate.stage == RecordingStage.STOPPING) recording?.stop()
        } catch (failure: Exception) {
            finalize("Record start failed: ${failure.javaClass.simpleName}: ${failure.message}; cleanupSuppressed=${failure.suppressed.size}")
        }
    }

    fun stop(reason: String) {
        if (!gate.stop()) return
        stopRequestedAtMs = SystemClock.elapsedRealtime()
        refresh(); event("VIDEO_STOP reason=$reason; awaiting Finalize and output publication")
        try { recording?.stop() }
        catch (failure: Exception) { event("ERROR video stop ${failure.message}; waiting for recorder Finalize") }
    }

    companion object {
        private val io = Executors.newSingleThreadExecutor { work -> Thread(work, "cine-output").apply { isDaemon = true } }
    }
}
