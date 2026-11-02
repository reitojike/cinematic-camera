package dev.cinematic.p0

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.os.Build
import android.os.PowerManager
import android.net.Uri
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.AspectRatio
import androidx.camera.core.FocusMeteringAction
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
class CameraProbe(private val context: Context, private val owner: LifecycleOwner) {
    private val main = ContextCompat.getMainExecutor(context)
    private val preferences = CapturePreferences(context)
    val log = ProbeLog(context)
    var lines by mutableStateOf(""); private set
    var reports by mutableStateOf(emptyList<CameraReport>()); private set
    var cameraXReports by mutableStateOf(""); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var formats by mutableStateOf(emptySet<Int>()); private set
    var format by mutableIntStateOf(ImageCapture.OUTPUT_FORMAT_JPEG); private set
    var status by mutableStateOf("Permission required"); private set
    var desired by mutableStateOf("AUTO"); private set
    var measured by mutableStateOf("No CaptureResult yet"); private set
    var bound by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var controlReady by mutableStateOf(false); private set
    var mode by mutableStateOf(preferences.mode()); private set
    var requestedIso by mutableStateOf("100"); private set
    var requestedMs by mutableStateOf("10"); private set
    var audioWanted by mutableStateOf(preferences.flag("audio", true)); private set
    var highResolution by mutableStateOf(preferences.flag("highResolution", true)); private set
    var videoChoices by mutableStateOf(emptyList<VideoChoice>()); private set
    var videoSelection by mutableStateOf("Not selected; capabilities not queried"); private set
    var stillResolution by mutableStateOf("Highest available requested; actual resolution pending"); private set
    var manualExposure by mutableStateOf(false); private set
    var evStatus by mutableStateOf("No EV request"); private set
    var focusStatus by mutableStateOf("No focus/metering request"); private set
    var thermalStatus by mutableStateOf("OS thermal status unknown; not a temperature measurement"); private set
    var overrides by mutableStateOf(SensorOverrides()); private set
    var overrideStatus by mutableStateOf("CameraX defaults; no accepted overrides"); private set
    var autoLocks by mutableStateOf(AutoLocks()); private set
    var lockStatus by mutableStateOf("No requested locks; transient, not saved"); private set
    var requestedFocus by mutableStateOf("0"); private set
    var exportingReport by mutableStateOf(false); private set
    var appliedExposure by mutableStateOf<ManualExposure?>(null); private set
    var acceptedEv by mutableStateOf<Int?>(null); private set
    var controlReceipt by mutableStateOf(ControlReceipt()); private set
    var sample by mutableStateOf<CaptureSample?>(null); private set
    var photoStage by mutableStateOf(PhotoStage.IDLE); private set
    var lastFailure by mutableStateOf<String?>(null); private set
    var thermalSeverity by mutableIntStateOf(-1); private set
    val currentEpoch get() = epoch
    val acceptedControls get() = ControlSnapshot(appliedExposure, acceptedEv, overrides, autoLocks)
    val activeVideoChoice get() = cineSession?.choice
    val exposureCompensationStep get() = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toDouble()
    val exposureCompensationRange get() = camera?.cameraInfo?.exposureState?.exposureCompensationRange?.let { it.lower..it.upper }
    val exposureCompensationSupported get() = camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true
    private var thermalCode = -1
    private var removeThermalListener: (() -> Unit)? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var view: PreviewView? = null
    private var epoch by mutableIntStateOf(0)
    private var closed = false
    private var cineSession: PreparedCine? = null
    private var pendingStop = false
    private val cine = CineRecorder(context, { message ->
        if (!closed) event(message) else log.write("LATE $message")
    }, ::onCineChanged)
    val recordingBusy get() = cine.busy
    val recordingStage get() = cine.stage
    val recordingStats get() = cine.stats
    val recordingStartSequence get() = cine.startSequence
    val recordingDurationMs get() = cine.durationMs
    val recordingAudioAmplitude get() = cine.audioAmplitude
    val recordingAudioState get() = cine.audioState
    private var activeCaptureToken: String? = null
    val sharedSaveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val selectedReport get() = reports.find { it.id == selectedId }
    init {
        log.onLine = { line -> main.execute { if (!closed) lines = (lines + "\n" + line).takeLast(16000) } }
        observeThermals()
    }

