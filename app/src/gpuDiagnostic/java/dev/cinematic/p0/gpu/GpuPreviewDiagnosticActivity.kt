package dev.cinematic.p0.gpu

import android.net.Uri
import android.os.CancellationSignal
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.gpu.preview.GpuPreviewFrame
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSurface
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.photoroll.PhotoRollResourceObserver
import dev.cinematic.p0.rawcapture.DevelopExecution
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 診断専用の合成入力と公開・合成DNG。製品の保存経路には接続しない。 */
class GpuPreviewDiagnosticActivity : ComponentActivity() {
    internal lateinit var renderer: GpuPreviewRenderer
    private val measurement = PreviewMeasurement()
    private var measuring by mutableStateOf(false)
    private var sampleCount by mutableStateOf(0)
    private var summary by mutableStateOf("未計測")
    private var error by mutableStateOf<String?>(null)
    private var allocatedBytes by mutableStateOf(0L)

    private lateinit var resourceObserver: PhotoRollResourceObserver
    private var inputSignal: CancellationSignal? = null
    private var inputEpoch = 0L
    private var resumed = false
    private var pendingDocument: Uri? = null
    private var dngSelected by mutableStateOf(false)
    private var dngLoading by mutableStateOf(false)
    private var inputStatus by mutableStateOf("公開・合成の試験用DNGのみ。個人の写真は選ばないでください。")
    // OpenDocument uses ACTION_OPEN_DOCUMENT. No persistable permission is taken.
    // https://developer.android.com/training/data-storage/shared/documents-files
    private val pickDng = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pendingDocument = uri
            if (resumed) loadPendingDocument()
        }
    }

    private fun cancelInput() {
        inputEpoch++
        inputSignal?.cancel()
        inputSignal = null
        dngLoading = false
    }

    private fun loadPendingDocument() {
        val uri = pendingDocument ?: return
        pendingDocument = null
        cancelInput()
        stopMeasurement()
        dngSelected = true
        dngLoading = true
        error = null
        inputStatus = "DNG原本を照合して入力を準備中…"
        renderer.closePhoto()
        val epoch = inputEpoch
        val signal = CancellationSignal().also { inputSignal = it }
        GpuDiagnosticDngInput.load(this, uri, resourceObserver::snapshot, signal).whenComplete { input, failure ->
            runOnUiThread {
                if (!isDestroyed && resumed && epoch == inputEpoch) {
                    inputSignal = null
                    dngLoading = false
                    if (failure == null && !signal.isCanceled) {
                        renderer.openPhoto(input)
                        inputStatus = "DNG AS_SHOT入力: ${input.width} × ${input.height}（長辺1024以下）"
                    } else {
                        // Provider/native exception messages can contain names or URIs. Never display/log them.
                        inputStatus = "DNGを読み込めません。内部ストレージの試験用DNGと端末の空き資源を確認し、選び直してください。"
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        resourceObserver.start()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resourceObserver.foreground(true)
        loadPendingDocument()
    }

    override fun onPause() {
        resumed = false
        cancelInput()
        resourceObserver.foreground(false)
        if (dngSelected) {
            renderer.closePhoto()
            inputStatus = "DNG表示を中断しました。再開するには選び直してください。"
        }
        if (dngSelected) GpuDiagnosticDngInput.release(this) // Same process-retained serial owner.
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resourceObserver = PhotoRollResourceObserver(this, DevelopExecution::updateConditions)
        renderer = GpuPreviewRenderer(assets, onFrame = { frame ->
            // Renderer callbacks are on GL thread. Compose state and sample storage belong to main.
            runOnUiThread {
                if (!isDestroyed) {
                    error = null
                    allocatedBytes = frame.allocatedBytes
                    if (measuring && measurement.add(frame)) {
                        sampleCount = measurement.count
                        if (sampleCount >= PreviewMeasurement.MAX_SAMPLES) stopMeasurement()
                    }
                }
            }
        }, onError = { failure ->
            runOnUiThread {
                if (!isDestroyed) {
                    error = failure.message ?: failure.javaClass.simpleName
                    stopMeasurement()
                }
            }
        })
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { DiagnosticScreen() }
            }
        }
    }

    private fun stopMeasurement() {
        if (measuring) {
            measuring = false
            val thermal = getSystemService(PowerManager::class.java).currentThermalStatus
            summary = measurement.summary() + "\n温度状態: $thermal"
            Log.i("GpuPreview", "$summary\nGPU allocated bytes: $allocatedBytes")
        }
    }

    override fun onStop() {
        // A background pause must not become an apparent slow render interval.
        stopMeasurement()
        resourceObserver.stop()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && dngSelected) {
            cancelInput()
            renderer.closePhoto()
            inputStatus = "DNG表示を解放しました。再開するには選び直してください。"
            GpuDiagnosticDngInput.release(this)
        }
        if (::renderer.isInitialized) renderer.trimMemory(level)
    }

    override fun onDestroy() {
        // Activity owns renderer; Surface owns its surface/lifecycle attachment only.
        // https://developer.android.com/guide/components/activities/activity-lifecycle
        if (::renderer.isInitialized) renderer.close()
        super.onDestroy()
    }

    @Composable
    private fun DiagnosticScreen() {
        var strength by rememberSaveable { mutableStateOf(1f) }
        var warmth by rememberSaveable { mutableStateOf(0f) }
        var exposure by rememberSaveable { mutableStateOf(0f) }
        var before by rememberSaveable { mutableStateOf(false) }
        var rotation by rememberSaveable { mutableStateOf(0) }
        var fixtureName by rememberSaveable { mutableStateOf(GpuPreviewDiagnosticImages.Kind.MIXED.name) }
        var choosing by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(true) }
        LaunchedEffect(fixtureName, dngSelected) {
            if (dngSelected) { loading = false; return@LaunchedEffect }
            stopMeasurement()
            loading = true
            // Compose effect cancellation prevents a stale fixture from replacing a newer choice.
            // https://developer.android.com/develop/ui/compose/side-effects#launchedeffect
            val input = withContext(Dispatchers.Default) {
                GpuPreviewDiagnosticImages.create(
                    GpuPreviewDiagnosticImages.Kind.valueOf(fixtureName),
                    GpuPreviewDiagnosticImages.PREVIEW_SIZE,
                )
            }
            if (!dngSelected) renderer.openPhoto(input)
            loading = false
        }
        LaunchedEffect(strength, warmth, exposure, before, rotation) {
            renderer.submit(
                GpuRenderParams(strength.toDouble(), PhotoAdjustments(warmth.toDouble(), exposure.toDouble())),
                GpuDisplayOptions(before, rotation),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("GPU 表示診断・製品未採用", style = MaterialTheme.typography.titleLarge)
            Text("この画面はAS_SHOT入力の表示です。全工程の表示は接続準備中です。保存なし。DNG選択は全工程計測を開始しません。")
            GpuPreviewSurface(renderer, Modifier.fillMaxWidth().height(240.dp))
            Column {
                Button(onClick = { choosing = true }) { Text("合成入力: $fixtureName") }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    GpuPreviewDiagnosticImages.Kind.entries.forEach { kind ->
                        DropdownMenuItem(text = { Text(kind.name) }, onClick = {
                            cancelInput()
                            stopMeasurement()
                            dngSelected = false
                            fixtureName = kind.name
                            choosing = false
                        })
                    }
                }
            }
            Button(onClick = {
                cancelInput()
                stopMeasurement()
                pickDng.launch(arrayOf("image/x-adobe-dng", "image/dng", "application/octet-stream"))
            }) { Text("試験用DNGをFilesから選択") }
            Text(if (dngSelected) inputStatus else if (loading) "入力を生成中…" else
                "1024 × 769 / 固定seedの合成入力。公開・合成DNGのみ選択可。個人の写真は使用しないでください。")
            DiagnosticSlider("強度", strength, 0f..1f) { strength = it }
            DiagnosticSlider("暖かさ", warmth, -100f..100f) { warmth = it }
            DiagnosticSlider("露出 EV", exposure, -2f..2f) { exposure = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Before")
                Switch(checked = before, onCheckedChange = { before = it })
                Button(onClick = { rotation = (rotation + 90) % 360 }) { Text("回転 $rotation°") }
            }
            Button(enabled = !loading && !dngSelected && !dngLoading && error == null, onClick = {
                if (measuring) stopMeasurement() else {
                    measurement.start()
                    sampleCount = 0
                    summary = "計測中: スライダー等を操作してください"
                    measuring = true
                    renderer.submit(
                        GpuRenderParams(strength.toDouble(), PhotoAdjustments(warmth.toDouble(), exposure.toDouble())),
                        GpuDisplayOptions(before, rotation),
                    )
                }
            }) { Text(if (measuring) "計測停止 ($sampleCount)" else "計測開始") }
            Text(summary)
            Text("GPU割当: $allocatedBytes bytes（renderer台帳）")
            Text("CPUは描画提出時間、GPUは取得できたサンプルのみ。フレーム間隔は描画コールバック間隔で、操作待ちを含み、画面提示FPSではありません。最大${PreviewMeasurement.MAX_SAMPLES}件で自動停止。実機の速度・画質合格を示すものではありません。")
            error?.let {
                Text("GPU診断エラー: $it", color = MaterialTheme.colorScheme.error)
                Button(onClick = { renderer.retry() }) { Text("GPUを再作成して再試行") }
            }
        }
    }
}

