package dev.cinematic.p0

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.cinematic.p0.photoroll.PhotoRollActivity
import dev.cinematic.p0.photoroll.canOpenPhotoRoll

class MainActivity : ComponentActivity() {
    private lateinit var probe: CameraProbe
    private var permission by mutableStateOf(false)
    private var pendingAudioRequest = false
    private var orientation = CaptureOrientation()
    private var uiOrientation by mutableStateOf(CaptureOrientation())
    private var autoRotateEnabled = false
    private var observingRotationSetting = false
    private val rotationSettingObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (observingRotationSetting) refreshRotationSetting()
        }
    }
    private var orientationListener: android.view.OrientationEventListener? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        probe = CameraProbe(this, this)
        refreshRotationSetting()
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val next = orientation.observe(degrees)
                if (next != orientation) {
                    orientation = next
                    uiOrientation = next.forUi(autoRotateEnabled, uiOrientation)
                    // Capture orientation follows the device independently of the UI setting.
                    probe.setDeviceRotation(next.surfaceRotation)
                }
            }
        }
        permission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            CaptureTheme {
                var reportPending by rememberSaveable { mutableStateOf(false) }
                var photosPending by rememberSaveable { mutableStateOf(false) }
                val photos = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { photosPending = false }
                val report = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                    reportPending = false
                    if (uri == null) probe.reportPickerCancelled() else probe.exportVerificationReport(uri)
                }
                val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    permission = granted; probe.log.write("PERMISSION granted=$granted")
                }
                val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    val requested = pendingAudioRequest; pendingAudioRequest = false
                    probe.reportAudioPermission(granted)
                    if (granted && requested && !photosPending) probe.startRecording()
                }
                CaptureScreen(probe, permission, uiOrientation,
                    requestPermission = { camera.launch(Manifest.permission.CAMERA) },
                    record = {
                        if (!photosPending) {
                            if (probe.audioWanted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                pendingAudioRequest = true; microphone.launch(Manifest.permission.RECORD_AUDIO)
                            } else probe.startRecording()
                        }
                    },
                    export = {
                        if (!reportPending) try {
                            reportPending = true; report.launch("cinematic-verification-${System.currentTimeMillis()}.txt")
                        } catch (failure: Exception) { reportPending = false; probe.reportPickerFailure(failure) }
                    }, reportPending = reportPending, exit = ::finish,
                    photosPending = photosPending, capture = { if (!photosPending) probe.capture() }, openPhotos = {
                        if (!photosPending && canOpenPhotoRoll(probe.busy, probe.recordingBusy, probe.stillExposure?.ownsCapture == true,
                            probe.stillExposure?.phase == StillExposurePhase.UNKNOWN || probe.controlReceipt.phase == ControlPhase.UNKNOWN)) {
                            try {
                                photosPending = true
                                photos.launch(Intent(this, PhotoRollActivity::class.java))
                            } catch (_: Exception) { photosPending = false }
                        }
                    })
            }
        }
    }
    override fun onStart() {
        if (::probe.isInitialized) probe.onHostStarting()
        super.onStart()
        if (!observingRotationSetting) {
            contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, rotationSettingObserver)
            observingRotationSetting = true
        }
        // Register before reading so a setting change cannot fall between the two.
        refreshRotationSetting()
        orientationListener?.let { if (it.canDetectOrientation()) it.enable() }
    }
    override fun onResume() {
        if (::probe.isInitialized) probe.onHostStarting()
        super.onResume()
        refreshRotationSetting()
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (permission && !granted && ::probe.isInitialized) probe.stop()
        permission = granted
        if (granted && ::probe.isInitialized) probe.onForeground()
        if (::probe.isInitialized && probe.audioPermissionDenied && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            probe.reportAudioPermission(true)
        }
    }
    private fun refreshRotationSetting() {
        autoRotateEnabled = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        // USER_ROTATION is a fallback only when no Activity orientation preference applies.
        // This portrait Activity therefore stays portrait when auto-rotate is disabled.
        uiOrientation = orientation.forUi(autoRotateEnabled, uiOrientation)
    }
    private fun stopObservingRotationSetting() {
        if (!observingRotationSetting) return
        observingRotationSetting = false
        contentResolver.unregisterContentObserver(rotationSettingObserver)
    }
    override fun onStop() { stopObservingRotationSetting(); orientationListener?.disable(); if (::probe.isInitialized) probe.onBackground(); super.onStop() }
    override fun onPause() { if (::probe.isInitialized) probe.onBackground(); super.onPause() }
    override fun onDestroy() { stopObservingRotationSetting(); orientationListener?.disable(); orientationListener = null; if (::probe.isInitialized) probe.close(); super.onDestroy() }
}