    private fun observeThermals() {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val power = context.getSystemService(PowerManager::class.java)
            fun update(value: Int) {
                if (closed) return
                thermalCode = value; thermalSeverity = value
                thermalStatus = "OS ${ProbeControls.thermalName(value)} ($value); not temperature °C or a quality test"
                log.write("THERMAL $thermalStatus")
                if (ProbeControls.shouldStopRecording(value) && recordingBusy) cine.stop("OS thermal ${ProbeControls.thermalName(value)}")
            }
            val listener = PowerManager.OnThermalStatusChangedListener { update(it) }
            power.addThermalStatusListener(main, listener)
            removeThermalListener = { power.removeThermalStatusListener(listener) }
            update(power.currentThermalStatus)
        } catch (failure: Exception) { log.write("THERMAL_UNAVAILABLE ${failure.message}") }
    }

    private fun event(message: String) { status = message; log.write(message) }
    private fun beginControl(target: ControlSnapshot) {
        controlReceipt = controlReceipt.copy(epoch = epoch, sequence = controlReceipt.sequence + 1,
            phase = ControlPhase.PENDING, requested = target, acceptedAtMs = null, detail = "送信中")
        lastFailure = null
    }
    private fun acceptedControl() {
        controlReceipt = controlReceipt.copy(epoch = epoch, phase = ControlPhase.ACCEPTED,
            accepted = acceptedControls, acceptedAtMs = SystemClock.elapsedRealtime(), detail = "受付済・実測待ち")
    }
    private fun error(operation: String, e: Exception) {
        lastFailure = "$operation: ${e.message}"
        controlReceipt = controlReceipt.failure(e.message ?: operation,
            operation.contains("validation") || operation.contains("input"))
        if (controlReceipt.phase == ControlPhase.UNKNOWN) controlReady = false
        event("ERROR $operation ${e.javaClass.simpleName}: ${e.message}")
    }

    fun reportPickerFailure(failure: Exception) { error("verification report picker", failure) }
    fun reportPickerCancelled() { event("Verification report save cancelled; no document written") }
    fun exportVerificationReport(uri: Uri) {
        if (closed || exportingReport || recordingBusy) {
            if (!closed) event("Report save unavailable while recording/finalizing or another report is pending")
            return
        }
        val snapshotAt = java.time.Instant.now()
        val snapshot = """
            applicationId=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}
            cameraId=$selectedId mode=$mode bound=$bound ready=$controlReady operationPending=$busy
            state=$status
            desiredExposure=$desired
            requestedIsoDraft=$requestedIso requestedMsDraft=$requestedMs
            controlSnapshot=$overrideStatus
            autoLocks=$lockStatus
            focus=$focusStatus EV=$evStatus thermal=$thermalStatus
            observedCaptureResult=$measured
            still=$stillResolution
            video=$videoSelection
            recordingStage=$recordingStage recordingStats=$recordingStats
            CameraX capabilities:
            $cameraXReports
            Camera2 capabilities:
            ${reports.joinToString("\n\n") { it.text }}
        """.trimIndent()
        val resolver = context.applicationContext.contentResolver
        exportingReport = true
        mediaIo.execute {
            try {
                val text = VerificationReport.render(snapshotAt, log.deviceInfo, snapshot, log.tail())
                val bytes = VerificationReport.write({ resolver.openOutputStream(uri, "w") }, text)
                main.execute {
                    if (!closed) { exportingReport = false; event("REPORT_SAVED uri=$uri bytes=$bytes; review before sharing; no automatic upload") }
                    else log.write("LATE REPORT_SAVED uri=$uri bytes=$bytes")
                }
            } catch (failure: Exception) {
                main.execute {
                    if (!closed) { exportingReport = false; error("report save; selected document may be partial, original media unchanged", failure) }
                    else log.write("LATE REPORT_ERROR ${failure.javaClass.simpleName}; selected document may be partial")
                }
            }
        }
    }

    fun initialize(previewView: PreviewView) {
        view = previewView
        try {
            reports = CameraReports.read(context, log::write)
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (closed) return@addListener
                try {
                    provider = future.get()
                    val available = provider!!.availableCameraInfos
                    val entries = available.map { info ->
                        val id = Camera2CameraInfo.from(info).cameraId
                        val supported = try { ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats }
                        catch (e: Exception) { error("CameraX capabilities id=$id", e); emptySet() }
                        "CameraX id=$id formats=${supported.joinToString { formatName(it) }}"
                    }
                    cameraXReports = entries.joinToString("\n")
                    entries.forEach(log::write)
                    val ids = available.map { Camera2CameraInfo.from(it).cameraId }
                    reports.filter { it.id !in ids }.forEach {
                        log.write("CameraX id=${it.id} unavailable for selection; Camera2 report only")
                    }
                    val savedId = preferences.text("camera")
                    selectedId = savedId.takeIf { it in ids } ?: available.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                        ?.let { Camera2CameraInfo.from(it).cameraId } ?: ids.firstOrNull()
                    if (savedId.isNotEmpty() && savedId != selectedId) log.write("CAMERA_FALLBACK requested=$savedId unavailable; applied=$selectedId; saved request retained")
                    format = preferences.text("format/$selectedId", ImageCapture.OUTPUT_FORMAT_JPEG.toString()).toIntOrNull() ?: ImageCapture.OUTPUT_FORMAT_JPEG
                    bind()
                } catch (e: Exception) { error("initialize", e) }
            }, main)
        } catch (e: Exception) { error("capability enumeration", e) }
    }

    fun selectCamera(id: String) {
        if (!busy && !recordingBusy) {
            selectedId = id; preferences.put("camera", id)
            format = preferences.text("format/$id", ImageCapture.OUTPUT_FORMAT_JPEG.toString()).toIntOrNull() ?: ImageCapture.OUTPUT_FORMAT_JPEG
            bind()
        }
    }
    private fun exposureKey() = "exposure/$selectedId/${mode.name}"
    fun editIso(value: String) { requestedIso = value; preferences.put(exposureKey() + "/iso", value) }
    fun editMs(value: String) { requestedMs = value; preferences.put(exposureKey() + "/ms", value) }
    fun selectMode(value: CaptureMode) {
        if (busy || recordingBusy || mode == value) return
        mode = value; preferences.put("mode", value.name); bind()
    }
    fun selectVideo(key: String) {
        if (busy || recordingBusy || mode != CaptureMode.CINE || videoChoices.none { it.key == key }) return
        preferences.put("video/$selectedId", key); bind()
    }
    fun setAudio(value: Boolean) { if (!recordingBusy) { audioWanted = value; preferences.put("audio", value) } }
    fun chooseHighResolution(value: Boolean) {
        if (busy || recordingBusy) return
        highResolution = value; preferences.put("highResolution", value); if (mode == CaptureMode.STILL) bind()
    }
    fun startRecording() {
        if (mode != CaptureMode.CINE || !bound || !controlReady || busy || recordingBusy) return
        val session = cineSession ?: return
        if (ProbeControls.shouldStopRecording(thermalCode)) {
            event("Recording blocked by OS thermal ${ProbeControls.thermalName(thermalCode)}; wait for cooling")
            return
        }
        if (!owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            event("Recording requires a visible activity; retry Record after resume"); return
        }
        cine.start(session.recorder, audioWanted, "${session.choice.label} exposure=[$desired] overrides=[$overrides] requestedLocks=[$autoLocks]")
    }
    fun stopRecording() { cine.stop("user") }
    fun reportAudioPermission(granted: Boolean) {
        event("MICROPHONE permission=$granted${if (granted) "" else "; recording not started; grant permission or explicitly select audio off"}")
    }
    fun onBackground() { cine.stop("activity background; no background recording") }
    private fun onCineChanged() {
        if (!closed && pendingStop && !recordingBusy) { pendingStop = false; stop() }
    }

    fun focusCenter() {
        val previewView = view ?: return
        focusAt(previewView.width / 2f, previewView.height / 2f, "center")
    }

    fun focusAt(x: Float, y: Float, source: String = "tap") {
        if (busy || recordingBusy || !bound || !controlReady) return
        val c = camera ?: return
        val previewView = view ?: return
        try {
            val requestedPoint = ProbeControls.normalizedPreviewPoint(x, y, previewView.width, previewView.height)
            check(previewView.previewStreamState.value == PreviewView.StreamState.STREAMING) { "Preview not streaming; wait for the visible image before metering" }
            var flags = if (selectedReport?.autoFocusAvailable == true && overrides.focusDistance == null) FocusMeteringAction.FLAG_AF else 0
            if (!manualExposure) flags = flags or FocusMeteringAction.FLAG_AE
            check(flags != 0) { "AF unavailable or MF selected, and AE is manual; use AF/default or AUTO reset" }
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, flags).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
            // Public support check validates the full-sensor point, not every displayed-image/letterbox boundary.
            check(c.cameraInfo.isFocusMeteringSupported(action)) { "CameraX cannot meter this point: unsupported/invalid point or preview transform not ready" }
            val generation = epoch
            val includesAf = flags and FocusMeteringAction.FLAG_AF != 0
            val kinds = if (includesAf) { if (flags and FocusMeteringAction.FLAG_AE != 0) "AF+AE" else "AF only" } else "AE only"
            val target = "$source requested=$kinds at viewNormalized=$requestedPoint (not sensor coordinates)"
            busy = true; focusStatus = "$target PENDING"
            log.write("FOCUS_DESIRED $target flags=$flags; actual sensor mapping is observed separately in request/result regions")
            val future = c.cameraControl.startFocusAndMetering(action)
            future.addListener({
                if (generation != epoch || closed) return@addListener
                busy = false
                try {
                    val result = future.get()
                    focusStatus = if (includesAf) "$target AF API result=${result.isFocusSuccessful}; supported portions only, inspect actual regions/quality"
                        else "$target AE metering API completed; no AF requested; inspect actual region"
                    event("FOCUS $focusStatus")
                } catch (failure: Exception) { focusStatus = "Focus request failed"; error("focus", failure) }
            }, main)
        } catch (failure: Exception) {
            busy = false; focusStatus = "Focus/metering request rejected: ${failure.message}"
            error("focus validation", failure)
        }
    }

    fun compensateEv(delta: Int) {
        if (busy || recordingBusy || !bound || !controlReady) return
        val c = camera ?: return
        try {
            val state = c.cameraInfo.exposureState
            val range = state.exposureCompensationRange
            val target = ProbeControls.evTarget(state.exposureCompensationIndex, delta, range.lower..range.upper,
                state.isExposureCompensationSupported, !manualExposure && controlReady)
            val generation = epoch
            beginControl(acceptedControls.copy(evIndex = target))
            applyEv(c, generation, exposureKey(), target, persist = true) { busy = false; controlReady = true; acceptedControl() }
        } catch (failure: Exception) { error("EV validation", failure) }
    }

    private fun applyEv(c: Camera, generation: Int, key: String, requested: Int, persist: Boolean, accepted: () -> Unit) {
        try {
            val state = c.cameraInfo.exposureState
            if (!state.isExposureCompensationSupported) {
                acceptedEv = null
                evStatus = "CameraX EV unsupported; requested index=$requested not applied"
                log.write("EV_UNSUPPORTED $evStatus")
                accepted(); return
            }
            val range = state.exposureCompensationRange
            val target = if (range.contains(requested)) requested else 0
            require(range.contains(target)) { "No neutral EV fallback available in $range" }
            val fallback = if (requested != target) "; request outside current $range, neutral fallback, saved request retained" else ""
            evStatus = "Requested index=$requested step=${state.exposureCompensationStep}; target=$target PENDING$fallback"
            busy = true; controlReady = false
            val future = c.cameraControl.setExposureCompensationIndex(target)
            future.addListener({
                if (generation != epoch || closed) return@addListener
                try {
                    val value = future.get()
                    acceptedEv = value
                    if (persist) preferences.put("$key/ev", requested.toString())
                    evStatus = "Requested index=$requested, CameraX accepted=$value step=${state.exposureCompensationStep}$fallback; check actual CaptureResult"
                    log.write("EV $evStatus")
                    accepted()
                } catch (failure: Exception) {
                    busy = false; controlReady = false; lockStatus = "State unknown after EV/control failure; use AUTO reset"
                    error("EV control", failure)
                }
            }, main)
        } catch (failure: Exception) {
            busy = false; controlReady = false; lockStatus = "State unknown after EV/control failure; use AUTO reset"
            error("EV control", failure)
        }
    }
    fun selectFormat(value: Int) {
        if (busy || recordingBusy || mode != CaptureMode.STILL) return
        if (value !in formats) { event("UNSUPPORTED ${formatName(value)} by CameraX id=$selectedId"); return }
        format = value; preferences.put("format/$selectedId", value.toString()); bind()
    }

    fun editFocus(value: String) { requestedFocus = value; preferences.put(exposureKey() + "/focusDraft", value) }
    fun selectWhiteBalance(value: Int?) = changeOverrides(overrides.copy(whiteBalance = value), OverrideField.WHITE_BALANCE)
    fun selectAntiBanding(value: Int?) = changeOverrides(overrides.copy(antiBanding = value), OverrideField.ANTI_BANDING)
    fun restoreAutofocus() = changeOverrides(overrides.copy(focusDistance = null), OverrideField.FOCUS)
    fun applyManualFocus() {
        try { changeOverrides(overrides.copy(focusDistance = requestedFocus.trim().toFloat()), OverrideField.FOCUS) }
        catch (failure: Exception) { error("manual focus input", failure) }
    }

    private fun saveOverrides(key: String, value: SensorOverrides) {
        OverrideField.entries.forEach { field ->
            value.persistencePatch(field).forEach { (name, saved) -> preferences.put("$key/$name", saved) }
        }
    }

    private fun savedOverrides(key: String): SensorOverrides {
        fun integer(name: String): Int? {
            val text = preferences.text("$key/$name")
            return text.toIntOrNull().also { if (text.isNotBlank() && it == null) log.write("OVERRIDE_FALLBACK invalid saved $name; CameraX default, request retained") }
        }
        val focusText = preferences.text("$key/focus")
        val focus = focusText.toFloatOrNull()
        if (focusText.isNotBlank() && focus == null) log.write("OVERRIDE_FALLBACK invalid saved focus; CameraX default, request retained")
        return SensorOverrides(integer("wb"), focus, integer("antiBanding"))
            .supportedFallback(selectedReport?.overrides ?: OverrideCapabilities()) { log.write("OVERRIDE_FALLBACK $it") }
    }

    private fun changeOverrides(candidate: SensorOverrides, edited: OverrideField) {
        if (busy || recordingBusy || !bound || !controlReady) return
        val c = camera ?: return
        var started = false
        try {
            candidate.validate(selectedReport?.overrides ?: OverrideCapabilities())
            val targetLocks = autoLocks.forControls(manualExposure, candidate.whiteBalance)
            val generation = epoch
            val key = exposureKey()
            beginControl(acceptedControls.copy(overrides = candidate, locks = targetLocks))
            busy = true; controlReady = false; started = true
            overrideStatus = "Requested=$candidate PENDING; no quality claim"
            requestSnapshot(c, generation, appliedExposure, candidate, targetLocks, clearFirst = false,
                cancelFocus = candidate.focusDistance != overrides.focusDistance,
                accepted = {
                    overrides = candidate
                    acceptLocks(targetLocks, "Fixed WB releases AWB lock; other eligible locks retained")
                    candidate.persistencePatch(edited).forEach { (name, saved) -> preferences.put("$key/$name", saved) }
                    busy = false; controlReady = true
                    overrideStatus = "Accepted=$candidate; inspect CaptureResult. AE anti-banding has no effect in MANUAL exposure."
                    acceptedControl()
                    event("OVERRIDES $overrideStatus")
                }, failed = { failure ->
                    busy = false; controlReady = false
                    overrideStatus = "FAILED; sensor state unknown, use AUTO reset or restart"
                    lockStatus = "State unknown after control failure; use AUTO reset"
                    error("control snapshot", failure)
                })
        } catch (failure: Exception) {
            if (started) { busy = false; controlReady = false; overrideStatus = "FAILED; use AUTO reset or restart"; lockStatus = "State unknown; use AUTO reset" }
            error("override validation/control", failure)
        }
    }

    fun toggleExposureLock() = setLocks(autoLocks.copy(exposure = !autoLocks.exposure))
    fun toggleWhiteBalanceLock() = setLocks(autoLocks.copy(whiteBalance = !autoLocks.whiteBalance))

    private fun acceptLocks(value: AutoLocks, reason: String) {
        autoLocks = value
        lockStatus = "Requested=$value ACCEPTED; $reason; not persisted, inspect actual CaptureResult"
        log.write("AUTO_LOCKS $lockStatus")
    }

    private fun setLocks(candidate: AutoLocks) {
        if (busy || recordingBusy || !bound || !controlReady) return
        val c = camera ?: return
        var started = false
        try {
            candidate.validate(selectedReport?.locks ?: LockCapabilities(), manualExposure, overrides.whiteBalance)
            val generation = epoch
            beginControl(acceptedControls.copy(locks = candidate))
            busy = true; controlReady = false; started = true
            lockStatus = "Requested=$candidate PENDING; current binding only"
            log.write("AUTO_LOCKS $lockStatus")
            requestSnapshot(c, generation, appliedExposure, overrides, candidate, clearFirst = false, cancelFocus = false,
                accepted = { acceptLocks(candidate, "User lock/unlock"); busy = false; controlReady = true; acceptedControl() },
                failed = { failure ->
                    busy = false; controlReady = false; lockStatus = "FAILED; sensor state unknown, use AUTO reset"
                    error("AE/AWB lock", failure)
                })
        } catch (failure: Exception) {
            if (started) { busy = false; controlReady = false; lockStatus = "FAILED; use AUTO reset" }
            error("AE/AWB lock validation/control", failure)
        }
    }

    /** One replacement snapshot prevents independent controls from silently erasing each other. */
    private fun requestSnapshot(c: Camera, generation: Int, exposure: ManualExposure?, extras: SensorOverrides,
        locks: AutoLocks, clearFirst: Boolean, cancelFocus: Boolean, accepted: () -> Unit, failed: (Exception) -> Unit) {
        val control = Camera2CameraControl.from(c.cameraControl)
        val builder = CaptureRequestOptions.Builder()
        exposure?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it.iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it.exposureNs)
        }
        extras.whiteBalance?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, it) }
        if (locks.exposure) builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        if (locks.whiteBalance) builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        extras.antiBanding?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }
        extras.focusDistance?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it)
        }
        fun await(future: com.google.common.util.concurrent.ListenableFuture<*>, next: () -> Unit) {
            future.addListener({
                if (generation != epoch || closed) return@addListener
                try { future.get(); next() } catch (failure: Exception) { failed(failure) }
            }, main)
        }
        fun apply() {
            if (exposure == null && extras.isEmpty && locks.isEmpty) {
                if (clearFirst) accepted() else await(control.clearCaptureRequestOptions(), accepted)
            } else await(control.setCaptureRequestOptions(builder.build()), accepted)
        }
        fun clearAndApply() {
            if (clearFirst) await(control.clearCaptureRequestOptions(), ::apply) else apply()
        }
        if (cancelFocus) await(c.cameraControl.cancelFocusAndMetering(), ::clearAndApply) else clearAndApply()
    }

    fun bind() {
        if (busy || recordingBusy || closed) return
        val p = provider ?: return
        val id = selectedId ?: run { event("UNSUPPORTED no CameraX camera"); return }
        val previewView = view ?: return
        val generation = ++epoch
        sample = null; acceptedEv = null; photoStage = PhotoStage.IDLE
        controlReceipt = ControlReceipt(epoch = epoch, sequence = controlReceipt.sequence)
        val settingsKey = exposureKey()
        requestedIso = preferences.text("$settingsKey/iso", "100")
        requestedMs = preferences.text("$settingsKey/ms", "10")
        requestedFocus = preferences.text("$settingsKey/focusDraft", "0")
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        bound = false; controlReady = false; formats = emptySet()
        videoSelection = "No applied video profile for this session; requested preference retained"
        stillResolution = "No applied image resolution for this session; requested preference retained"
        manualExposure = false; evStatus = "No accepted EV for this session"; focusStatus = "No focus request for this session"
        overrides = SensorOverrides(); appliedExposure = null; overrideStatus = "No accepted overrides for this session"
        autoLocks = AutoLocks(); lockStatus = "New binding; lock requests cleared and not restored"
        camera = null; imageCapture = null; cineSession = null; videoChoices = emptyList()
        desired = "AUTO reset required"; measured = "Awaiting new session result"
        try {
            p.unbindAll()
            val info = p.availableCameraInfos.firstOrNull { Camera2CameraInfo.from(it).cameraId == id }
                ?: run { event("UNSUPPORTED CameraX unavailable id=$id; Camera2 report only"); return }
            formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            // Never construct RAW ImageCapture until CameraX has advertised that exact output format.
            if (mode == CaptureMode.STILL) require(format in formats) { "CameraX ${formatName(format)} unsupported id=$id" }
            val selector = CameraSelector.Builder().addCameraFilter { list ->
                list.filter { Camera2CameraInfo.from(it).cameraId == id }
            }.build()
            var lastSample = 0L
            val builder = Preview.Builder()
            val resultCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    // Observe only: never operate CameraX's CameraDevice / session.
                    val still = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                    val now = SystemClock.elapsedRealtime()
                    if (!still && now - lastSample < 1000) return
                    lastSample = now
                    val periodRate = Exposure.sensorPeriodFps(result.get(CaptureResult.SENSOR_FRAME_DURATION))
                        ?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "unknown"
                    val locks = "requestAELock=${request.get(CaptureRequest.CONTROL_AE_LOCK)} actualAELock=${result.get(CaptureResult.CONTROL_AE_LOCK)} aeState=${result.get(CaptureResult.CONTROL_AE_STATE)} requestAWBLock=${request.get(CaptureRequest.CONTROL_AWB_LOCK)} actualAWBLock=${result.get(CaptureResult.CONTROL_AWB_LOCK)}"
                    val regions = "requestAFRegions=${request.get(CaptureRequest.CONTROL_AF_REGIONS)?.contentToString()} actualAFRegions=${result.get(CaptureResult.CONTROL_AF_REGIONS)?.contentToString()} requestAERegions=${request.get(CaptureRequest.CONTROL_AE_REGIONS)?.contentToString()} actualAERegions=${result.get(CaptureResult.CONTROL_AE_REGIONS)?.contentToString()}"
                    val actual = "sensorPeriodFps=$periodRate (period sample; not preview/encoded FPS) $locks $regions frame=${result.frameNumber} sensorTimestamp=${result.get(CaptureResult.SENSOR_TIMESTAMP)} intent=${request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)} requestAE=${request.get(CaptureRequest.CONTROL_AE_MODE)} requestISO=${request.get(CaptureRequest.SENSOR_SENSITIVITY)} requestNs=${request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)} actualAE=${result.get(CaptureResult.CONTROL_AE_MODE)} actualISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)} actualNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} frameDurationNs=${result.get(CaptureResult.SENSOR_FRAME_DURATION)} requestAF=${request.get(CaptureRequest.CONTROL_AF_MODE)} actualAF=${result.get(CaptureResult.CONTROL_AF_MODE)} afState=${result.get(CaptureResult.CONTROL_AF_STATE)} requestFocusLensUnits=${request.get(CaptureRequest.LENS_FOCUS_DISTANCE)} actualFocusLensUnits=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} requestAWB=${request.get(CaptureRequest.CONTROL_AWB_MODE)} actualAWB=${result.get(CaptureResult.CONTROL_AWB_MODE)} awbState=${result.get(CaptureResult.CONTROL_AWB_STATE)} requestEV=${request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)} actualEV=${result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)} requestAntiBanding=${request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE)} actualAntiBanding=${result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE)} sceneFlicker=${result.get(CaptureResult.STATISTICS_SCENE_FLICKER)}"
                    main.execute {
                        if (generation == epoch && !closed) {
                            measured = actual
                            val observation = CaptureSample(generation, result.frameNumber, now,
                                SensorValues(request.get(CaptureRequest.SENSOR_SENSITIVITY), request.get(CaptureRequest.SENSOR_EXPOSURE_TIME), request.get(CaptureRequest.CONTROL_AE_MODE),
                                    request.get(CaptureRequest.LENS_FOCUS_DISTANCE), request.get(CaptureRequest.CONTROL_AF_MODE), request.get(CaptureRequest.CONTROL_AWB_MODE),
                                    request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION), request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE),
                                    request.get(CaptureRequest.CONTROL_AE_LOCK), request.get(CaptureRequest.CONTROL_AWB_LOCK)),
                                SensorValues(result.get(CaptureResult.SENSOR_SENSITIVITY), result.get(CaptureResult.SENSOR_EXPOSURE_TIME), result.get(CaptureResult.CONTROL_AE_MODE),
                                    result.get(CaptureResult.LENS_FOCUS_DISTANCE), result.get(CaptureResult.CONTROL_AF_MODE), result.get(CaptureResult.CONTROL_AWB_MODE),
                                    result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION), result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                                    result.get(CaptureResult.CONTROL_AE_LOCK), result.get(CaptureResult.CONTROL_AWB_LOCK)))
                            sample = newerSample(sample, observation, epoch)
                            log.write("RESULT id=$id desired=[$desired] $actual")
                        }
                    }
                }
            }
            Camera2Interop.Extender(builder).setSessionCaptureCallback(resultCallback)
            val preview = builder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            if (mode == CaptureMode.STILL) {
                val captureBuilder = ImageCapture.Builder().setOutputFormat(format)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                if (highResolution) {
                    val active = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    val ratio = active?.let { it.width().toDouble() / it.height() }
                    val preferredRatio = if (ratio != null && kotlin.math.abs(ratio - 16.0 / 9) < kotlin.math.abs(ratio - 4.0 / 3)) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
                    captureBuilder.setResolutionSelector(ResolutionSelector.Builder()
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        .setAspectRatioStrategy(AspectRatioStrategy(preferredRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build())
                }
                Camera2Interop.Extender(captureBuilder).setSessionCaptureCallback(resultCallback)
                var capture = captureBuilder.build()
                var fallbackReason: String? = null
                camera = try { p.bindToLifecycle(owner, selector, preview, capture) }
                catch (failure: Exception) {
                    if (!highResolution) throw failure
                    fallbackReason = "Highest-resolution bind failed: ${failure.message}; CameraX default requested instead"
                    log.write("STILL_RESOLUTION_FALLBACK $fallbackReason")
                    p.unbindAll()
                    val fallbackBuilder = ImageCapture.Builder().setOutputFormat(format)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    Camera2Interop.Extender(fallbackBuilder).setSessionCaptureCallback(resultCallback)
                    capture = fallbackBuilder.build()
                    p.bindToLifecycle(owner, selector, preview, capture)
                }
                imageCapture = capture
                stillResolution = "Requested=${if (highResolution) "highest API available, sensor aspect preferred" else "CameraX default"}; bound=${capture.resolutionInfo?.resolution ?: "pending"}; 48MP not guaranteed. ${fallbackReason ?: "No explicit fallback"}"
                log.write("STILL_RESOLUTION $stillResolution")
            } else {
                val prepared = CineSessions.query(info, preview, log::write)
                videoChoices = prepared.map { it.choice }.distinctBy { it.key }
                val requested = preferences.text("video/$id").ifBlank { null }
                val ordered = VideoPolicy.ordered(videoChoices, requested)
                var lastFailure: Exception? = null
                val bindFailures = mutableListOf<String>()
                for (choice in ordered) {
                    val option = prepared.first { it.choice.key == choice.key }
                    try {
                        p.unbindAll()
                        camera = p.bindToLifecycle(owner, selector, option.session)
                        cineSession = option
                        val hdr = if (videoChoices.any { it.dynamicRange != "SDR" }) "HDR choices listed; device validation pending" else "No usable HDR combination advertised by CameraX"
                        videoSelection = "Requested=${requested ?: "highest supported SDR"}; applied=${choice.label}; bound=${option.video.resolutionInfo?.resolution ?: "pending"}. ${VideoPolicy.fallbackReason(requested, choice.key)}. $hdr. ${bindFailures.joinToString()} Device validation pending."
                        log.write("VIDEO_SELECTION $videoSelection")
                        break
                    } catch (failure: Exception) {
                        lastFailure = failure
                        bindFailures += "${choice.label} failed: ${failure.message}"
                        log.write("VIDEO_BIND_FALLBACK requested=$requested candidate=${choice.key} ${failure.message}")
                    }
                }
                check(cineSession != null) { "No supported CINE combination: ${lastFailure?.message ?: "CameraX returned no combination-specific fps/session support"}" }
            }
            bound = true
            camera!!.cameraInfo.cameraState.observe(owner) { state ->
                if (generation == epoch && !closed) {
                    log.write("CAMERA_STATE id=$id type=${state.type} error=${state.error?.code}")
                    state.error?.let {
                        controlReady = false
                        controlReceipt = controlReceipt.copy(phase = ControlPhase.UNKNOWN, acceptedAtMs = null, detail = "CameraX camera error ${it.code}")
                        lastFailure = controlReceipt.detail
                        event("ERROR CameraX camera state code=${it.code}")
                    }
                }
            }
            event("BOUND id=$id mode=$mode format=${formatName(format)}; clearing Interop before capture")
            // The provider may reuse a CameraControl whose Interop options survive unbind/rebind.
            // Explicitly clear them and wait for acceptance before enabling capture.
            setExposure(remember = false, restoreSaved = true)
        } catch (e: Exception) { error("bind id=$id mode=$mode format=${formatName(format)} (see selection/fallback log)", e) }
    }

    fun stop() {
        if (recordingBusy) { pendingStop = true; cine.stop("preview Stop"); return }
        ++epoch
        sample = null; acceptedEv = null; photoStage = PhotoStage.IDLE
        controlReceipt = ControlReceipt(epoch = epoch, sequence = controlReceipt.sequence)
        busy = false
        controlReady = false
        activeCaptureToken = null
        videoSelection = "Unbound; no applied video profile"
        stillResolution = "Unbound; no applied image resolution"
        focusStatus = "Unbound; no active focus request"; evStatus = "Unbound; no accepted EV"; manualExposure = false
        overrides = SensorOverrides(); appliedExposure = null; overrideStatus = "Unbound; no accepted overrides"
        autoLocks = AutoLocks(); lockStatus = "Unbound; no lock request"
        try {
            camera?.cameraInfo?.cameraState?.removeObservers(owner)
            provider?.unbindAll(); camera = null; imageCapture = null; cineSession = null; bound = false
            desired = "AUTO (unbound)"; event("Preview stopped")
        } catch (e: Exception) { error("stop", e) }
    }

    fun setExposureAuto() = setExposure()
    fun resetAllControls() = setExposure(reset = true)
    fun setExposure(iso: String? = null, ms: String? = null, remember: Boolean = true, restoreSaved: Boolean = false, reset: Boolean = false) {
        if (busy || recordingBusy) return
        val c = camera ?: return
        val manual = iso != null && ms != null
        try {
            val r = selectedReport
            val value = if (manual) {
                require(r != null) { "No Camera2 report" }
                require(r.manualBlockReason == null) { r.manualBlockReason!! }
                Exposure.parse(iso, ms, r.isoRange, r.exposureRange).also {
                    if (mode == CaptureMode.CINE) {
                        val maxFps = cineSession?.choice?.fpsUpper ?: error("Video fps unavailable")
                        require(it.exposureNs <= 1_000_000_000L / maxFps) { "Exposure exceeds requested video frame period at $maxFps fps; lower fps or shorten exposure" }
                    }
                }
            } else null
            val label = value?.let { "MANUAL ISO=${it.iso} exposureNs=${it.exposureNs}" }
                ?: if (restoreSaved) "AUTO exposure (clear then restore supported mode controls)" else if (reset) "AUTO reset (all Interop controls cleared)" else "AUTO exposure only (independent controls retained)"
            desired = "$label PENDING"
            log.write("DESIRED id=$selectedId $desired")
            val generation = epoch
            val key = exposureKey()
            val auto = autoControlSnapshot(acceptedControls, preferences.text("$key/ev", "0").toIntOrNull() ?: 0, reset)
            val extras = if (restoreSaved) savedOverrides(key) else if (manual) overrides else auto.overrides
            val targetLocks = if (manual) autoLocks.forControls(true, extras.whiteBalance) else if (restoreSaved) AutoLocks() else auto.locks
            beginControl(ControlSnapshot(value, if (manual) acceptedEv else auto.evIndex, extras, targetLocks))
            busy = true; controlReady = false
            requestSnapshot(c, generation, value, extras, targetLocks, clearFirst = reset || restoreSaved, cancelFocus = reset || restoreSaved,
                accepted = {
                    val finish = {
                        busy = false; controlReady = true; manualExposure = manual
                        appliedExposure = value; overrides = extras
                        acceptLocks(targetLocks, if (manual) "MANUAL exposure releases AE lock" else "AUTO/reset/bind clears lock requests")
                        overrideStatus = "Accepted=$extras; check actual result; anti-banding affects AUTO AE only"
                        desired = "$label ACCEPTED (check actual result)"; acceptedControl(); event(desired)
                        if (remember) {
                            preferences.saveExposure(key, if (manual) iso else null, if (manual) ms else null)
                            if (!manual && reset) { saveOverrides(key, SensorOverrides()); preferences.put("$key/ev", "0") }
                        }
                        if (manual) evStatus = "EV inactive while MANUAL exposure is requested"
                        if (restoreSaved && preferences.flag("$key/manual")) {
                            try {
                                val saved = Exposure.savedManual(preferences.text("$key/appliedIso"), preferences.text("$key/appliedMs"),
                                    r?.isoRange, r?.exposureRange)
                                setExposure(saved.iso, saved.milliseconds, remember = false)
                            } catch (failure: Exception) {
                                controlReady = false
                                desired = "Saved MANUAL not applied; saved request retained; use AUTO reset or apply valid values"
                                error("saved exposure restoration (input drafts are not applied)", failure)
                            }
                        }
                    }
                    if (manual) finish() else {
                        val ev = if (reset || (restoreSaved && preferences.flag("$key/manual"))) 0 else auto.evIndex ?: 0
                        applyEv(c, generation, key, ev, persist = false, accepted = finish)
                    }
                }, failed = { e ->
                    busy = false
                    desired = "$label FAILED; sensor state unknown; use AUTO or stop/restart"
                    lockStatus = "State unknown after exposure failure; use AUTO reset"
                    error("exposure", e)
                })
        } catch (e: Exception) {
            if (!remember && manual) { controlReady = false; desired = "Saved MANUAL not applied; use AUTO or valid values" }
            if (busy) { desired = "Exposure control FAILED; sensor state unknown; use AUTO or stop/restart"; lockStatus = "State unknown; use AUTO reset" }
            busy = false; error("exposure validation/control", e)
        }
    }

    fun capture() {
        if (busy || recordingBusy || mode != CaptureMode.STILL) return
        if (!sharedSaveSupported) { event("UNSUPPORTED shared image saving requires Android 10+"); return }
        if (!controlReady) { event("Capture disabled: exposure control not accepted; use AUTO or stop/restart"); return }
        val capture = imageCapture ?: return
        val token = UUID.randomUUID().toString()
        val appCaptureStart = SystemClock.elapsedRealtime()
        val generation = epoch
        val cameraId = selectedId
        val captureFormat = format
        busy = true; activeCaptureToken = token; photoStage = PhotoStage.CAPTURING
        lastFailure = null
        var staging = emptyList<File>()
        fun isCurrent() = !closed && generation == epoch && activeCaptureToken == token
        fun clean(files: List<File>) {
            files.forEach { file ->
                try {
                    if (file.exists() && !file.delete()) log.write("WARN staging cleanup failed token=$token")
                } catch (e: Exception) { log.write("WARN staging cleanup token=$token ${e.javaClass.simpleName}") }
            }
        }
        fun failed(e: Exception) {
            log.write("CAPTURE_TIMING token=$token failureAfterAppRequestMs=${SystemClock.elapsedRealtime() - appCaptureStart}; not sensor shutter lag")
            if (isCurrent()) {
                busy = false; activeCaptureToken = null; photoStage = PhotoStage.FAILED
                error("capture/shared save token=$token", e)
            } else log.write("CAPTURE_LATE_ERROR token=$token ${e.javaClass.simpleName}: ${e.message}")
        }
        try {
            val dir = File(context.cacheDir, "capture-staging")
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create capture staging directory" }
            val raw = File(dir, "$token.dng")
            val jpeg = File(dir, "$token.jpg")
            val sources = when (captureFormat) {
                ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> listOf(PhotoSource(raw, PhotoFormat.DNG), PhotoSource(jpeg, PhotoFormat.JPEG))
                ImageCapture.OUTPUT_FORMAT_RAW -> listOf(PhotoSource(raw, PhotoFormat.DNG))
                else -> listOf(PhotoSource(jpeg, PhotoFormat.JPEG))
            }
            staging = sources.map { it.file }
            val expectedFormats = sources.map { if (it.format == PhotoFormat.DNG) ImageFormat.RAW_SENSOR else ImageFormat.JPEG }.toSet()
            val received = mutableSetOf<Int>()
            var terminal = false
            var publishing = false
            busy = true; activeCaptureToken = token
            log.write("CAPTURE_BEGIN token=$token id=$cameraId format=${formatName(captureFormat)} desired=[$desired] selectedResolution=${capture.resolutionInfo?.resolution} target=${PhotoPublisher.RELATIVE_PATH}")
            val callback = object : ImageCapture.OnImageSavedCallback {
                private fun fail(e: Exception) {
                    if (terminal) { log.write("CAPTURE_LATE_CALLBACK_ERROR token=$token afterAppRequestMs=${SystemClock.elapsedRealtime() - appCaptureStart} ${e.javaClass.simpleName}"); return }
                    terminal = true
                    mediaIo.execute { clean(staging) }
                    failed(e)
                }
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    try {
                        if (terminal) {
                            log.write("CAPTURE_IGNORED_FILE_CALLBACK token=$token imageFormat=${result.imageFormat} afterAppRequestMs=${SystemClock.elapsedRealtime() - appCaptureStart}; terminal already handled")
                            if (!publishing) mediaIo.execute { clean(staging) }
                            return
                        }
                        check(result.imageFormat in expectedFormats) { "Unexpected camera output format ${result.imageFormat}" }
                        if (!received.add(result.imageFormat)) return
                        log.write("CAPTURE_FILE_CALLBACK token=$token imageFormat=${result.imageFormat} afterAppRequestMs=${SystemClock.elapsedRealtime() - appCaptureStart}; includes camera/file/callback delivery, not shutter lag")
                        if (received != expectedFormats) return
                        terminal = true; publishing = true
                        if (isCurrent()) photoStage = PhotoStage.SAVING
                        val allCallbacksAt = SystemClock.elapsedRealtime()
                        // CameraX has finished every file. Publish together off the UI thread.
                        mediaIo.execute {
                            try {
                                val mediaWorkStart = SystemClock.elapsedRealtime()
                                sources.forEach { source ->
                                    try {
                                        if (source.format == PhotoFormat.JPEG) {
                                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                            android.graphics.BitmapFactory.decodeFile(source.file.absolutePath, bounds)
                                            log.write("IMAGE_METADATA token=$token mime=image/jpeg encodedSize=${bounds.outWidth}x${bounds.outHeight}; viewerValidation=UNVERIFIED")
                                        } else {
                                            val exif = android.media.ExifInterface(source.file)
                                            log.write("IMAGE_METADATA token=$token mime=image/x-adobe-dng exifWidth=${exif.getAttribute(android.media.ExifInterface.TAG_IMAGE_WIDTH)} exifHeight=${exif.getAttribute(android.media.ExifInterface.TAG_IMAGE_LENGTH)}; notFullRawDecodeProof")
                                        }
                                    } catch (metadataFailure: Exception) {
                                        log.write("IMAGE_METADATA_UNAVAILABLE token=$token ${metadataFailure.message}; source retained for publication")
                                    }
                                }
                                val photos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    PhotoPublisher(AndroidImageStore(context.applicationContext.contentResolver)).publish(sources)
                                } else throw IllegalStateException("MediaStore saving requires Android 10+")
                                val publishedAt = SystemClock.elapsedRealtime()
                                main.execute {
                                    log.write("CAPTURE_TIMING token=$token allFileCallbacksAfterAppRequestMs=${allCallbacksAt - appCaptureStart} ioQueueMs=${mediaWorkStart - allCallbacksAt} metadataAndPublishWorkMs=${publishedAt - mediaWorkStart} appRequestToPublishedMs=${publishedAt - appCaptureStart} publishToUiNotificationMs=${SystemClock.elapsedRealtime() - publishedAt}; app stages only, not sensor shutter lag")
                                    val current = isCurrent()
                                    if (current) { busy = false; activeCaptureToken = null; photoStage = PhotoStage.SAVED }
                                    photos.forEach { photo ->
                                        val message = "${if (current) "SAVED" else "CAPTURE_LATE_SAVED"} token=$token mime=${photo.format.mimeType} uri=${photo.uri} name=${photo.name} bytes=${photo.bytes} folder=${PhotoPublisher.RELATIVE_PATH} viewerValidity=UNVERIFIED"
                                        if (current) event(message) else log.write(message)
                                    }
                                    log.write("CAPTURE_COMPLETE token=$token files=${photos.joinToString { it.name }}")
                                }
                            } catch (e: Exception) { main.execute { failed(e) } }
                            finally { clean(staging) }
                        }
                    } catch (e: Exception) { fail(e) }
                }
                override fun onError(exception: ImageCaptureException) { fail(exception) }
            }
            fun options(file: File) = ImageCapture.OutputFileOptions.Builder(file).build()
            when (captureFormat) {
                ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> capture.takePicture(options(raw), options(jpeg), main, callback)
                ImageCapture.OUTPUT_FORMAT_RAW -> capture.takePicture(options(raw), main, callback)
                else -> capture.takePicture(options(jpeg), main, callback)
            }
        } catch (e: Exception) {
            mediaIo.execute { clean(staging) }
            failed(e)
        }
    }

    fun close() {
        closed = true; controlReady = false; activeCaptureToken = null; ++epoch
        cine.stop("activity destroyed")
        try { removeThermalListener?.invoke(); removeThermalListener = null }
        catch (failure: Exception) { log.write("THERMAL_REMOVE_ERROR ${failure.message}") }
        try { camera?.cameraInfo?.cameraState?.removeObservers(owner); provider?.unbindAll() }
        catch (e: Exception) { log.write("ERROR close ${e.javaClass.simpleName}: ${e.message}") }
    }

    companion object {
        // Process-scoped IO: late CameraX callbacks remain valid after an Activity closes.
        // No Activity or ContentResolver is retained by the executor itself.
        private val mediaIo = Executors.newSingleThreadExecutor { work ->
            Thread(work, "p0-media-save").apply { isDaemon = true }
        }
        fun formatName(value: Int): String = when (value) {
            ImageCapture.OUTPUT_FORMAT_JPEG -> "JPEG"
            ImageCapture.OUTPUT_FORMAT_RAW -> "RAW"
            ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> "RAW+JPEG"
            else -> "OTHER($value)"
        }
    }
}
