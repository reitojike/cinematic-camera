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
import java.util.concurrent.atomic.AtomicBoolean
import dev.cinematic.p0.rawcapture.AndroidRawCaptureQueue
import dev.cinematic.p0.rawcapture.RawCaptureNotice
import dev.cinematic.p0.rawcapture.RawCaptureObserver
import dev.cinematic.p0.rawcapture.RawCaptureTicket
import dev.cinematic.p0.rawcapture.RawOutput
import dev.cinematic.p0.rawcapture.DngPublicationPhase
import dev.cinematic.p0.rawcapture.RawCapturePresentation
import dev.cinematic.p0.photoroll.AndroidPhotoRollCameraObservations
import dev.cinematic.p0.photoroll.PhotoRollCameraSnapshot

@androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
class CameraProbe(private val context: Context, private val owner: LifecycleOwner) {
    private val main = ContextCompat.getMainExecutor(context)
    private val rawQueue = if (Build.VERSION.SDK_INT >= 29) AndroidRawCaptureQueue.get(context) else null
    private val rawCameraOwner = rawQueue?.newCameraOwner()
    private var activeRawTicket: RawCaptureTicket? = null
    private val rawPresentation = RawCapturePresentation()
    private var rawObserver: RawCaptureObserver? = null
    private val controlHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val controlOperation = ControlOperation()
    private val liveControls = LatestLiveControlQueue()
    var stillExposure by mutableStateOf<StillExposureState?>(null); private set
    private enum class RuntimeTarget { GENERAL, PREVIEW, CAPTURE }
    private var runtimeTarget = RuntimeTarget.GENERAL
    private var runtimeRevision = 0L
    private var targetRevision = 0L
    private var receiptSequence = 0L
    private var previewWait: Runnable? = null
    private var photoDeadline: Runnable? = null
    private var previewSeeded = false
    private var pendingForegroundBind = false
    private val pendingBindControls = PendingBindControls()
    private val stillResumeIntent = StillResumeIntent()
    private var lastPreviewMinFrameNs: Long? = null
    private val operationReceipt get() = if (runtimeTarget == RuntimeTarget.CAPTURE) stillExposure?.captureReceipt ?: controlReceipt else controlReceipt
    private fun operationReceipt(value: ControlReceipt) {
        if (runtimeTarget == RuntimeTarget.CAPTURE) stillExposure = stillExposure?.captureReceipt(value)
        else {
            controlReceipt = value
            if (runtimeTarget == RuntimeTarget.PREVIEW) stillExposure = stillExposure?.previewReceipt(runtimeRevision, value)
        }
    }
    private var controlTimeout: Runnable? = null
    private var exposureFlush: Runnable? = null
    private val exposureDurations = ExposureDurations()
    @Volatile private var sampleBurstUntilMs = 0L
    var exposureOperationPending by mutableStateOf(false); private set
    var exposureQueued by mutableStateOf(false); private set
    var liveExposureTarget by mutableStateOf<ManualExposure?>(null); private set
    var liveControlIntent by mutableStateOf<ControlSnapshot?>(null); private set
    private var liveIntentKey: String? = null
    val liveControlsPending get() = exposureOperationPending
    val exposureChoices get() = ExposureStops.choices(selectedReport?.isoRange, selectedReport?.exposureRange,
        selectedReport?.maxFrameDurationNs, if (mode == CaptureMode.CINE) activeVideoChoice?.fpsUpper else null)
    val canSelectLiveControls get() = !closed && bound && !recordingBusy && activeCaptureToken == null &&
        owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) &&
        stillExposure?.ownsCapture != true && stillExposure?.phase != StillExposurePhase.UNKNOWN &&
        controlReceipt.phase !in setOf(ControlPhase.UNKNOWN, ControlPhase.UNBOUND) &&
        (!busy || liveControlsPending) && (controlReady || liveControlsPending ||
        (stillExposure?.phase == StillExposurePhase.PREVIEW_APPLYING && controlReceipt.phase == ControlPhase.ACCEPTED))
    val canSelectExposure get() = canSelectLiveControls
    private val controlRecovery get() = ControlRecoveryState(epoch, bound,
        owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED), closed,
        busy, recordingBusy, activeCaptureToken != null, stillExposure?.ownsCapture == true,
        controlReceipt.phase, stillExposure?.phase)
    val canRecoverControls get() = controlRecovery.canReconnect(epoch)
    private val editControls get() = liveControlIntent ?: selectedControls

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
    var exposurePolicyStatus by mutableStateOf(NormalExposurePolicy.DESCRIPTION); private set
    var exposureMigrationNotice by mutableStateOf<String?>(null); private set
    fun consumeExposureMigrationNotice(): String? = exposureMigrationNotice.also { exposureMigrationNotice = null }
    var measured by mutableStateOf("No CaptureResult yet"); private set
    var bound by mutableStateOf(false); private set
    private var busyValue by mutableStateOf(false)
    var busy: Boolean
        get() = busyValue
        private set(value) {
            if (value) rawCameraOwner?.update(true)
            busyValue = value
            if (!value) refreshRawAdmission()
        }
    private var controlReadyValue by mutableStateOf(false)
    var controlReady: Boolean
        get() = controlReadyValue
        private set(value) {
            if (!value) rawCameraOwner?.update(true)
            controlReadyValue = value
            if (value) refreshRawAdmission()
        }
    var mode by mutableStateOf(preferences.mode()); private set
    var requestedIso by mutableStateOf("100"); private set
    var requestedMs by mutableStateOf("10"); private set
    var audioPermissionDenied by mutableStateOf(false); private set
    var audioWanted by mutableStateOf(preferences.flag("audio", true)); private set
    var highResolution by mutableStateOf(preferences.flag("highResolution", true)); private set
    var videoChoices by mutableStateOf(emptyList<VideoChoice>()); private set
    var videoSelection by mutableStateOf("Not selected; capabilities not queried"); private set
    var stillResolution by mutableStateOf("Highest available requested; actual resolution pending"); private set
    var manualExposure by mutableStateOf(false); private set
    var evStatus by mutableStateOf("No EV request"); private set
    var focusFeedback by mutableStateOf<FocusFeedback?>(null); private set
    var focusStatus by mutableStateOf("No focus/metering request"); private set
    var thermalStatus by mutableStateOf("OS thermal status unknown; not a temperature measurement"); private set
    var overrides by mutableStateOf(SensorOverrides()); private set
    var overrideStatus by mutableStateOf("CameraX defaults; no accepted overrides"); private set
    var autoLocks by mutableStateOf(AutoLocks()); private set
    var lockStatus by mutableStateOf("No requested locks; transient, not saved"); private set
    var exportingReport by mutableStateOf(false); private set
    var appliedExposure by mutableStateOf<ManualExposure?>(null); private set
    var acceptedEv by mutableStateOf<Int?>(null); private set
    var controlReceipt by mutableStateOf(ControlReceipt()); private set
    var sample by mutableStateOf<CaptureSample?>(null); private set
    var previewExposureSamples by mutableStateOf(PreviewExposureSamples()); private set
    private var autofocusObservations by mutableStateOf(AutofocusObservations())
    val previewFocusSample get() = autofocusObservations.sample
    var photoStage by mutableStateOf(PhotoStage.IDLE); private set
    var lastFailure by mutableStateOf<String?>(null); private set
    var thermalSeverity by mutableIntStateOf(-1); private set
    var outputOrientation by mutableStateOf(OutputOrientation()); private set
    val currentEpoch get() = epoch
    /** Last selected still target is distinct from the runtime preview API receipt. */
    val selectedControls get() = ControlSnapshot(appliedExposure, acceptedEv, overrides, autoLocks)
    private val runtimeExposure get() = stillExposure?.target?.plan?.preview ?: appliedExposure
    private val runtimeBoost get() = stillExposure?.target?.boostPercent
    private val runtimeControls get() = selectedControls.copy(exposure = runtimeExposure)
    val activeVideoChoice get() = cineSession?.choice
    val exposureCompensationStep get() = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toDouble()
    val exposureCompensationRange get() = camera?.cameraInfo?.exposureState?.exposureCompensationRange?.let { it.lower..it.upper }
    val exposureCompensationSupported get() = camera?.cameraInfo?.exposureState?.isExposureCompensationSupported == true
    private var thermalCode = -1
    private var removeThermalListener: (() -> Unit)? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var retainedInterop: Camera2CameraControl? = null
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
    val recordingFailure get() = cine.failure
    val recordingSaveSucceeded get() = cine.saveSucceeded
    val recordingStartSequence get() = cine.startSequence
    val recordingDurationMs get() = cine.durationMs
    val recordingAudioAmplitude get() = cine.audioAmplitude
    val recordingAudioState get() = cine.audioState
    private var activeCaptureToken: String? = null
    private var photoRollHostMayStart = false
    private var photoRollRebind: Boolean? = false
    private var photoRollProviderPending = false
    private var photoRollCloseAcknowledged = false
    private val photoRollCameraOwner = AndroidPhotoRollCameraObservations.register {
        PhotoRollCameraSnapshot(recordingBusy, photoRollRebind,
            photoRollHostMayStart || photoRollProviderPending,
            activeCaptureToken != null || activeRawTicket != null || stillExposure?.ownsCapture == true ||
                exposureOperationPending || controlOperation.token != null)
    }
    val sharedSaveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val selectedReport get() = reports.find { it.id == selectedId }
    init {
        log.onLine = { line -> main.execute { if (!closed) lines = (lines + "\n" + line).takeLast(16000) } }
        observeThermals()
    }

    fun setDeviceRotation(rotation: Int) {
        if (closed) return
        outputOrientation = outputOrientation.observe(rotation)
        applyOutputOrientation()
    }
    private fun applyOutputOrientation(): Boolean {
        if (closed) return false
        val target = outputOrientation.target(activeCaptureToken != null, recordingBusy) ?: return true
        if (imageCapture == null && cineSession == null) return false
        return try {
            // PreviewView owns preview orientation and metering coordinates. Rotate only output use cases.
            imageCapture?.targetRotation = target
            cineSession?.video?.targetRotation = target
            outputOrientation = outputOrientation.copy(applied = target)
            true
        } catch (failure: Exception) {
            lastFailure = "撮影の向きを適用できません: ${failure.message}"
            event("ERROR output rotation ${failure.javaClass.simpleName}: ${failure.message}")
            false
        }
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

    private fun refreshRawAdmission() {
        if (closed) {
            if (photoRollCloseAcknowledged && !recordingBusy) photoRollCameraOwner.close()
            else if (!photoRollCloseAcknowledged) photoRollCameraOwner.holdUnknown()
        }
        if (closed && !recordingBusy && !bound) rawCameraOwner?.close()
        else rawCameraOwner?.update(busy || recordingBusy || exposureOperationPending || activeCaptureToken != null ||
            controlOperation.token != null || stillExposure?.ownsCapture == true || (bound && !controlReady))
    }
    private fun event(message: String) { refreshRawAdmission(); status = message; log.write(message) }
    private fun clearControlDeadline() {
        controlTimeout?.let(controlHandler::removeCallbacks); controlTimeout = null
    }
    private fun clearLiveExposure(keepTarget: Boolean = false) {
        exposureFlush?.let(controlHandler::removeCallbacks); exposureFlush = null
        val retained = liveControlIntent
        liveControls.cancel(keepTarget); exposureOperationPending = false; exposureQueued = false
        liveControlIntent = if (keepTarget) retained else null
        if (!keepTarget) liveIntentKey = null
        liveExposureTarget = liveControlIntent?.exposure
    }
    private fun invalidateControl() {
        clearControlDeadline(); controlOperation.invalidate()
    }
    private fun expireControl(token: ControlToken) {
        if (!controlOperation.expire(token, SystemClock.elapsedRealtime())) return
        autofocusObservations = autofocusObservations.suspend()
        clearControlDeadline(); clearLiveExposure(keepTarget = true)
        val capturePreparation = runtimeTarget == RuntimeTarget.CAPTURE
        busy = false; controlReady = false
        operationReceipt(operationReceipt.copy(phase = ControlPhase.UNKNOWN, acceptedAtMs = null,
            detail = "設定応答期限を超過・適用状態不明。センサー露光の継続とアプリ待ちを区別してください"))
        lastFailure = operationReceipt.detail
        desired = "CONTROL_TIMEOUT; sensor state unknown"
        event("CONTROL_TIMEOUT token=$token target=$runtimeTarget lastPreviewReceipt=[$controlReceipt]; API response unconfirmed, not proof of sensor freeze")
        if (capturePreparation) failCapturePreparation("撮影設定の受付期限を超過・撮影未開始")
    }
    private fun controlCurrent(token: ControlToken): Boolean {
        if (closed || token.epoch != epoch || !controlOperation.current(token)) return false
        if (SystemClock.elapsedRealtime() >= controlOperation.deadlineMs) { expireControl(token); return false }
        return true
    }
    private fun beginControl(target: ControlSnapshot, kind: RuntimeTarget = RuntimeTarget.GENERAL) {
        if (target.overrides.focusDistance != operationReceipt.accepted?.overrides?.focusDistance)
            autofocusObservations = autofocusObservations.suspend()
        invalidateControl()
        runtimeTarget = kind
        runtimeRevision = stillExposure?.target?.revision ?: 0L
        val previous = exposureDurations.previous(selectedId)
        val budget = controlBudgetMs(target.exposure?.exposureNs, previous, sample?.frameDurationNs)
        val token = controlOperation.begin(epoch, SystemClock.elapsedRealtime(), budget)
        exposureDurations.submitted(selectedId, target.exposure?.exposureNs)
        operationReceipt(operationReceipt.copy(epoch = epoch, sequence = ++receiptSequence,
            phase = ControlPhase.PENDING, requested = target, acceptedAtMs = null,
            detail = "送信中・応答期限${budget}ms（前後露光/実測frame周期を考慮）"))
        controlTimeout = Runnable { expireControl(token) }.also { controlHandler.postDelayed(it, budget) }
        if (kind != RuntimeTarget.PREVIEW || stillExposure?.filesSaved != false) lastFailure = null
        sampleBurstUntilMs = SystemClock.elapsedRealtime() + 2500
        log.write("CONTROL_BEGIN token=$token kind=$kind revision=$runtimeRevision budgetMs=$budget requested=$target previousExposureNs=$previous observedFrameNs=${sample?.frameDurationNs}; budget is not shutter lag")
    }
    private fun acceptedControl() {
        // The owning listener checked the deadline before any acceptance writes on the main thread.
        val token = controlOperation.token ?: return
        clearControlDeadline(); controlOperation.finish(token)
        sampleBurstUntilMs = SystemClock.elapsedRealtime() + 2500
        val accepted = operationReceipt.requested ?: return
        autofocusObservations = autofocusObservations.controlsAccepted(epoch, SystemClock.elapsedRealtime(),
            accepted.overrides.focusDistance, editControls.overrides.focusDistance,
            selectedReport?.autoFocusAvailable == true)
        exposureDurations.accepted(selectedId, accepted.exposure?.exposureNs)
        operationReceipt(operationReceipt.copy(epoch = epoch, phase = ControlPhase.ACCEPTED,
            accepted = accepted, acceptedAtMs = SystemClock.elapsedRealtime(), detail = when (runtimeTarget) {
                RuntimeTarget.PREVIEW -> "preview API受付・撮影targetの受付/画質一致とは別"
                RuntimeTarget.CAPTURE -> "撮影設定API受付・単発結果とは別"
                RuntimeTarget.GENERAL -> "受付済・実測待ち"
            }))
    }
    fun selectExposure(key: CaptureKey, index: Int) {
        if (!canSelectExposure || selectedReport?.manualBlockReason != null) return
        val target = liveExposureSelection(key, index, exposureChoices, editControls, previewExposureSamples,
            epoch, SystemClock.elapsedRealtime())
            ?: run { event("Exposure input unavailable: fresh AUTO preview values and supported candidates required"); return }
        offerLiveControls(target)
    }
    private fun offerLiveControls(target: ControlSnapshot) {
        if (!canSelectLiveControls || target == editControls) return
        if (target.overrides.focusDistance != editControls.overrides.focusDistance)
            autofocusObservations = autofocusObservations.suspend()
        stillResumeIntent.clear()
        cancelPreviewWait()
        liveControls.offer(epoch, target)
        liveControlIntent = target; liveExposureTarget = target.exposure; liveIntentKey = exposureKey()
        exposureOperationPending = true; exposureQueued = true
        busy = true
        scheduleLiveControls()
    }
    private fun scheduleLiveControls() {
        if (liveControls.active == null && liveControls.queued != null && exposureFlush == null) {
            exposureFlush = Runnable {
                exposureFlush = null
                liveControls.take()?.let(::dispatchLiveControls)
            }.also { controlHandler.postDelayed(it, 32) }
        }
    }
    private fun dispatchLiveControls(entry: LiveControlEntry) {
        if (closed || entry.epoch != epoch || liveControls.active != entry || recordingBusy) return
        val c = camera ?: return
        val target = entry.controls
        val manual = target.exposure != null
        val key = exposureKey()
        try {
            cancelPreviewWait()
            val request = if (manual && mode == CaptureMode.STILL)
                selectStillTarget(target.exposure, target.overrides, target.locks, target.evIndex)
                else target.also { stillExposure = null }
            val kind = if (manual && mode == CaptureMode.STILL) RuntimeTarget.PREVIEW else RuntimeTarget.GENERAL
            beginControl(request, kind)
            busy = true; controlReady = false; exposureQueued = liveControls.queued != null
            desired = "要求=$target; runtime=${request.exposure} 送信中"
            val finish = {
                // These are acceptance history only. A newer intent remains in the queue and UI.
                appliedExposure = target.exposure; overrides = target.overrides; manualExposure = manual
                acceptLocks(target.locks, "Live aggregate snapshot")
                acceptedControl()
                target.exposure?.let { preferences.saveExposure(key, it.iso.toString(),
                    java.math.BigDecimal.valueOf(it.exposureNs, 6).toPlainString()) }
                overrideStatus = "Accepted=${target.overrides}; actual results separate"
                if (manual) evStatus = "EV inactive while MANUAL exposure is requested"
                liveControls.complete(entry)
                liveControlIntent = liveControls.intent; liveExposureTarget = liveControlIntent?.exposure
                if (liveControlIntent == null) liveIntentKey = null
                exposureOperationPending = liveControls.hasWork; exposureQueued = liveControls.queued != null
                busy = liveControls.hasWork
                controlReady = !busy && (stillExposure?.canCapture ?: true)
                desired = "API受付=$target; 実測は別確認"
                if (liveControls.hasWork) scheduleLiveControls() else if (kind == RuntimeTarget.PREVIEW) armPreviewWait()
            }
            val applyEvOrFinish = {
                if (manual || target.evIndex == acceptedEv) finish()
                else applyEv(c, entry.epoch, key, target.evIndex ?: 0, persist = false, accepted = finish)
            }
            val acceptedRuntime = controlReceipt.accepted
            if (acceptedRuntime != null && acceptedRuntime.exposure == request.exposure &&
                acceptedRuntime.overrides == target.overrides && acceptedRuntime.locks == target.locks) {
                applyEvOrFinish()
            } else requestSnapshot(c, entry.epoch, request.exposure, target.overrides, target.locks,
                clearFirst = false, cancelFocus = (target.overrides.focusDistance == null) != (overrides.focusDistance == null),
                boostPercent = if (kind == RuntimeTarget.PREVIEW) runtimeBoost else null,
                accepted = applyEvOrFinish, failed = { failure -> error("live controls", failure) })
        } catch (failure: Exception) {
            operationReceipt(operationReceipt.copy(phase = ControlPhase.UNKNOWN, acceptedAtMs = null,
                detail = "Live control failed; latest intent retained, reset or rebind required"))
            error("live controls", failure)
            invalidateControl(); clearLiveExposure(keepTarget = true); busy = false; controlReady = false
        }
    }
    private fun error(operation: String, e: Exception) {
        lastFailure = "$operation: ${e.message}"
        operationReceipt(operationReceipt.failure(e.message ?: operation,
            operation.contains("validation") || operation.contains("input")))
        if (operationReceipt.phase == ControlPhase.UNKNOWN) {
            autofocusObservations = autofocusObservations.suspend()
            controlReady = false; busy = false; invalidateControl(); clearLiveExposure(keepTarget = true)
        }
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
        val observationNowMs = SystemClock.elapsedRealtime()
        val displayTarget = logicalCaptureTarget(epoch, controlReceipt, liveExposureTarget, stillExposure?.target, appliedExposure)
        val displayDiagnostics = listOf(CaptureKey.ISO, CaptureKey.SHUTTER).joinToString("\n") {
            exposureDisplayDiagnostic(it, previewExposureSamples, epoch, observationNowMs, exposureChoices, bound, displayTarget)
        }
        val snapshot = """
            applicationId=${BuildConfig.APPLICATION_ID} version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}
            cameraId=$selectedId mode=$mode bound=$bound ready=$controlReady operationPending=$busy
            state=$status
            desiredExposure=$desired
            requestedIsoDraft=$requestedIso requestedMsDraft=$requestedMs
            controlSnapshot=$overrideStatus
            autoLocks=$lockStatus
            focus=$focusStatus EV=$evStatus thermal=$thermalStatus
            outputOrientation=$outputOrientation
            observedCaptureResult=$measured
            $displayDiagnostics
            latestLiveIntent=$liveControlIntent livePending=$liveControlsPending
            normalStillExposure=$stillExposure
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
        photoRollProviderPending = true
        photoRollRebind = true
        try {
            reports = CameraReports.read(context, log::write)
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (closed) { photoRollProviderPending = false; return@addListener }
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
                } catch (e: Exception) { photoRollRebind = null; error("initialize", e) }
                finally { photoRollProviderPending = false }
            }, main)
        } catch (e: Exception) { photoRollProviderPending = false; photoRollRebind = null; error("capability enumeration", e) }
    }

    private fun captureBlocksControl(): Boolean {
        if (activeCaptureToken == null && stillExposure?.let { it.ownsCapture && !it.filesTerminal } != true) return false
        event("撮影設定を専有中です。完了を待つかPreviewを停止して復旧してください")
        return true
    }
    private fun cancelPreviewWait() {
        previewWait?.let(controlHandler::removeCallbacks); previewWait = null
    }
    private fun cancelPhotoDeadline() {
        photoDeadline?.let(controlHandler::removeCallbacks); photoDeadline = null
    }
    private fun clearStillExposure() {
        cancelPreviewWait(); cancelPhotoDeadline()
        stillExposure = null; runtimeTarget = RuntimeTarget.GENERAL
    }
    private fun clearRetainedInterop() {
        // 1.6.2 clears its held options synchronously; the returned Future waits for a result.
        // An inactive/cancelled old Future is not evidence for the new session or exposure end.
        val previous = camera?.let { Camera2CameraControl.from(it.cameraControl) }?.also { retainedInterop = it } ?: retainedInterop ?: return
        try {
            val clear = previous.clearCaptureRequestOptions()
            clear.addListener({
                try { clear.get(); log.write("INTEROP_HELD_OPTIONS_CLEAR old control acknowledged") }
                catch (failure: Exception) { log.write("INTEROP_HELD_OPTIONS_CLEAR old result unavailable: " + failure.javaClass.simpleName) }
            }, main)
        } catch (failure: Exception) { log.write("INTEROP_HELD_OPTIONS_CLEAR_FAILED " + failure.message); throw failure }
    }
    private fun plannedStill(value: ManualExposure, extras: SensorOverrides, locks: AutoLocks, evIndex: Int? = acceptedEv): StillExposureTarget {
        NormalExposurePolicy.validate(value)
        val report = selectedReport ?: error("No Camera2 capability report")
        require(report.manualBlockReason == null) { report.manualBlockReason!! }
        val planning = ExposureSimulationPolicy.plan(value, report.isoRange, report.exposureRange,
            report.maxFrameDurationNs, maxOf(33_333_333L, lastPreviewMinFrameNs ?: 0L))
        require(planning is SimulationPlanning.Ready) { "明るさ近似previewを構成できません: " + (planning as SimulationPlanning.Blocked).reason }
        return StillExposureTarget(epoch, ++targetRevision, planning.plan,
            ControlSnapshot(value, evIndex, extras, locks), if (report.unityPostRawBoostSupported) 100 else null)
    }
    private fun selectStillTarget(value: ManualExposure, extras: SensorOverrides, locks: AutoLocks, evIndex: Int? = acceptedEv): ControlSnapshot {
        val target = plannedStill(value, extras, locks, evIndex)
        val previous = stillExposure
        stillExposure = if (previous == null || (previous.ownsCapture && previous.filesTerminal)) StillExposureState(target)
            else previous.retarget(target)
        check(stillExposure?.target == target) { "撮影中のtargetは変更できません" }
        return target.controls.copy(exposure = target.plan.preview)
    }
    private fun armPreviewWait() {
        if (liveControlsPending) return
        val state = stillExposure ?: return
        if (state.canCapture) { cancelPreviewWait(); return }
        if (state.phase !in setOf(StillExposurePhase.PREVIEW_APPLYING, StillExposurePhase.RESTORING) || previewWait != null) return
        val generation = epoch
        val revision = state.target.revision
        val budget = controlBudgetMs(state.target.plan.preview.exposureNs, exposureDurations.previous(selectedId), sample?.frameDurationNs)
        previewWait = Runnable {
            previewWait = null
            val current = stillExposure
            if (!closed && generation == epoch && current?.target?.revision == revision &&
                current.phase in setOf(StillExposurePhase.PREVIEW_APPLYING, StillExposurePhase.RESTORING)) {
                autofocusObservations = autofocusObservations.suspend()
                stillExposure = current.unknown("preview実測の確認期限を超過。保存結果とは別・AUTOまたは接続停止で復旧")
                invalidateControl(); clearLiveExposure(keepTarget = true)
                controlReady = false; busy = false; lastFailure = stillExposure?.detail
                event("PREVIEW_OBSERVATION_TIMEOUT revision=" + revision + "; sensor/CameraX timing unconfirmed, not a save failure")
            }
        }.also { controlHandler.postDelayed(it, budget) }
    }
    private fun observeStill(frame: SimulationFrame) {
        val previous = stillExposure ?: return
        val next = previous.observe(frame)
        stillExposure = next
        if (activeCaptureToken == null) controlReady = next.canCapture
        if (next.canCapture) { cancelPreviewWait(); applyOutputOrientation() } else armPreviewWait()
        if (frame.intent == SimulationIntent.STILL || previous.phase != next.phase) {
            log.write("STILL_RUNTIME_RESULT revision=" + next.target.revision + " phase=" + next.phase +
                " intent=" + frame.intent + " requested=" + frame.requested + " actual=" + frame.actual +
                " frameNs=" + frame.frameDurationNs + "; brightness/output correspondence is a separate check")
        }
    }
    private fun restoreStillPreview() {
        val state = stillExposure ?: return
        val c = camera ?: return
        if (!owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            pendingForegroundBind = true; stillExposure = state.suspend(); controlReady = false; busy = false; return
        }
        val preview = state.target.controls.copy(exposure = state.target.plan.preview)
        beginControl(preview, RuntimeTarget.PREVIEW)
        busy = true; controlReady = false
        event("preview復帰待ち・保存結果と別。直前のセンサー長露光による待ちを含む場合があります")
        try { requestSnapshot(c, epoch, preview.exposure, preview.overrides, preview.locks, clearFirst = false, cancelFocus = false,
            boostPercent = state.target.boostPercent,
            accepted = {
                acceptedControl(); busy = false; controlReady = stillExposure?.canCapture == true
                log.write("PREVIEW_RESTORE_ACK receipt=" + controlReceipt.sequence + "; new PREVIEW observation still required")
                armPreviewWait()
            }, failed = { failure -> busy = false; controlReady = false; error("preview restoration (photo result separate)", failure) })
        } catch (failure: Exception) { busy = false; controlReady = false; error("preview restoration (photo result separate)", failure) }
    }
    private fun failCapturePreparation(reason: String) {
        val state = stillExposure ?: return
        if (state.singleIssued) return // No public per-shot cancel; never overwrite an outstanding single request.
        invalidateControl(); cancelPhotoDeadline()
        stillExposure = state.captureReceipt(state.captureReceipt.copy(phase = ControlPhase.UNKNOWN,
            acceptedAtMs = null, detail = reason)).preparationAborted(reason)
        activeCaptureToken = null; photoStage = PhotoStage.FAILED; busy = false; controlReady = false
        applyOutputOrientation()
        lastFailure = reason
        log.write("CAPTURE_PREPARATION_FAILED " + reason + "; takePicture not issued")
        restoreStillPreview()
    }
    private fun finishStillPhoto(saved: Boolean) {
        cancelPhotoDeadline()
        stillExposure = stillExposure?.filesFinished(saved)
        if (stillExposure != null) { controlReady = false; restoreStillPreview() }
    }
    private fun armPhotoDeadline(token: String) {
        val state = stillExposure ?: return
        val budget = controlBudgetMs(state.captureTarget?.plan?.capture?.exposureNs,
            exposureDurations.previous(selectedId), sample?.frameDurationNs)
        val generation = epoch
        photoDeadline = Runnable {
            photoDeadline = null
            val current = stillExposure
            if (!closed && generation == epoch && activeCaptureToken == token && current?.singleIssued == true && !current.filesTerminal) {
                stillExposure = current.unknown("CameraX撮影結果が未確定・撮影設定を保持中。接続停止で復旧できます")
                lastFailure = stillExposure?.detail; controlReady = false
                event("CAPTURE_RESULT_UNKNOWN; no terminal callback; target retained, not a fabricated save failure")
            }
        }.also { controlHandler.postDelayed(it, budget) }
    }
    /** Called before Activity lifecycle can restart CameraX. Deferred binds are not active work. */
    fun onHostStarting() { if (!closed) photoRollHostMayStart = true }

    fun onForeground() {
        onHostStarting()
        rawCameraOwner?.foreground(true)
        rawQueue?.onForeground()
        refreshRawAdmission()
        if (!closed && pendingForegroundBind) { pendingForegroundBind = false; busy = false; bind() }
    }

    fun selectCamera(id: String) {
        if (captureBlocksControl()) return
        if (!busy && !recordingBusy) {
            stillResumeIntent.clear(); pendingForegroundBind = false
            selectedId = id; preferences.put("camera", id)
            format = preferences.text("format/$id", ImageCapture.OUTPUT_FORMAT_JPEG.toString()).toIntOrNull() ?: ImageCapture.OUTPUT_FORMAT_JPEG
            bind()
        }
    }
    private fun exposureKey() = "exposure/$selectedId/${mode.name}"
    fun editIso(value: String) { if (captureBlocksControl()) return; requestedIso = value; preferences.put(exposureKey() + "/iso", value) }
    fun editMs(value: String) { if (captureBlocksControl()) return; requestedMs = value; preferences.put(exposureKey() + "/ms", value) }
    fun selectMode(value: CaptureMode) {
        if (busy || recordingBusy || mode == value) return
        stillResumeIntent.clear(); pendingForegroundBind = false
        clearStillExposure(); mode = value; preferences.put("mode", value.name); bind()
    }
    fun selectVideo(key: String) {
        if (busy || recordingBusy || mode != CaptureMode.CINE || videoChoices.none { it.key == key }) return
        preferences.put("video/$selectedId", key); bind()
    }
    fun setAudio(value: Boolean) { if (activeCaptureToken == null && !recordingBusy) { audioWanted = value; if (!value) audioPermissionDenied = false; preferences.put("audio", value) } }
    fun chooseHighResolution(value: Boolean) {
        if (captureBlocksControl()) return
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
        if (!applyOutputOrientation()) return
        rawCameraOwner?.update(true)
        log.write("VIDEO_ORIENTATION startRotation=${outputOrientation.applied}; fixed until completion")
        cine.start(session.recorder, audioWanted, "${session.choice.label} exposure=[$desired] overrides=[$overrides] requestedLocks=[$autoLocks]")
    }
    fun stopRecording() { cine.stop("user") }
    fun reportAudioPermission(granted: Boolean) {
        audioPermissionDenied = !granted
        event("MICROPHONE permission=$granted${if (granted) "" else "; recording not started; grant permission or explicitly select audio off"}")
    }
    fun onBackground() {
        photoRollHostMayStart = false
        rawCameraOwner?.foreground(false)
        cine.stop("activity background; no background recording")
        if (mode == CaptureMode.STILL && (bound || stillExposure != null || exposureOperationPending)) {
            val pendingAuto = if (runtimeTarget == RuntimeTarget.GENERAL) autoResumeTarget(operationReceipt) else null
            stillResumeIntent.remember(exposureKey(), null, null,
                liveControlIntent ?: pendingAuto, liveControlIntent ?: stillExposure?.target?.controls ?: selectedControls)
            // Invalidate the old session while keeping only its logical target for the next bind.
            pendingForegroundBind = true
            stopPreview()
            log.write("STILL_BACKGROUND session invalidated; safe preview rebind deferred to foreground")
            return
        }
        if (controlOperation.token != null || exposureOperationPending) {
            autofocusObservations = autofocusObservations.suspend()
            invalidateControl(); clearLiveExposure(keepTarget = true); busy = false
            controlReady = false
            controlReceipt = controlReceipt.copy(phase = ControlPhase.UNKNOWN, acceptedAtMs = null, detail = "背景化により送信/応答待ちを終了・AUTOまたは再接続で復旧")
        }
    }
    private fun onCineChanged() {
        if (!closed && pendingStop && !recordingBusy) { pendingStop = false; stop() }
        else if (!closed && !recordingBusy) applyOutputOrientation()
        refreshRawAdmission()
    }

    fun focusCenter() {
        val previewView = view ?: return
        focusAt(previewView.width / 2f, previewView.height / 2f, "center")
    }

    fun focusAt(x: Float, y: Float, source: String = "tap") {
        if (captureBlocksControl()) return
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
            focusFeedback = FocusFeedback(requestedPoint, FocusPhase.PENDING)
            log.write("FOCUS_DESIRED $target flags=$flags; actual sensor mapping is observed separately in request/result regions")
            val future = c.cameraControl.startFocusAndMetering(action)
            future.addListener({
                if (generation != epoch || closed) return@addListener
                busy = false
                try {
                    val result = future.get()
                    focusFeedback = FocusFeedback(requestedPoint, FocusPhase.COMPLETED, if (includesAf) result.isFocusSuccessful else null)
                    focusStatus = if (includesAf) "$target AF API result=${result.isFocusSuccessful}; supported portions only, inspect actual regions/quality"
                        else "$target AE metering API completed; no AF requested; inspect actual region"
                    event("FOCUS $focusStatus")
                } catch (failure: Exception) { focusFeedback = FocusFeedback(requestedPoint, FocusPhase.FAILED); focusStatus = "Focus request failed"; error("focus", failure) }
            }, main)
        } catch (failure: Exception) {
            busy = false; focusFeedback = null; focusStatus = "Focus/metering request rejected: ${failure.message}"
            error("focus validation", failure)
        }
    }

    fun selectEv(index: Int) {
        if (!canSelectLiveControls) return
        val c = camera ?: return
        try {
            val state = c.cameraInfo.exposureState
            val range = state.exposureCompensationRange
            val target = ProbeControls.evTarget(index, 0, range.lower..range.upper,
                state.isExposureCompensationSupported, editControls.exposure == null)
            offerLiveControls(editControls.copy(evIndex = target))
            // A saved user request is distinct from acceptedEv and CaptureResult evidence.
            preferences.put("${exposureKey()}/ev", target.toString())
        } catch (failure: Exception) { rejectLiveInput("EV", failure) }
    }

    private fun rejectLiveInput(name: String, failure: Exception) {
        lastFailure = "$name: ${failure.message}"
        event("LIVE_INPUT_REJECTED $name ${failure.message}; active snapshot unchanged")
    }

    private fun applyEv(c: Camera, generation: Int, key: String, requested: Int, persist: Boolean, accepted: () -> Unit) {
        val token = controlOperation.token ?: return
        if (!controlCurrent(token)) return
        try {
            val state = c.cameraInfo.exposureState
            if (!state.isExposureCompensationSupported) {
                acceptedEv = null
                operationReceipt(operationReceipt.copy(requested = operationReceipt.requested?.copy(evIndex = null)))
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
                if (generation != epoch || !controlCurrent(token)) return@addListener
                try {
                    val value = future.get()
                    acceptedEv = value
                    operationReceipt(operationReceipt.copy(requested = operationReceipt.requested?.copy(evIndex = value)))
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
        if (captureBlocksControl()) return
        if (busy || recordingBusy || mode != CaptureMode.STILL) return
        if (value !in formats) { event("UNSUPPORTED ${formatName(value)} by CameraX id=$selectedId"); return }
        format = value; preferences.put("format/$selectedId", value.toString()); bind()
    }

    fun selectWhiteBalance(value: Int?) = changeOverrides(editControls.overrides.copy(whiteBalance = value), OverrideField.WHITE_BALANCE)
    fun selectAntiBanding(value: Int?) = changeOverrides(editControls.overrides.copy(antiBanding = value), OverrideField.ANTI_BANDING)
    fun restoreAutofocus() = changeOverrides(editControls.overrides.copy(focusDistance = null), OverrideField.FOCUS)
    fun selectManualFocus(value: Float) = changeOverrides(editControls.overrides.copy(focusDistance = value), OverrideField.FOCUS)

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
        if (!canSelectLiveControls) return
        try {
            candidate.validate(selectedReport?.overrides ?: OverrideCapabilities())
            val current = editControls
            val targetLocks = current.locks.forControls(current.exposure != null, candidate.whiteBalance)
            offerLiveControls(current.copy(overrides = candidate, locks = targetLocks))
            // Persist the explicitly edited preference, never fallback values from another group.
            candidate.persistencePatch(edited).forEach { (name, saved) -> preferences.put("${exposureKey()}/$name", saved) }
        } catch (failure: Exception) { rejectLiveInput("override", failure) }
    }

    fun toggleExposureLock() = setLocks(autoLocks.copy(exposure = !autoLocks.exposure))
    fun toggleWhiteBalanceLock() = setLocks(autoLocks.copy(whiteBalance = !autoLocks.whiteBalance))

    private fun acceptLocks(value: AutoLocks, reason: String) {
        autoLocks = value
        lockStatus = "Requested=$value ACCEPTED; $reason; not persisted, inspect actual CaptureResult"
        log.write("AUTO_LOCKS $lockStatus")
    }

    private fun setLocks(candidate: AutoLocks) {
        if (captureBlocksControl()) return
        if (busy || recordingBusy || !bound || !controlReady) return
        val c = camera ?: return
        var started = false
        try {
            candidate.validate(selectedReport?.locks ?: LockCapabilities(), manualExposure, overrides.whiteBalance)
            val generation = epoch
            val request = if (stillExposure != null && appliedExposure != null) selectStillTarget(appliedExposure!!, overrides, candidate)
                else runtimeControls.copy(locks = candidate)
            beginControl(request, if (stillExposure != null) RuntimeTarget.PREVIEW else RuntimeTarget.GENERAL)
            busy = true; controlReady = false; started = true
            lockStatus = "Requested=$candidate PENDING; current binding only"
            log.write("AUTO_LOCKS $lockStatus")
            requestSnapshot(c, generation, request.exposure, overrides, candidate, clearFirst = false, cancelFocus = false, boostPercent = runtimeBoost,
                accepted = {
                    acceptLocks(candidate, "User lock/unlock"); busy = false; acceptedControl()
                    controlReady = stillExposure?.canCapture ?: true
                    if (stillExposure != null) armPreviewWait()
                },
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
        locks: AutoLocks, clearFirst: Boolean, cancelFocus: Boolean, accepted: () -> Unit, failed: (Exception) -> Unit, boostPercent: Int? = null) {
        val token = controlOperation.token ?: return
        if (!controlCurrent(token)) return
        val control = Camera2CameraControl.from(c.cameraControl)
        val builder = CaptureRequestOptions.Builder()
        exposure?.let {
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it.iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it.exposureNs)
        }
        boostPercent?.let { builder.setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it) }
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
                if (generation != epoch || !controlCurrent(token)) return@addListener
                try { future.get(); next() } catch (failure: Exception) { failed(failure) }
            }, main)
        }
        fun apply() {
            if (!controlCurrent(token)) return
            if (exposure == null && extras.isEmpty && locks.isEmpty) {
                if (clearFirst) accepted() else await(control.clearCaptureRequestOptions(), accepted)
            } else await(control.setCaptureRequestOptions(builder.build()), accepted)
        }
        fun clearAndApply() {
            if (!controlCurrent(token)) return
            if (clearFirst) await(control.clearCaptureRequestOptions(), ::apply) else apply()
        }
        if (cancelFocus) await(c.cameraControl.cancelFocusAndMetering(), ::clearAndApply) else clearAndApply()
    }

    /** Revalidate a rendered action; a new session must earn its own API receipt and preview readiness. */
    fun recoverControls(expectedEpoch: Int) {
        if (!controlRecovery.canReconnect(expectedEpoch)) return
        event("CONTROL_RECOVERY requested; reconnecting current camera/mode, confirmation still required")
        bind()
    }

    fun bind() {
        if (busy || recordingBusy || closed) return
        if (!photoRollHostMayStart) { pendingForegroundBind = true; photoRollRebind = false; return }
        val p = provider ?: return
        val id = selectedId ?: run { event("UNSUPPORTED no CameraX camera"); return }
        val previewView = view ?: return
        val retryIntent = liveControlIntent.takeIf { liveIntentKey == exposureKey() }
        photoRollRebind = true
        rawCameraOwner?.update(true)
        try { clearRetainedInterop() } catch (failure: Exception) { photoRollRebind = null; error("bind: held Interop clear", failure); return }
        clearStillExposure(); invalidateControl(); clearLiveExposure()
        lastPreviewMinFrameNs = null
        val generation = ++epoch
        autofocusObservations = AutofocusObservations(epoch = epoch)
        previewExposureSamples = PreviewExposureSamples(epoch = epoch)
        sample = null; acceptedEv = null; photoStage = PhotoStage.IDLE; focusFeedback = null
        outputOrientation = outputOrientation.copy(applied = null)
        controlReceipt = ControlReceipt(epoch = epoch, sequence = controlReceipt.sequence)
        val settingsKey = exposureKey()
        val resumeForThisBind = stillResumeIntent.peek(settingsKey)
        val pendingForThisBind = pendingBindControls.take(settingsKey, generation)
        val restoreForThisBind = resumeForThisBind?.controls ?: pendingForThisBind ?: retryIntent?.copy(locks = AutoLocks())
        liveControlIntent = restoreForThisBind; liveExposureTarget = restoreForThisBind?.exposure
        liveIntentKey = settingsKey.takeIf { restoreForThisBind != null }
        requestedIso = preferences.text("$settingsKey/iso", "100")
        requestedMs = preferences.text("$settingsKey/ms", "10")
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
            var lastOtherSample = 0L
            var lastResultLog = 0L
            val builder = Preview.Builder()
            previewSeeded = false
            val restoredCaptureBeforeLimit = if (restoreForThisBind != null) restoreForThisBind.exposure
                else if (mode == CaptureMode.STILL && preferences.flag("$settingsKey/manual")) {
                val report = selectedReport
                NormalExposurePolicy.restoreSaved(preferences.text("$settingsKey/appliedIso"), preferences.text("$settingsKey/appliedMs"),
                    report?.isoRange, report?.exposureRange, exposureChoices).target
            } else null
            val restoredCapture = restoredCaptureBeforeLimit?.let {
                if (mode == CaptureMode.STILL) NormalExposurePolicy.restore(it, exposureChoices).target else it
            }
            if (mode == CaptureMode.STILL && restoredCapture != null) {
                val safe = plannedStill(restoredCapture, restoreForThisBind?.overrides ?: savedOverrides(settingsKey), AutoLocks())
                Camera2Interop.Extender(builder)
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, safe.plan.preview.iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, safe.plan.preview.exposureNs)
                    .also { extender -> safe.boostPercent?.let { extender.setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, it) } }
                previewSeeded = true
                log.write("PREVIEW_SAFE_SEED captureTarget=${safe.plan.capture} preview=${safe.plan.preview} stage=${safe.plan.stage}; no still-target options in ImageCapture Builder")
            }
            if (mode == CaptureMode.CINE) builder.setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build())
            val exposureMailbox = PreviewExposureMailbox()
            val focusMaximum = selectedReport?.overrides?.manualFocusMaximum
            val focusMailbox = AutofocusObservationMailbox(focusMaximum)
            val resultCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    // Observe only: never operate CameraX's CameraDevice / session.
                    val still = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) == CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                    val previewIntent = request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) in setOf(
                        CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                    val now = SystemClock.elapsedRealtime()
                    val observation = CaptureSample(generation, result.frameNumber, now,
                        SensorValues(request.get(CaptureRequest.SENSOR_SENSITIVITY), request.get(CaptureRequest.SENSOR_EXPOSURE_TIME), request.get(CaptureRequest.CONTROL_AE_MODE),
                            request.get(CaptureRequest.LENS_FOCUS_DISTANCE), request.get(CaptureRequest.CONTROL_AF_MODE), request.get(CaptureRequest.CONTROL_AWB_MODE),
                            request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION), request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE),
                            request.get(CaptureRequest.CONTROL_AE_LOCK), request.get(CaptureRequest.CONTROL_AWB_LOCK)),
                        SensorValues(result.get(CaptureResult.SENSOR_SENSITIVITY), result.get(CaptureResult.SENSOR_EXPOSURE_TIME), result.get(CaptureResult.CONTROL_AE_MODE),
                            result.get(CaptureResult.LENS_FOCUS_DISTANCE), result.get(CaptureResult.CONTROL_AF_MODE), result.get(CaptureResult.CONTROL_AWB_MODE),
                            result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION), result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                            result.get(CaptureResult.CONTROL_AE_LOCK), result.get(CaptureResult.CONTROL_AWB_LOCK)),
                        frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                        intent = when (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)) {
                            CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW -> CaptureSampleIntent.PREVIEW
                            CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD -> CaptureSampleIntent.VIDEO_RECORD
                            CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE -> CaptureSampleIntent.STILL_CAPTURE
                            else -> CaptureSampleIntent.UNKNOWN
                        })
                    // Preserve every valid axis before diagnostic sampling can discard its frame.
                    // The mailbox owns at most one pending UI runnable and retains source timestamps.
                    if (previewIntent && exposureMailbox.offer(observation)) main.execute {
                        val pending = exposureMailbox.drain()
                        if (generation == epoch && !closed && pending != null) {
                            listOfNotNull(pending.iso, pending.shutter).distinctBy { it.frame }.sortedBy { it.frame }.forEach {
                                previewExposureSamples = previewExposureSamples.observe(it, epoch)
                            }
                        }
                    }
                    if (previewIntent && focusMailbox.offer(observation)) main.execute {
                        val pending = focusMailbox.drain()
                        if (generation == epoch && !closed && pending != null) {
                            autofocusObservations = autofocusObservations.observe(pending, epoch,
                                SystemClock.elapsedRealtime(), focusMaximum)
                        }
                    }
                    // Diagnostics and acceptance observations have their own sampling budget.
                    val sampleIntervalMs = if (now < sampleBurstUntilMs || stillExposure != null) 200L else 500L
                    if (!still && now - (if (previewIntent) lastSample else lastOtherSample) < sampleIntervalMs) return
                    if (previewIntent) lastSample = now else lastOtherSample = now
                    val periodRate = Exposure.sensorPeriodFps(result.get(CaptureResult.SENSOR_FRAME_DURATION))
                        ?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "unknown"
                    val locks = "requestAELock=${request.get(CaptureRequest.CONTROL_AE_LOCK)} actualAELock=${result.get(CaptureResult.CONTROL_AE_LOCK)} aeState=${result.get(CaptureResult.CONTROL_AE_STATE)} requestAWBLock=${request.get(CaptureRequest.CONTROL_AWB_LOCK)} actualAWBLock=${result.get(CaptureResult.CONTROL_AWB_LOCK)}"
                    val regions = "requestAFRegions=${request.get(CaptureRequest.CONTROL_AF_REGIONS)?.contentToString()} actualAFRegions=${result.get(CaptureResult.CONTROL_AF_REGIONS)?.contentToString()} requestAERegions=${request.get(CaptureRequest.CONTROL_AE_REGIONS)?.contentToString()} actualAERegions=${result.get(CaptureResult.CONTROL_AE_REGIONS)?.contentToString()}"
                    val actual = "sensorPeriodFps=$periodRate (period sample; not preview/encoded FPS) $locks $regions frame=${result.frameNumber} sensorTimestamp=${result.get(CaptureResult.SENSOR_TIMESTAMP)} intent=${request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)} requestAE=${request.get(CaptureRequest.CONTROL_AE_MODE)} requestISO=${request.get(CaptureRequest.SENSOR_SENSITIVITY)} requestNs=${request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)} actualAE=${result.get(CaptureResult.CONTROL_AE_MODE)} actualISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)} actualNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} frameDurationNs=${result.get(CaptureResult.SENSOR_FRAME_DURATION)} requestAF=${request.get(CaptureRequest.CONTROL_AF_MODE)} actualAF=${result.get(CaptureResult.CONTROL_AF_MODE)} afState=${result.get(CaptureResult.CONTROL_AF_STATE)} requestFocusLensUnits=${request.get(CaptureRequest.LENS_FOCUS_DISTANCE)} actualFocusLensUnits=${result.get(CaptureResult.LENS_FOCUS_DISTANCE)} requestAWB=${request.get(CaptureRequest.CONTROL_AWB_MODE)} actualAWB=${result.get(CaptureResult.CONTROL_AWB_MODE)} awbState=${result.get(CaptureResult.CONTROL_AWB_STATE)} requestEV=${request.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION)} actualEV=${result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION)} requestAntiBanding=${request.get(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE)} actualAntiBanding=${result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE)} sceneFlicker=${result.get(CaptureResult.STATISTICS_SCENE_FLICKER)}"
                    main.execute {
                        if (generation == epoch && !closed) {
                            measured = actual
                            if (stillExposure != null) {
                                val kind = when (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)) {
                                    CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW -> SimulationIntent.PREVIEW
                                    CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE -> SimulationIntent.STILL
                                    else -> SimulationIntent.OTHER
                                }
                                observeStill(SimulationFrame(generation, result.frameNumber, now, kind, observation.request, observation.actual,
                                    observation.frameDurationNs, result.get(CaptureResult.SENSOR_TIMESTAMP),
                                    request.get(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST), result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)))
                                if (kind == SimulationIntent.PREVIEW) sample = newerSample(sample, observation, epoch)
                            } else sample = newerSample(sample, observation, epoch)
                            if (still || now - lastResultLog >= 1000) {
                                lastResultLog = now
                                log.write("RESULT id=$id desired=[$desired] $actual")
                            }
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
                var capture = captureBuilder.build().also { it.flashMode = ImageCapture.FLASH_MODE_OFF }
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
                    capture = fallbackBuilder.build().also { it.flashMode = ImageCapture.FLASH_MODE_OFF }
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
            check(applyOutputOrientation()) { "Output orientation unavailable" }
            if (mode == CaptureMode.CINE) {
                val previewInfo = preview.resolutionInfo
                val videoInfo = cineSession?.video?.resolutionInfo
                log.write("VIDEO_FRAMING viewport=9:16@ROTATION_0/FILL_CENTER recorderAspect=16:9 previewResolution=${previewInfo?.resolution} previewCropRect=${previewInfo?.cropRect} previewRotationDegrees=${previewInfo?.rotationDegrees} videoResolution=${videoInfo?.resolution} videoCropRect=${videoInfo?.cropRect} videoRotationDegrees=${videoInfo?.rotationDegrees}; bound session geometry, actual MP4 metadata logged separately; frameMatch=UNVERIFIED")
            }
            val previewSize = preview.resolutionInfo?.resolution
            val minimumPreviewFrameNs = runCatching {
                val configurations = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                previewSize?.let { configurations?.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, it) }
            }.getOrNull()
            lastPreviewMinFrameNs = minimumPreviewFrameNs?.takeIf { it > 0 }
            log.write("FRAME_TIMING previewSize=$previewSize minimumPreviewFrameNs=$minimumPreviewFrameNs maxSensorFrameNs=${selectedReport?.maxFrameDurationNs} selectedVideo=${cineSession?.choice}; minimum stream period is not maximum exposure; inspect actual SENSOR_FRAME_DURATION separately")
            bound = true
            camera!!.cameraInfo.cameraState.observe(owner) { state ->
                if (generation == epoch && !closed) {
                    log.write("CAMERA_STATE id=$id type=${state.type} error=${state.error?.code}")
                    state.error?.let {
                        autofocusObservations = autofocusObservations.suspend()
                        if (controlOperation.token != null || exposureOperationPending) {
                            invalidateControl(); clearLiveExposure(keepTarget = true); busy = false
                        }
                        controlReady = false
                        operationReceipt(operationReceipt.copy(phase = ControlPhase.UNKNOWN, acceptedAtMs = null, detail = "CameraX camera error ${it.code}"))
                        stillExposure = stillExposure?.unknown("CameraX camera error ${it.code}")
                        lastFailure = "CameraX camera error ${it.code}"
                        event("ERROR CameraX camera state code=${it.code}")
                    }
                }
            }
            event("BOUND id=$id mode=$mode format=${formatName(format)}; clearing Interop before capture")
            // The provider may reuse a CameraControl whose Interop options survive unbind/rebind.
            // Explicitly clear them and wait for acceptance before enabling capture.
            setExposure(remember = restoreForThisBind != null, restoreSaved = true, bindRestore = restoreForThisBind,
                bindResume = resumeForThisBind)
            photoRollRebind = false
        } catch (e: Exception) { photoRollRebind = null; stillExposure = stillExposure?.unknown("bind failed: ${e.message}"); error("bind id=$id mode=$mode format=${formatName(format)} (see selection/fallback log)", e) }
    }

    fun stop() {
        stillResumeIntent.clear(); pendingForegroundBind = false
        stopPreview()
    }
    private fun stopPreview() {
        photoRollRebind = true
        rawPresentation.invalidate()
        activeRawTicket?.let { rawQueue?.interrupt(it) }; activeRawTicket = null
        pendingBindControls.clear()
        clearStillExposure()
        if (recordingBusy) { pendingStop = true; cine.stop("preview Stop"); return }
        invalidateControl(); clearLiveExposure()
        ++epoch
        autofocusObservations = AutofocusObservations(epoch = epoch)
        previewExposureSamples = PreviewExposureSamples(epoch = epoch)
        sample = null; acceptedEv = null; photoStage = PhotoStage.IDLE; focusFeedback = null
        outputOrientation = outputOrientation.copy(applied = null)
        controlReceipt = ControlReceipt(epoch = epoch, sequence = controlReceipt.sequence)
        busy = false
        controlReady = false
        activeCaptureToken = null
        videoSelection = "Unbound; no applied video profile"
        stillResolution = "Unbound; no applied image resolution"
        focusStatus = "Unbound; no active focus request"; evStatus = "Unbound; no accepted EV"; manualExposure = false
        overrides = SensorOverrides(); appliedExposure = null; overrideStatus = "Unbound; no accepted overrides"
        autoLocks = AutoLocks(); lockStatus = "Unbound; no lock request"
        try { clearRetainedInterop() } catch (failure: Exception) { log.write("STOP_INTEROP_CLEAR_FAILED " + failure.message) }
        try {
            camera?.cameraInfo?.cameraState?.removeObservers(owner)
            provider?.unbindAll(); camera = null; imageCapture = null; cineSession = null; bound = false
            photoRollRebind = false
            desired = "AUTO (unbound)"; event("Preview stopped")
        } catch (e: Exception) { photoRollRebind = null; error("stop", e) }
    }

    fun setExposureAuto() = switchToAuto(false)
    fun resetAllControls() = switchToAuto(true)
    private fun switchToAuto(reset: Boolean) {
        if (closed || recordingBusy || captureBlocksControl()) return
        if (exposureOperationPending) { invalidateControl(); busy = false }
        if (busy) return
        val previous = editControls
        // AUTO supersedes retained failed/UNKNOWN manual input even after its pending flag cleared.
        clearLiveExposure()
        stillResumeIntent.clear(); pendingForegroundBind = false
        exposurePolicyStatus = NormalExposurePolicy.DESCRIPTION + "; AUTOは手動露出解除・実測露光は別確認"
        exposureMigrationNotice = null
        val key = exposureKey()
        // These are explicit reset preferences, not API acceptance or measured sensor state.
        if (reset) { saveOverrides(key, SensorOverrides()); preferences.put("$key/ev", "0") }
        // A static manual Preview seed would reappear after clear; replace that use case once.
        if (mode == CaptureMode.STILL && (previewSeeded || camera == null)) {
            pendingBindControls.offer(key, epoch + 1,
                autoControlSnapshot(previous, preferences.text("$key/ev", "0").toIntOrNull() ?: 0, reset))
            preferences.saveExposure(key, null, null)
            clearStillExposure(); bind()
        } else setExposure(reset = reset, autoBase = previous)
    }
    fun setExposure(iso: String? = null, ms: String? = null, remember: Boolean = true, restoreSaved: Boolean = false, reset: Boolean = false, bindRestore: ControlSnapshot? = null, bindResume: StillResumeRequest? = null, autoBase: ControlSnapshot? = null) {
        if (captureBlocksControl() || busy || recordingBusy) return
        val c = camera ?: return
        try {
            val report = selectedReport
            val key = exposureKey()
            val input = if (restoreSaved && bindRestore != null) bindRestore.exposure?.let {
                ManualExposureInput(it.iso.toString(), java.math.BigDecimal.valueOf(it.exposureNs, 6).toPlainString())
            } else if (restoreSaved && preferences.flag("$key/manual"))
                if (mode == CaptureMode.STILL) ManualExposureInput(preferences.text("$key/appliedIso"), preferences.text("$key/appliedMs"))
                else Exposure.savedManual(preferences.text("$key/appliedIso"), preferences.text("$key/appliedMs"), report?.isoRange, report?.exposureRange)
                else if (iso != null && ms != null) ManualExposureInput(iso, ms) else null
            var restoration: NormalExposureRestoration? = null
            val value = input?.let {
                require(report != null) { "No Camera2 report" }
                require(report.manualBlockReason == null) { report.manualBlockReason!! }
                val normalized = if (restoreSaved && mode == CaptureMode.STILL) {
                    NormalExposurePolicy.restoreSaved(it.iso, it.milliseconds, report.isoRange, report.exposureRange,
                        exposureChoices).also { restoration = it }.target
                } else NormalExposurePolicy.validate(Exposure.parse(it.iso, it.milliseconds, report.isoRange, report.exposureRange))
                normalized.also { pair ->
                    require(report.maxFrameDurationNs == null || pair.exposureNs <= report.maxFrameDurationNs) { "Exposure exceeds device maximum frame duration" }
                    if (mode == CaptureMode.CINE) {
                        val maxFps = cineSession?.choice?.fpsUpper ?: error("Video fps unavailable")
                        require(pair.exposureNs <= 1_000_000_000L / maxFps) { "Exposure exceeds requested video frame period; lower fps or shorten exposure" }
                    }
                }
            }
            val migration = restoration?.takeIf { it.migrated }
            val migrationScope = "移行履歴 camera ID=${selectedId ?: "—"} mode=$mode epoch=$epoch: "
            migration?.let {
                exposurePolicyStatus = migrationScope + "旧保存/復帰target ${ExposureStops.label(it.original.exposureNs)} → ${ExposureStops.label(it.target.exposureNs)}へ移行要求（ISO ${it.target.iso}保持）。API受付・実測は未確認"
                exposureMigrationNotice = exposurePolicyStatus
                event(exposurePolicyStatus)
            }
            val manual = value != null
            val restored = if (restoreSaved) bindRestore else null
            val logical = exposureOperationIntent(value, autoBase ?: selectedControls,
                preferences.text("$key/ev", "0").toIntOrNull() ?: 0, reset, restored,
                if (restoreSaved && restored == null) savedOverrides(key) else null)
            val extras = logical.overrides
            val targetLocks = logical.locks
            // Legacy AUTO/reset shares the same scoped intent owner as live inputs. A failed request
            // must remain AUTO on explicit rebind even while saved exposure still says MANUAL.
            liveControlIntent = logical; liveExposureTarget = logical.exposure; liveIntentKey = key
            cancelPreviewWait()
            val request = if (manual && mode == CaptureMode.STILL) selectStillTarget(value, extras, targetLocks, logical.evIndex)
                else logical.also { stillExposure = null }
            val kind = if (manual && mode == CaptureMode.STILL) RuntimeTarget.PREVIEW else RuntimeTarget.GENERAL
            val generation = epoch
            val label = if (manual) "撮影target ISO=" + value.iso + " ns=" + value.exposureNs
                else if (reset) "AUTO reset: 全制御解除" else "AUTO露出: 独立制御を保持"
            desired = label + "; runtime=" + request.exposure + " PENDING"
            beginControl(request, kind)
            busy = true; controlReady = false
            log.write("EXPOSURE_DISPATCH captureTarget=" + value + " runtime=" + request.exposure + " kind=" + kind + "; no editing rebind")
            requestSnapshot(c, generation, request.exposure, extras, targetLocks, clearFirst = reset || restoreSaved,
                cancelFocus = reset || restoreSaved || (extras.focusDistance == null) != (overrides.focusDistance == null),
                boostPercent = if (kind == RuntimeTarget.PREVIEW) runtimeBoost else null,
                accepted = {
                    val finish = {
                        appliedExposure = value; overrides = extras; manualExposure = manual
                        acceptLocks(targetLocks, if (manual) "MANUAL releases AE lock" else "AUTO/reset control snapshot")
                        overrideStatus = "Accepted runtime controls=" + extras + "; actual results separate"
                        acceptedControl()
                        clearLiveExposure()
                        busy = false; controlReady = if (kind == RuntimeTarget.PREVIEW) stillExposure?.canCapture == true else true
                        desired = label + "; " + if (kind == RuntimeTarget.PREVIEW) "preview API受付・撮影API受付はシャッター時" else "API受付・実測は別確認"
                        event(desired)
                        if (remember || migration != null) {
                            val persisted = restoration?.input ?: input
                            preferences.saveExposure(key, persisted?.iso, persisted?.milliseconds)
                            if (!manual && reset) { saveOverrides(key, SensorOverrides()); preferences.put("$key/ev", "0") }
                        }
                        migration?.let {
                            requestedIso = it.target.iso.toString(); requestedMs = it.input.milliseconds
                            preferences.put("$key/iso", requestedIso); preferences.put("$key/ms", requestedMs)
                            exposurePolicyStatus = migrationScope + "旧target ${ExposureStops.label(it.original.exposureNs)} → ${ExposureStops.label(it.target.exposureNs)}へ移行・preview API受付済み。撮影API受付/実測は別確認"
                            event(exposurePolicyStatus)
                        }
                        bindResume?.let { stillResumeIntent.accepted(key, it) }
                        if (manual) evStatus = "EV inactive while MANUAL exposure is requested"
                        if (kind == RuntimeTarget.PREVIEW) armPreviewWait()
                    }
                    if (manual) finish() else applyEv(c, generation, key, logical.evIndex ?: 0,
                        persist = false, accepted = finish)
                }, failed = { failure ->
                    busy = false; controlReady = false
                    desired = label + " FAILED; runtime state unknown"
                    error("exposure runtime", failure)
                })
        } catch (failure: Exception) {
            busy = false; controlReady = false
            error("exposure validation/control", failure)
        }
    }

    private var photoRequestedAtMs = 0L
    fun capture() {
        if (busy || recordingBusy || mode != CaptureMode.STILL || !controlReady || !sharedSaveSupported) return
        val state = stillExposure
        if (state == null) { rawPresentation.invalidate(); captureFiles(); return }
        if (!state.canCapture) { event("previewの新しい受付・実測を待ってください"); return }
        val c = camera ?: return
        val token = UUID.randomUUID().toString()
        rawPresentation.invalidate()
        if (format == ImageCapture.OUTPUT_FORMAT_RAW || format == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) rawPresentation.begin(token, true)
        photoRequestedAtMs = SystemClock.elapsedRealtime()
        stillExposure = state.beginCapture()
        val fixed = stillExposure?.captureTarget ?: return
        activeCaptureToken = token; photoStage = PhotoStage.CAPTURING
        busy = true; controlReady = false
        try {
        beginControl(fixed.controls, RuntimeTarget.CAPTURE)
        event("撮影設定の受付待ち・長露光ではセンサーの結果待ちが発生します")
        requestSnapshot(c, epoch, fixed.plan.capture, fixed.controls.overrides, fixed.controls.locks,
            clearFirst = false, cancelFocus = false, boostPercent = fixed.boostPercent,
            accepted = {
                acceptedControl()
                if (stillExposure?.canIssueCapture != true || activeCaptureToken != token) return@requestSnapshot
                log.write("CAPTURE_PREPARATION_ACK token=" + token + " elapsedMs=" + (SystemClock.elapsedRealtime() - photoRequestedAtMs) +
                    "; runtime ACK includes repeating result wait, not only app queue")
                captureFiles(token)
            }, failed = { failure -> failCapturePreparation("撮影設定を反映できません・撮影未開始: " + failure.message) })
        } catch (failure: Exception) { failCapturePreparation("撮影設定を送信できません・撮影未開始: " + failure.message) }
    }
    private fun captureFiles(preparedToken: String? = null) {
        if (preparedToken == null && (busy || recordingBusy || mode != CaptureMode.STILL)) return
        if (preparedToken != null && (activeCaptureToken != preparedToken || stillExposure?.canIssueCapture != true)) return
        if (!sharedSaveSupported) { event("UNSUPPORTED shared image saving requires Android 10+"); return }
        if (preparedToken == null && !controlReady) { event("Capture disabled: runtime preview not ready; use AUTO or stop/restart"); return }
        val capture = imageCapture ?: run {
            if (preparedToken != null) failCapturePreparation("ImageCaptureが未接続・撮影未開始")
            return
        }
        if (!applyOutputOrientation()) {
            if (preparedToken != null) failCapturePreparation("撮影の向きを反映できません・撮影未開始")
            return
        }
        val captureRotation = outputOrientation.applied
        val token = preparedToken ?: UUID.randomUUID().toString()
        if (format == ImageCapture.OUTPUT_FORMAT_RAW || format == ImageCapture.OUTPUT_FORMAT_RAW_JPEG) {
            captureRawFiles(capture, token, preparedToken != null)
            return
        }
        val appCaptureStart = if (preparedToken == null) SystemClock.elapsedRealtime() else photoRequestedAtMs
        val generation = epoch
        val cameraId = selectedId
        val captureFormat = format
        val captureSettings = stillExposure?.captureTarget
        var cameraRequestIssued = false
        var photoRollCameraXIssued = false
        val photoRollCaptureClosed = AtomicBoolean(false)
        AndroidPhotoRollCameraObservations.holdCaptureUntil { photoRollCaptureClosed.get() }
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
                applyOutputOrientation()
                error("capture/shared save token=$token", e)
                if (captureSettings != null) {
                    if (cameraRequestIssued) finishStillPhoto(false) else failCapturePreparation("撮影要求を開始できません: " + e.message)
                }
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
            val callbacks = PhotoCallbackGate(expectedFormats)
            busy = true; activeCaptureToken = token
            log.write("STILL_CAPTURE_TARGET token=$token fixed=${captureSettings?.plan?.capture} previewTarget=${captureSettings?.plan?.preview}; no per-file sensor correspondence assumed")
            log.write("CAPTURE_BEGIN token=$token id=$cameraId format=${formatName(captureFormat)} targetRotation=$captureRotation desired=[$desired] selectedResolution=${capture.resolutionInfo?.resolution} target=${PhotoPublisher.RELATIVE_PATH}")
            val callback = object : ImageCapture.OnImageSavedCallback {
                private fun fail(e: Exception) {
                    if (!callbacks.fail()) { log.write("CAPTURE_LATE_CALLBACK_ERROR token=$token afterAppRequestMs=${SystemClock.elapsedRealtime() - appCaptureStart} ${e.javaClass.simpleName}"); return }
                    mediaIo.execute { clean(staging); if (!photoRollCameraXIssued) photoRollCaptureClosed.set(true) }
                    failed(e)
                }
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    try {
                        val outcome = callbacks.saved(result.imageFormat)
                        if (outcome == PhotoCallbackOutcome.IGNORED) {
                            log.write("CAPTURE_IGNORED_FILE_CALLBACK token=$token imageFormat=${result.imageFormat} terminal=${callbacks.terminal} publishing=${callbacks.publishing}")
                            // A duplicate before the pair completes still shares the live staging files.
                            if (callbacks.terminal && !callbacks.publishing) mediaIo.execute { clean(staging) }
                            return
                        }
                        log.write("CAPTURE_FILE_CALLBACK token=$token imageFormat=${result.imageFormat} afterAppRequestMs=${SystemClock.elapsedRealtime() - appCaptureStart}; includes camera/file/callback delivery, not shutter lag")
                        if (outcome == PhotoCallbackOutcome.PARTIAL) return
                        if (isCurrent()) {
                            cancelPhotoDeadline()
                            photoStage = PhotoStage.SAVING
                            stillExposure = stillExposure?.copy(detail = "CameraX全ファイル受領・共有フォルダー保存待ち（露光待ちとは別）")
                        }
                        val allCallbacksAt = SystemClock.elapsedRealtime()
                        // CameraX has finished every file. Publish together off the UI thread.
                        mediaIo.execute {
                            var photoRollSaveClosed = false
                            try {
                                val mediaWorkStart = SystemClock.elapsedRealtime()
                                sources.forEach { source ->
                                    try {
                                        val orientationExif = android.media.ExifInterface(source.file)
                                        log.write("IMAGE_ORIENTATION token=$token requestedTargetRotation=$captureRotation actualExifOrientation=${orientationExif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_UNDEFINED)}; viewerValidation=UNVERIFIED")
                                        if (captureSettings != null) {
                                            val storedIso = orientationExif.getAttribute("PhotographicSensitivity") ?: orientationExif.getAttribute("ISOSpeedRatings")
                                            log.write("STILL_STORED_METADATA token=$token mime=${source.format.mimeType} fixedTarget=${captureSettings.plan.capture} storedIso=$storedIso storedExposureSeconds=${orientationExif.getAttribute(android.media.ExifInterface.TAG_EXPOSURE_TIME)}; metadata only, not full RAW decode or pixel brightness proof")
                                        }
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
                                photoRollSaveClosed = true
                                val publishedAt = SystemClock.elapsedRealtime()
                                main.execute {
                                    log.write("CAPTURE_TIMING token=$token allFileCallbacksAfterAppRequestMs=${allCallbacksAt - appCaptureStart} ioQueueMs=${mediaWorkStart - allCallbacksAt} metadataAndPublishWorkMs=${publishedAt - mediaWorkStart} appRequestToPublishedMs=${publishedAt - appCaptureStart} publishToUiNotificationMs=${SystemClock.elapsedRealtime() - publishedAt}; app stages only, not sensor shutter lag")
                                    val current = isCurrent()
                                    if (current) { busy = false; activeCaptureToken = null; photoStage = PhotoStage.SAVED; applyOutputOrientation() }
                                    photos.forEach { photo ->
                                        val message = "${if (current) "SAVED" else "CAPTURE_LATE_SAVED"} token=$token mime=${photo.format.mimeType} uri=${photo.uri} name=${photo.name} bytes=${photo.bytes} folder=${PhotoPublisher.RELATIVE_PATH} viewerValidity=UNVERIFIED"
                                        if (current) event(message) else log.write(message)
                                    }
                                    log.write("CAPTURE_COMPLETE token=$token files=${photos.joinToString { it.name }}")
                                    if (current) finishStillPhoto(true)
                                }
                            } catch (e: Exception) { main.execute { failed(e) } }
                            finally { clean(staging); if (photoRollSaveClosed) photoRollCaptureClosed.set(true) }
                        }
                    } catch (e: Exception) { fail(e) }
                }
                override fun onError(exception: ImageCaptureException) { fail(exception) }
            }
            fun options(file: File) = ImageCapture.OutputFileOptions.Builder(file).build()
            if (preparedToken != null) {
                stillExposure = stillExposure?.singleStarted()
                check(stillExposure?.singleIssued == true) { "Capture settings not accepted for this revision" }
                cameraRequestIssued = true
                armPhotoDeadline(token)
            }
            log.write("CAPTURE_SINGLE_ISSUED token=$token preparationAfterAppRequestMs=${SystemClock.elapsedRealtime() - appCaptureStart}; output callback time logged separately")
            photoRollCameraXIssued = true
            when (captureFormat) {
                ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> capture.takePicture(options(raw), options(jpeg), main, callback)
                ImageCapture.OUTPUT_FORMAT_RAW -> capture.takePicture(options(raw), main, callback)
                else -> capture.takePicture(options(jpeg), main, callback)
            }
        } catch (e: Exception) {
            mediaIo.execute { clean(staging); if (!photoRollCameraXIssued) photoRollCaptureClosed.set(true) }
            failed(e)
        }
    }

    /** RAW only: durable per-format receipt first, shared IO after capture/preview recovery. */
    private fun captureRawFiles(capture: ImageCapture, token: String, prepared: Boolean) {
        val queue = rawQueue ?: return
        val generation = epoch
        val pair = format == ImageCapture.OUTPUT_FORMAT_RAW_JPEG
        val captureSettings = stillExposure?.captureTarget
        var issued = false
        val photoRollCallbacksFinished = AtomicBoolean(true) // No CameraX request until explicitly issued below.
        val photoRollExpected = if (pair) setOf(RawOutput.DNG, RawOutput.JPEG) else setOf(RawOutput.DNG)
        val photoRollReceived = mutableSetOf<RawOutput>() // CameraX callbacks and observation run on main.
        val presentation = rawPresentation.request(token) ?: rawPresentation.begin(token, captureSettings != null)
        busy = true; activeCaptureToken = token; photoStage = PhotoStage.CAPTURING
        lastFailure = null
        fun current() = !closed && generation == epoch && activeCaptureToken == token && rawPresentation.ownsCapture(presentation)
        fun latest() = !closed && generation == epoch && rawPresentation.ownsSharedNotice(presentation)
        fun failed(failure: Exception) {
            if (!current()) { log.write("RAW_LATE_FAILURE type=${failure.javaClass.simpleName}"); return }
            rawPresentation.failed(presentation)
            cancelPhotoDeadline()
            activeRawTicket = null
            busy = false; activeCaptureToken = null; photoStage = PhotoStage.FAILED
            lastFailure = "RAW撮影・受領を完了できません。残ったデータは保持しています"
            event(lastFailure!!)
            if (captureSettings != null) {
                if (issued) finishStillPhoto(false) else failCapturePreparation("RAW出力を準備できません・撮影未開始")
            } else { controlReady = true; applyOutputOrientation() }
        }
        val observer = RawCaptureObserver { ticket, notice ->
            when (notice) {
                is RawCaptureNotice.Prepared -> {
                    if (!current()) queue.interrupt(ticket)
                    else try {
                        val callback = object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                val output = when (result.imageFormat) {
                                    ImageFormat.RAW_SENSOR -> RawOutput.DNG
                                    ImageFormat.JPEG -> RawOutput.JPEG
                                    else -> null
                                }
                                if (output == null) queue.interrupt(ticket, IllegalArgumentException("Unexpected RAW output format"))
                                else {
                                    photoRollReceived += output
                                    if (photoRollReceived.containsAll(photoRollExpected)) photoRollCallbacksFinished.set(true)
                                    queue.received(ticket, output)
                                }
                            }
                            override fun onError(exception: ImageCaptureException) = queue.interrupt(ticket, exception)
                        }
                        fun options(output: RawOutput) = ImageCapture.OutputFileOptions.Builder(notice.outputs.getValue(output)).build()
                        if (prepared) {
                            stillExposure = stillExposure?.singleStarted()
                            check(stillExposure?.singleIssued == true) { "Capture settings not accepted" }
                        }
                        issued = true
                        check(rawPresentation.issued(presentation)) { "RAW presentation owner changed" }
                        cancelPhotoDeadline()
                        val budget = controlBudgetMs(captureSettings?.plan?.capture?.exposureNs,
                            exposureDurations.previous(selectedId), sample?.frameDurationNs)
                        photoDeadline = Runnable {
                            photoDeadline = null
                            if (current()) {
                                rawPresentation.unknown(presentation)
                                // Unknown sensor/request completion retains control ownership until callback or Stop.
                                stillExposure = stillExposure?.unknown("RAW撮影結果が不明です。接続停止で復旧できます")
                                busy = false; controlReady = false; photoStage = PhotoStage.FAILED
                                event("RAW撮影結果を確認できません。保存済みデータを保持し、接続停止で復旧できます")
                                queue.interrupt(ticket)
                            }
                        }.also { controlHandler.postDelayed(it, budget) }
                        photoRollCallbacksFinished.set(false)
                        if (pair) capture.takePicture(options(RawOutput.DNG), options(RawOutput.JPEG), main, callback)
                        else capture.takePicture(options(RawOutput.DNG), main, callback)
                        log.write("RAW_CAPTURE_ISSUED pair=$pair targetRotation=${outputOrientation.applied}; callbacks/receipts separate from shared handoff")
                    } catch (failure: Exception) { queue.interrupt(ticket, failure) }
                }
                is RawCaptureNotice.Received -> {
                    val receipt = notice.receipt
                    val completion = if (current()) rawPresentation.received(presentation, receipt) else null
                    if (completion != null) {
                        cancelPhotoDeadline()
                        activeRawTicket = null
                        busy = false; activeCaptureToken = null
                        val interrupted = completion.interrupted
                        photoStage = if (interrupted) PhotoStage.FAILED else PhotoStage.SAVING
                        event(if (interrupted) "中断した撮影の受領データを保全しました。DNG共有保存は別途確認します"
                            else "RAWを受領しました。DNGを共有保存しています")
                        finishStillPhoto(!interrupted)
                        if (completion.restoreAutoPreview) { controlReady = true; applyOutputOrientation() }
                    }
                }
                is RawCaptureNotice.Shared -> if (latest()) {
                    if (notice.record.phase == DngPublicationPhase.SHARED_ONLY) {
                        photoStage = if (notice.interrupted) PhotoStage.FAILED else PhotoStage.SAVED
                        event(if (notice.interrupted) "中断した撮影のDNG原本を共有保存しました"
                            else if (pair) "DNGを共有保存しました。撮影JPEGはアプリ内に保持しています" else "DNGを共有保存しました")
                    } else if (notice.record.phase == DngPublicationPhase.UNKNOWN) {
                        photoStage = PhotoStage.FAILED
                        event("DNG共有保存を確認できません。元データを保持しています")
                    }
                }
                is RawCaptureNotice.Failed -> {
                    if (notice.stage == "shared") {
                        if (latest()) {
                            photoStage = PhotoStage.FAILED
                            event("DNG共有保存を完了できません。保存データを保持し、再開を待ちます")
                        }
                    } else failed(notice.failure)
                }
            }
        }
        rawObserver = observer // Queue stores a weak observer, never an Activity owner.
        cancelPhotoDeadline()
        val preparationBudget = controlBudgetMs(captureSettings?.plan?.capture?.exposureNs,
            exposureDurations.previous(selectedId), sample?.frameDurationNs)
        photoDeadline = Runnable {
            photoDeadline = null
            if (current() && !issued) {
                activeRawTicket?.let { queue.interrupt(it) }
                failed(java.util.concurrent.TimeoutException("RAW output preparation timed out"))
            }
        }.also { controlHandler.postDelayed(it, preparationBudget) }
        try {
            val ticket = queue.prepare(token, photoRollExpected, observer)
            activeRawTicket = ticket
            // Keep only ticket/atomic state, never the Activity or its observer, in this process lease.
            AndroidPhotoRollCameraObservations.holdCaptureUntil {
                photoRollCallbacksFinished.get() && ticket.writerReleased.get()
            }
        } catch (failure: Exception) { failed(failure) }
    }

    fun close() {
        photoRollHostMayStart = false
        photoRollRebind = true
        photoRollCloseAcknowledged = false
        rawPresentation.invalidate()
        activeRawTicket?.let { rawQueue?.interrupt(it) }; activeRawTicket = null
        rawObserver = null
        stillResumeIntent.clear(); pendingForegroundBind = false
        pendingBindControls.clear()
        clearStillExposure()
        invalidateControl(); clearLiveExposure()
        closed = true; controlReady = false; activeCaptureToken = null; ++epoch
        autofocusObservations = AutofocusObservations(epoch = epoch)
        cine.stop("activity destroyed")
        try { removeThermalListener?.invoke(); removeThermalListener = null }
        catch (failure: Exception) { log.write("THERMAL_REMOVE_ERROR ${failure.message}") }
        try { clearRetainedInterop() } catch (failure: Exception) { log.write("CLOSE_INTEROP_CLEAR_FAILED " + failure.message) }
        try { camera?.cameraInfo?.cameraState?.removeObservers(owner); provider?.unbindAll(); photoRollCloseAcknowledged = true }
        catch (e: Exception) { photoRollRebind = null; log.write("ERROR close ${e.javaClass.simpleName}: ${e.message}") }
        bound = false
        refreshRawAdmission()
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
