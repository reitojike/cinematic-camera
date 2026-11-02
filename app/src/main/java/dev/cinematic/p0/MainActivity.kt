package dev.cinematic.p0

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    private lateinit var probe: CameraProbe
    private var permission by mutableStateOf(false)
    private var pendingAudioRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = CameraProbe(this, this)
        permission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            MaterialTheme {
                var resetConfirm by remember { mutableStateOf(false) }
                if (resetConfirm) AlertDialog(onDismissRequest = { resetConfirm = false }, title = { Text("Reset all capture controls?") },
                    text = { Text("Exposure, WB, MF, anti-banding, locks and EV will return to defaults.") },
                    confirmButton = { TextButton(onClick = { resetConfirm = false; probe.resetAllControls() }) { Text("Reset all") } },
                    dismissButton = { TextButton(onClick = { resetConfirm = false }) { Text("Cancel") } })
                var reportPickerPending by rememberSaveable { mutableStateOf(false) }
                val reportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                    reportPickerPending = false
                    if (uri == null) probe.reportPickerCancelled() else probe.exportVerificationReport(uri)
                }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    permission = granted
                    probe.log.write("PERMISSION granted=$granted")
                }
                val microphoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    val requested = pendingAudioRequest
                    pendingAudioRequest = false
                    probe.reportAudioPermission(granted)
                    if (granted && requested) probe.startRecording()
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cinematic Camera — P0.5 probe", style = MaterialTheme.typography.titleLarge)
                        Text("Pixel 9a / CameraX 1.6.2 / ${BuildConfig.VERSION_NAME}")
                        Button(onClick = {
                            try {
                                reportPickerPending = true
                                reportLauncher.launch("cinematic-verification-${System.currentTimeMillis()}.txt")
                            } catch (failure: Exception) { reportPickerPending = false; probe.reportPickerFailure(failure) }
                        }, enabled = !reportPickerPending && !probe.exportingReport && !probe.busy && !probe.recordingBusy) { Text("Save verification report") }
                        Text("Report contains device build, settings and a bounded log tail; no media bytes. Review before sharing. No automatic upload.")
                        if (!permission) {
                            Text("Camera permission required. If denied permanently, enable Camera in Android app settings.")
                            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera permission") }
                        } else {
                            AndroidView(factory = { context ->
                                PreviewView(context).also {
                                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    it.scaleType = PreviewView.ScaleType.FIT_CENTER
                                    it.installFocusTap { x, y -> probe.focusAt(x, y) }
                                    probe.initialize(it)
                                }
                            }, modifier = Modifier.fillMaxWidth().height(240.dp))
                            Text("When idle, tap the visible camera image for AF/AE; avoid black bars. Invalid/unsupported points are rejected, but placement needs device verification. Center AF/AE button is also available.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = probe::bind, enabled = !probe.busy && !probe.recordingBusy && !probe.bound) { Text("Start") }
                                Button(onClick = probe::stop, enabled = probe.bound) { Text("Stop") }
                            }
                            Row {
                                CaptureMode.entries.forEach { mode ->
                                    TextButton(onClick = { probe.selectMode(mode) }, enabled = !probe.busy && !probe.recordingBusy) {
                                        Text("${if (probe.mode == mode) "● " else ""}$mode")
                                    }
                                }
                            }
                            Text("Camera ID (Camera2-only IDs show an unsupported reason when selected)")
                            probe.reports.forEach { report ->
                                TextButton(onClick = { probe.selectCamera(report.id) }, enabled = !probe.busy && !probe.recordingBusy) {
                                    Text("${if (report.id == probe.selectedId) "● " else ""}Camera ${report.id}")
                                }
                            }
                            if (probe.mode == CaptureMode.STILL) {
                            Text("Output: ${CameraProbe.formatName(probe.format)}. Rebind clears Interop then restores this mode's saved exposure.")
                            Row {
                                listOf(ImageCapture.OUTPUT_FORMAT_JPEG, ImageCapture.OUTPUT_FORMAT_RAW,
                                    ImageCapture.OUTPUT_FORMAT_RAW_JPEG).forEach { f ->
                                    TextButton(onClick = { probe.selectFormat(f) },
                                        enabled = !probe.busy && !probe.recordingBusy && f in probe.formats) { Text(CameraProbe.formatName(f)) }
                                }
                            }
                            if (ImageCapture.OUTPUT_FORMAT_RAW !in probe.formats) Text("RAW unavailable: CameraX did not advertise OUTPUT_FORMAT_RAW.")
                            if (ImageCapture.OUTPUT_FORMAT_RAW_JPEG !in probe.formats) Text("RAW+JPEG unavailable: CameraX did not advertise OUTPUT_FORMAT_RAW_JPEG.")
                            Row {
                                Checkbox(probe.highResolution, probe::chooseHighResolution, enabled = !probe.busy && !probe.recordingBusy)
                                Text("Prefer highest available still resolution (may be slower)")
                            }
                            Text(probe.stillResolution)
                            } else {
                                Text("Video selection: ${probe.videoSelection}")
                                Text("Supported combinations (device quality/HDR/audio still unverified)")
                                probe.videoChoices.forEach { choice ->
                                    TextButton(onClick = { probe.selectVideo(choice.key) }, enabled = !probe.busy && !probe.recordingBusy) {
                                        Text(choice.label)
                                    }
                                }
                                Row {
                                    Checkbox(probe.audioWanted, probe::setAudio, enabled = !probe.busy && !probe.recordingBusy)
                                    Text("Record audio (permission requested on Record)")
                                }
                                Text("${probe.recordingStage}: ${probe.recordingStats}")
                                Button(onClick = {
                                    if (probe.audioWanted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        pendingAudioRequest = true
                                        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else probe.startRecording()
                                }, enabled = probe.bound && probe.controlReady && !probe.busy && !probe.recordingBusy) { Text("Record") }
                                Button(onClick = probe::stopRecording,
                                    enabled = probe.recordingStage == RecordingStage.STARTING || probe.recordingStage == RecordingStage.RECORDING) { Text("Stop recording") }
                            }
                            OutlinedTextField(probe.requestedIso, probe::editIso, label = { Text("Requested ISO (${probe.mode})") }, singleLine = true,
                                enabled = !probe.busy && !probe.recordingBusy, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(probe.requestedMs, probe::editMs, label = { Text("Requested exposure ms (${probe.mode})") },
                                singleLine = true, enabled = !probe.busy && !probe.recordingBusy, modifier = Modifier.fillMaxWidth())
                            val reason = probe.selectedReport?.manualBlockReason
                            reason?.let { Text("MANUAL unavailable: $it") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = probe::setExposureAuto, enabled = probe.bound && !probe.busy && !probe.recordingBusy) { Text("Exposure AUTO") }
                                TextButton(onClick = { resetConfirm = true }, enabled = probe.bound && !probe.busy && !probe.recordingBusy) { Text("Reset all...") }
                                Button(onClick = { probe.setExposure(probe.requestedIso, probe.requestedMs) },
                                    enabled = probe.bound && !probe.busy && !probe.recordingBusy && probe.selectedReport != null && reason == null) { Text("Apply MANUAL") }
                            }
                            Text("Desired: ${probe.desired}")
                            Text("EV: ${probe.evStatus}")
                            Row {
                                Button(onClick = { probe.compensateEv(-1) }, enabled = probe.bound && probe.controlReady && !probe.manualExposure && !probe.busy && !probe.recordingBusy) { Text("EV −") }
                                Button(onClick = { probe.compensateEv(1) }, enabled = probe.bound && probe.controlReady && !probe.manualExposure && !probe.busy && !probe.recordingBusy) { Text("EV +") }
                            }
                            Button(onClick = probe::focusCenter, enabled = probe.bound && probe.controlReady && !probe.busy && !probe.recordingBusy) { Text("Center AF / AE") }
                            Text("Focus: ${probe.focusStatus}")
                            val overrideCaps = probe.selectedReport?.overrides ?: OverrideCapabilities()
                            val controlsEnabled = probe.bound && probe.controlReady && !probe.busy && !probe.recordingBusy
                            Text("WB preset (CameraX default / reported supported modes)")
                            ControlMenu("WB", probe.overrides.whiteBalance, overrideCaps.whiteBalanceModes,
                                controlsEnabled, SensorModeNames::whiteBalance, probe::selectWhiteBalance)
                            Text("MF lens units: 0..${overrideCaps.manualFocusMaximum ?: "unavailable"}; calibration=${overrideCaps.focusCalibration}. Uncalibrated units do not guarantee meters.")
                            OutlinedTextField(probe.requestedFocus, probe::editFocus, singleLine = true,
                                label = { Text("Requested focus lens units") }, enabled = controlsEnabled && overrideCaps.manualFocusMaximum != null)
                            Row {
                                Button(onClick = probe::applyManualFocus, enabled = controlsEnabled && overrideCaps.manualFocusMaximum != null) { Text("Apply MF") }
                                Button(onClick = probe::restoreAutofocus, enabled = controlsEnabled) { Text("AF / default") }
                            }
                            Text("AE anti-banding: affects AUTO AE only; cannot guarantee removal of LED/PWM bands")
                            ControlMenu("Anti-banding", probe.overrides.antiBanding, overrideCaps.antiBandingModes,
                                controlsEnabled, SensorModeNames::antiBanding, probe::selectAntiBanding)
                            Text("Control snapshot: ${probe.overrideStatus}")
                            val lockCaps = probe.selectedReport?.locks ?: LockCapabilities()
                            val autoWb = probe.overrides.whiteBalance == null || probe.overrides.whiteBalance == 1
                            Row {
                                Button(onClick = probe::toggleExposureLock, enabled = controlsEnabled &&
                                    (probe.autoLocks.exposure || lockCaps.exposure && !probe.manualExposure)) {
                                    Text(if (probe.autoLocks.exposure) "Release AE lock" else "AE lock")
                                }
                                Button(onClick = probe::toggleWhiteBalanceLock, enabled = controlsEnabled &&
                                    (probe.autoLocks.whiteBalance || lockCaps.whiteBalance && autoWb)) {
                                    Text(if (probe.autoLocks.whiteBalance) "Release AWB lock" else "AWB lock")
                                }
                            }
                            Text("Locks: ${probe.lockStatus}")
                            Text("Available AE=${lockCaps.exposure} / AWB=${lockCaps.whiteBalance}. AUTO algorithms only. Locks are not saved and clear on rebind/reset. EV may still adjust exposure while AE is locked.")
                            Text("Thermal: ${probe.thermalStatus}")
                            Text("CaptureResult: ${probe.measured}")
                            if (!probe.sharedSaveSupported) Text("Shared photo saving requires Android 10 or newer.")
                            if (probe.mode == CaptureMode.STILL) Button(onClick = probe::capture, enabled = probe.bound && probe.controlReady && probe.sharedSaveSupported && !probe.busy && !probe.recordingBusy) { Text("Capture ${CameraProbe.formatName(probe.format)}") }
                        }
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("State: ${probe.status}${if (probe.busy) " [operation pending]" else ""}")
                                Text("Log: ${probe.log.file.absolutePath}\nImages: ${PhotoPublisher.RELATIVE_PATH}\nVideos: Movies/Cinematic Camera/ (MediaStore)")
                                Text("CameraX output capabilities (separate from Camera2)\n${probe.cameraXReports}")
                                Text("Camera2 characteristics\n${probe.reports.joinToString("\n\n") { it.text }}")
                                Text("Recent log (full session saved privately)\n${probe.lines}")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (::probe.isInitialized) probe.log.write("LIFECYCLE onResume permission=$permission")
    }
    override fun onStop() {
        if (::probe.isInitialized) probe.onBackground()
        if (::probe.isInitialized) probe.log.write("LIFECYCLE onStop; CameraX lifecycle owns suspension")
        super.onStop()
    }
    override fun onPause() {
        if (::probe.isInitialized) probe.onBackground()
        super.onPause()
    }
    override fun onDestroy() {
        if (::probe.isInitialized) probe.close()
        super.onDestroy()
    }
}

@Composable
private fun ControlMenu(label: String, selected: Int?, modes: Set<Int>, enabled: Boolean,
    name: (Int) -> String, choose: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$label: ${selected?.let(name) ?: "CameraX default"}")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("CameraX default") }, onClick = { expanded = false; choose(null) })
            modes.sorted().forEach { mode ->
                DropdownMenuItem(text = { Text(name(mode)) }, onClick = { expanded = false; choose(mode) })
            }
        }
    }
}
