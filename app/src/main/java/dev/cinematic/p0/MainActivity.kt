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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {
    private lateinit var probe: CameraProbe
    private var permission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probe = CameraProbe(this, this)
        permission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        setContent {
            MaterialTheme {
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    permission = granted
                    probe.log.write("PERMISSION granted=$granted")
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cinematic Camera — P0", style = MaterialTheme.typography.titleLarge)
                        Text("Pixel 9a / CameraX 1.6.2 / ${BuildConfig.VERSION_NAME}")
                        if (!permission) {
                            Text("Camera permission required. If denied permanently, enable Camera in Android app settings.")
                            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera permission") }
                        } else {
                            AndroidView(factory = { context ->
                                PreviewView(context).also {
                                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                                    it.scaleType = PreviewView.ScaleType.FIT_CENTER
                                    probe.initialize(it)
                                }
                            }, modifier = Modifier.fillMaxWidth().height(240.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = probe::bind, enabled = !probe.busy && !probe.bound) { Text("Start") }
                                Button(onClick = probe::stop, enabled = probe.bound) { Text("Stop") }
                            }
                            Text("Camera ID (Camera2-only IDs show an unsupported reason when selected)")
                            probe.reports.forEach { report ->
                                TextButton(onClick = { probe.selectCamera(report.id) }, enabled = !probe.busy) {
                                    Text("${if (report.id == probe.selectedId) "● " else ""}Camera ${report.id}")
                                }
                            }
                            Text("Output: ${CameraProbe.formatName(probe.format)}. Changing camera/output rebinds in AUTO.")
                            Row {
                                listOf(ImageCapture.OUTPUT_FORMAT_JPEG, ImageCapture.OUTPUT_FORMAT_RAW,
                                    ImageCapture.OUTPUT_FORMAT_RAW_JPEG).forEach { f ->
                                    TextButton(onClick = { probe.selectFormat(f) },
                                        enabled = !probe.busy && f in probe.formats) { Text(CameraProbe.formatName(f)) }
                                }
                            }
                            if (ImageCapture.OUTPUT_FORMAT_RAW !in probe.formats) Text("RAW unavailable: CameraX did not advertise OUTPUT_FORMAT_RAW.")
                            if (ImageCapture.OUTPUT_FORMAT_RAW_JPEG !in probe.formats) Text("RAW+JPEG unavailable: CameraX did not advertise OUTPUT_FORMAT_RAW_JPEG.")
                            var iso by remember { mutableStateOf("100") }
                            var ms by remember { mutableStateOf("10") }
                            OutlinedTextField(iso, { iso = it }, label = { Text("Requested ISO") }, singleLine = true,
                                enabled = !probe.busy, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(ms, { ms = it }, label = { Text("Requested exposure (ms), e.g. 8.333333") },
                                singleLine = true, enabled = !probe.busy, modifier = Modifier.fillMaxWidth())
                            val reason = probe.selectedReport?.manualBlockReason
                            reason?.let { Text("MANUAL unavailable: $it") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { probe.setExposure() }, enabled = probe.bound && !probe.busy) { Text("AUTO") }
                                Button(onClick = { probe.setExposure(iso, ms) },
                                    enabled = probe.bound && !probe.busy && probe.selectedReport != null && reason == null) { Text("Apply MANUAL") }
                            }
                            Text("Desired: ${probe.desired}")
                            Text("CaptureResult: ${probe.measured}")
                            if (!probe.sharedSaveSupported) Text("Shared photo saving requires Android 10 or newer.")
                            Button(onClick = probe::capture, enabled = probe.bound && probe.controlReady && probe.sharedSaveSupported && !probe.busy) { Text("Capture ${CameraProbe.formatName(probe.format)}") }
                        }
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("State: ${probe.status}${if (probe.busy) " [operation pending]" else ""}")
                                Text("Log: ${probe.log.file.absolutePath}\nImages: ${PhotoPublisher.RELATIVE_PATH} (MediaStore)")
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
        if (::probe.isInitialized) probe.log.write("LIFECYCLE onStop; CameraX lifecycle owns suspension")
        super.onStop()
    }
    override fun onDestroy() {
        if (::probe.isInitialized) probe.close()
        super.onDestroy()
    }
}