@Composable
private fun DiagnosticSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text("$label: ${String.format(Locale.ROOT, "%.2f", value)}")
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

/** Main-thread-only, bounded capture. GPU-null is missing data, never a zero-duration sample. */
private class PreviewMeasurement {
    private val cpu = ArrayList<Long>()
    private val gpu = ArrayList<Long>()
    private val intervals = ArrayList<Long>()
    private var startedAt = 0L
    private var previousTime: Long? = null
    private var baselineMissed: Long? = null
    private var lastMissed = 0L
    val count get() = cpu.size

    fun start() {
        cpu.clear()
        gpu.clear()
        intervals.clear()
        previousTime = null
        baselineMissed = null
        lastMissed = 0L
        startedAt = System.nanoTime()
    }

    fun add(frame: GpuPreviewFrame): Boolean {
        if (frame.frameTimeNanos < startedAt || count >= MAX_SAMPLES) return false
        cpu.add(frame.stats.cpuSubmitNanos)
        frame.stats.gpuNanos?.let { gpu.add(it) }
        previousTime?.let { previous ->
            if (frame.frameTimeNanos >= previous) intervals.add(frame.frameTimeNanos - previous)
        }
        previousTime = frame.frameTimeNanos
        if (baselineMissed == null) baselineMissed = frame.missedChangedFrames
        lastMissed = frame.missedChangedFrames
        return true
    }

    fun summary(): String = listOf(
        "描画件数: $count",
        describe("CPU提出", cpu),
        describe("GPU", gpu),
        describe("描画フレーム間隔", intervals),
        "変更要求の未描画フレーム増分: ${maxOf(0L, lastMissed - (baselineMissed ?: lastMissed))}（最初のサンプル以降）",
    ).joinToString("\n")

    private fun describe(label: String, values: List<Long>): String {
        if (values.isEmpty()) return "$label: 未取得 (n=0)"
        val sorted = values.sorted()
        val middle = sorted.size / 2
        val median = if (sorted.size % 2 == 0) (sorted[middle - 1].toDouble() + sorted[middle]) / 2 else sorted[middle].toDouble()
        val p95 = sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)]
        return String.format(Locale.ROOT, "%s: median %.3f / p95 %.3f / max %.3f ms (n=%d)",
            label, median / 1e6, p95 / 1e6, sorted.last() / 1e6, sorted.size)
    }

    companion object { const val MAX_SAMPLES = 10_000 }
}
