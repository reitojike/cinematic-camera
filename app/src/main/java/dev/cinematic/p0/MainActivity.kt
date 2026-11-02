package dev.cinematic.p0

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : ComponentActivity() {
    private lateinit var probe: CameraProbe
    private var permission by mutableStateOf(false)
    private var pendingAudioRequest = false
    private var orientation by mutableStateOf(CaptureOrientation())
    private var orientationListener: android.view.OrientationEventListener? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        probe = CameraProbe(this, this)
        orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val next = orientation.observe(degrees)
                if (next != orientation) {
                    orientation = next
                    probe.setDeviceRotation(next.surfaceRotation)
                }
            }
        }
        permission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            CaptureTheme {
                var reportPending by rememberSaveable { mutableStateOf(false) }
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
                    if (granted && requested) probe.startRecording()
                }
                CaptureScreen(probe, permission, orientation,
                    requestPermission = { camera.launch(Manifest.permission.CAMERA) },
                    record = {
                        if (probe.audioWanted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            pendingAudioRequest = true; microphone.launch(Manifest.permission.RECORD_AUDIO)
                        } else probe.startRecording()
                    },
                    export = {
                        if (!reportPending) try {
                            reportPending = true; report.launch("cinematic-verification-${System.currentTimeMillis()}.txt")
                        } catch (failure: Exception) { reportPending = false; probe.reportPickerFailure(failure) }
                    }, reportPending = reportPending, exit = ::finish)
            }
        }
    }
    override fun onStart() {
        super.onStart()
        orientationListener?.let { if (it.canDetectOrientation()) it.enable() }
    }
    override fun onResume() {
        super.onResume()
        val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (permission && !granted && ::probe.isInitialized) probe.stop()
        permission = granted
        if (::probe.isInitialized && probe.audioPermissionDenied && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            probe.reportAudioPermission(true)
        }
    }
    override fun onStop() { orientationListener?.disable(); if (::probe.isInitialized) probe.onBackground(); super.onStop() }
    override fun onPause() { if (::probe.isInitialized) probe.onBackground(); super.onPause() }
    override fun onDestroy() { orientationListener?.disable(); orientationListener = null; if (::probe.isInitialized) probe.close(); super.onDestroy() }
}
