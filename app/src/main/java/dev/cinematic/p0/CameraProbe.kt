package dev.cinematic.p0

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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

@androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])
class CameraProbe(private val context: Context, private val owner: LifecycleOwner) {
    private val main = ContextCompat.getMainExecutor(context)
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
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var view: PreviewView? = null
    private var epoch = 0
    private var closed = false
    private var activeCaptureToken: String? = null
    val sharedSaveSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val selectedReport get() = reports.find { it.id == selectedId }
    init { log.onLine = { line -> main.execute { if (!closed) lines = (lines + "\n" + line).takeLast(16000) } } }

    private fun event(message: String) { status = message; log.write(message) }
    private fun error(operation: String, e: Exception) {
        event("ERROR $operation ${e.javaClass.simpleName}: ${e.message}")
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
                    selectedId = available.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                        ?.let { Camera2CameraInfo.from(it).cameraId } ?: ids.firstOrNull()
                    bind()
                } catch (e: Exception) { error("initialize", e) }
            }, main)
        } catch (e: Exception) { error("capability enumeration", e) }
    }

    fun selectCamera(id: String) { if (!busy) { selectedId = id; format = ImageCapture.OUTPUT_FORMAT_JPEG; bind() } }
    fun selectFormat(value: Int) {
        if (busy) return
        if (value !in formats) { event("UNSUPPORTED ${formatName(value)} by CameraX id=$selectedId"); return }
        format = value; bind()
    }

    fun bind() {
        if (busy || closed) return
        val p = provider ?: return
        val id = selectedId ?: run { event("UNSUPPORTED no CameraX camera"); return }
        val previewView = view ?: return
        val generation = ++epoch
        camera?.cameraInfo?.cameraState?.removeObservers(owner)
        bound = false; controlReady = false; formats = emptySet()
        camera = null; imageCapture = null
        desired = "AUTO reset required"; measured = "Awaiting new session result"
        try {
            p.unbindAll()
            val info = p.availableCameraInfos.firstOrNull { Camera2CameraInfo.from(it).cameraId == id }
                ?: run { event("UNSUPPORTED CameraX unavailable id=$id; Camera2 report only"); return }
            formats = ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
            // Never construct RAW ImageCapture until CameraX has advertised that exact output format.
            require(format in formats) { "CameraX ${formatName(format)} unsupported id=$id" }
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
                    val actual = "frame=${result.frameNumber} sensorTimestamp=${result.get(CaptureResult.SENSOR_TIMESTAMP)} intent=${request.get(CaptureRequest.CONTROL_CAPTURE_INTENT)} requestAE=${request.get(CaptureRequest.CONTROL_AE_MODE)} requestISO=${request.get(CaptureRequest.SENSOR_SENSITIVITY)} requestNs=${request.get(CaptureRequest.SENSOR_EXPOSURE_TIME)} actualAE=${result.get(CaptureResult.CONTROL_AE_MODE)} actualISO=${result.get(CaptureResult.SENSOR_SENSITIVITY)} actualNs=${result.get(CaptureResult.SENSOR_EXPOSURE_TIME)} frameDurationNs=${result.get(CaptureResult.SENSOR_FRAME_DURATION)}"
                    main.execute {
                        if (generation == epoch && !closed) {
                            measured = actual
                            log.write("RESULT id=$id desired=[$desired] $actual")
                        }
                    }
                }
            }
            Camera2Interop.Extender(builder).setSessionCaptureCallback(resultCallback)
            val preview = builder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val captureBuilder = ImageCapture.Builder().setOutputFormat(format)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            Camera2Interop.Extender(captureBuilder).setSessionCaptureCallback(resultCallback)
            val capture = captureBuilder.build()
            camera = p.bindToLifecycle(owner, selector, preview, capture)
            imageCapture = capture; bound = true
            camera!!.cameraInfo.cameraState.observe(owner) { state ->
                if (generation == epoch && !closed) {
                    log.write("CAMERA_STATE id=$id type=${state.type} error=${state.error?.code}")
                    state.error?.let { event("ERROR CameraX camera state code=${it.code}") }
                }
            }
            event("BOUND id=$id format=${formatName(format)}; clearing Interop before capture")
            // The provider may reuse a CameraControl whose Interop options survive unbind/rebind.
            // Explicitly clear them and wait for acceptance before enabling capture.
            setExposure()
        } catch (e: Exception) { error("bind id=$id format=${formatName(format)} (no fallback attempted)", e) }
    }

    fun stop() {
        ++epoch
        busy = false
        controlReady = false
        activeCaptureToken = null
        try {
            camera?.cameraInfo?.cameraState?.removeObservers(owner)
            provider?.unbindAll(); camera = null; imageCapture = null; bound = false
            desired = "AUTO (unbound)"; event("Preview stopped")
        } catch (e: Exception) { error("stop", e) }
    }

    fun setExposure(iso: String? = null, ms: String? = null) {
        if (busy) return
        val c = camera ?: return
        val manual = iso != null && ms != null
        try {
            val r = selectedReport
            val value = if (manual) {
                require(r != null) { "No Camera2 report" }
                require(r.manualBlockReason == null) { r.manualBlockReason!! }
                Exposure.parse(iso, ms, r.isoRange, r.exposureRange)
            } else null
            val label = value?.let { "MANUAL ISO=${it.iso} exposureNs=${it.exposureNs}" } ?: "AUTO (Interop cleared)"
            desired = "$label PENDING"; busy = true; controlReady = false
            log.write("DESIRED id=$selectedId $desired")
            val generation = epoch
            val control = Camera2CameraControl.from(c.cameraControl)
            val future = if (value == null) control.clearCaptureRequestOptions() else {
                control.setCaptureRequestOptions(CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, value.iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, value.exposureNs).build())
            }
            future.addListener({
                if (generation != epoch || closed) return@addListener
                busy = false
                try {
                    future.get(); controlReady = true
                    desired = "$label ACCEPTED (check actual result)"; event(desired)
                }
                catch (e: Exception) {
                    desired = "$label FAILED; sensor state unknown; use AUTO or stop/restart"
                    error("exposure", e)
                }
            }, main)
        } catch (e: Exception) {
            if (busy) desired = "Exposure control FAILED; sensor state unknown; use AUTO or stop/restart"
            busy = false; error("exposure validation/control", e)
        }
    }

    fun capture() {
        if (busy) return
        if (!sharedSaveSupported) { event("UNSUPPORTED shared image saving requires Android 10+"); return }
        if (!controlReady) { event("Capture disabled: exposure control not accepted; use AUTO or stop/restart"); return }
        val capture = imageCapture ?: return
        val token = UUID.randomUUID().toString()
        val generation = epoch
        val cameraId = selectedId
        val captureFormat = format
        busy = true; activeCaptureToken = token
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
            if (isCurrent()) {
                busy = false; activeCaptureToken = null
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
            log.write("CAPTURE_BEGIN token=$token id=$cameraId format=${formatName(captureFormat)} desired=[$desired] target=${PhotoPublisher.RELATIVE_PATH}")
            val callback = object : ImageCapture.OnImageSavedCallback {
                private fun fail(e: Exception) {
                    if (terminal) { log.write("CAPTURE_LATE_CALLBACK_ERROR token=$token ${e.javaClass.simpleName}"); return }
                    terminal = true
                    mediaIo.execute { clean(staging) }
                    failed(e)
                }
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    try {
                        if (terminal) {
                            if (!publishing) mediaIo.execute { clean(staging) }
                            return
                        }
                        check(result.imageFormat in expectedFormats) { "Unexpected camera output format ${result.imageFormat}" }
                        if (!received.add(result.imageFormat) || received != expectedFormats) return
                        terminal = true; publishing = true
                        // CameraX has finished every file. Publish together off the UI thread.
                        mediaIo.execute {
                            try {
                                val photos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    PhotoPublisher(AndroidImageStore(context.applicationContext.contentResolver)).publish(sources)
                                } else throw IllegalStateException("MediaStore saving requires Android 10+")
                                main.execute {
                                    val current = isCurrent()
                                    if (current) { busy = false; activeCaptureToken = null }
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
