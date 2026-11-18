package dev.cinematic.p0.halfcompare

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.gpu.GpuPreviewInputSize
import dev.cinematic.p0.gpu.preview.GpuPreviewSurface
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.metrics.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.photoroll.GpuPhotoRollSession
import dev.cinematic.p0.photoroll.GpuPhotoRollRetiredOwners
import dev.cinematic.p0.rawcapture.DngContentVerifier
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit local-only ABBA. No pixels/readback/Bitmap/report files, no product entry or settings. */
class PerformanceCompareActivity : ComponentActivity() {
    private val generation = CompareGeneration()
    private var stop: ComparisonStop? = null
    private lateinit var status: TextView
    private lateinit var surface: ComposeView
    private lateinit var local: CheckBox
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile internal var createInput: (android.content.Context, Uri, ComparisonStop) -> PerformanceInput =
            { context, uri, stop -> PerformanceInput.prepare(context, uri, stop) }
        internal val order = listOf(PreviewRasterMode.FULL, PreviewRasterMode.HALF_CANDIDATE,
            PreviewRasterMode.HALF_CANDIDATE, PreviewRasterMode.FULL)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        check(BuildConfig.DEBUG && BuildConfig.PRIVATE_HALF_COMPARE && packageName.endsWith(".halfcompare"))
        check(!BuildConfig.INITIAL_HALF_GUIDE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val padding = (16 * resources.displayMetrics.density).toInt()
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom); insets
        }
        column.addView(TextView(this).apply { text = "FULL / HALF 速度比較（未採用）\n強さ0.5・0 EV、FULL→HALF→HALF→FULL。写真選択と最初のコピーは計測外です。製品全体の待ち時間ではありません。cache未制御。" })
        local = CheckBox(this).apply { text = "既知の端末内DNGを選びます（クラウド不可）" }
        column.addView(local)
        column.addView(Button(this).apply { text = "DNG を選んで4回計測"; setOnClickListener {
            if (local.isChecked) {
                cancel()
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, 1)
            }
        } })
        column.addView(Button(this).apply { text = "取消・閉じる"; setOnClickListener { cancel(); finish() } })
        status = TextView(this).apply { text = "既知のローカル写真だけを選択してください。結果はこの画面内のみです。" }
        column.addView(ScrollView(this).apply { addView(status) }, LinearLayout.LayoutParams(-1, 240))
        surface = ComposeView(this)
        column.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column); column.requestApplyInsets()
    }
    @Deprecated("Standalone evaluation activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && local.isChecked) data?.data?.let(::start)
    }
    private fun start(uri: Uri) {
        cancel()
        val ticket = generation.next()
        val cancellation = ComparisonStop().also { stop = it }
        status.text = "読み取り準備中（計測外）"
        executor.execute {
            var lease: AutoCloseable? = null
            var input: PerformanceInput? = null
            try {
                lease = ComparisonLease.acquire()
                input = createInput(applicationContext, uri, cancellation)
                val summary = StringBuilder("OPEN→通常swap。cache未制御、各2標本。\n")
                for ((index, mode) in order.withIndex()) {
                    cancellation.check()
                    update(ticket, "${index + 1}/4 ${mode.name} 計測中")
                    summary.append(run(input, mode, cancellation, ticket)).append('\n')
                }
                input.verifyOriginal()
                input.close(); input = null
                cancellation.check()
                update(ticket, "完了・一時データ解放済み\n$summary")
            } catch (_: Throwable) {
                update(ticket, "計測を完了できませんでした。原本は変更していません。解放未確認の場合は再試行せず終了してください。")
            } finally {
                if (!ComparisonLease.blocked) try { input?.close() } catch (_: Throwable) { ComparisonLease.retain(input!!) }
                lease?.close()
            }
        }
    }
    private fun update(ticket: Long, text: String) = runOnUiThread {
        if (generation.accepts(ticket) && !isDestroyed) status.text = text
    }
    private fun onMain(action: () -> Unit) {
        val done = CompletableFuture<Unit>()
        runOnUiThread { try { action(); done.complete(Unit) } catch (e: Throwable) { done.completeExceptionally(e) } }
        done.get(10, TimeUnit.SECONDS)
    }
    private fun run(input: PerformanceInput, mode: PreviewRasterMode, stop: ComparisonStop, ticket: Long): String {
        GpuPhotoRollRetiredOwners.releaseBeforeOpen()
        val owner = GpuPhotoRollSession(assets)
        val session = AtomicReference<Original24PreviewSession?>()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val drained = CountDownLatch(1)
        val result = CompletableFuture<List<GpuPreviewTimingRecord>>()
        val thermal = getSystemService(PowerManager::class.java)
        var thermalStart = -1
        var thermalEnd = -1
        stop.signal.setOnCancelListener { session.get()?.requestStop(); result.cancel(false) }
        try {
            stop.check()
            onMain {
                check(generation.accepts(ticket) && !isDestroyed)
                surface.setContent {
                    GpuPreviewSurface(owner.renderer, Modifier.fillMaxSize())
                    LaunchedEffect(owner) {
                        started.set(true)
                        val timing = checkNotNull(GpuPreviewTiming.start(1, {
                            thermalEnd = thermal.currentThermalStatus; result.complete(it); Unit
                        }))
                        owner.openTiming = timing
                        thermalStart = thermal.currentThermalStatus
                        try {
                            val image = withContext(Dispatchers.IO) {
                                stop.check()
                                timing.measure(GpuPreviewTimingStage.CHECK_ORIGINAL) {
                                    DngContentVerifier.verify(input.reference.fingerprint, { input.file.inputStream() }, stop::isStopped)
                                }
                                val geometry = timing.measure(GpuPreviewTimingStage.PREFLIGHT) {
                                    ParcelFileDescriptor.open(input.file, ParcelFileDescriptor.MODE_READ_ONLY).use {
                                        Original24Preflight.inspect(it.fd, stop.signal)
                                    }
                                }
                                val size = GpuPreviewInputSize.fit(geometry.outputWidth, geometry.outputHeight)
                                check(size == GpuPreviewInputSize.fit((geometry.outputWidth + 1) / 2, (geometry.outputHeight + 1) / 2))
                                timing.pixels(geometry.outputWidth, geometry.outputHeight)
                                val decoder = Original24PreviewSession(input.reference, File(input.file.parentFile, "session"), {
                                    val memory = android.app.ActivityManager.MemoryInfo().also {
                                        getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
                                    }
                                    check(!memory.lowMemory)
                                }, geometry, rasterMode = mode, timing = timing) { input.file.inputStream() }
                                session.set(decoder)
                                stop.check()
                                decoder.use { it.loadGpuAsShotInput(stop::isStopped) }.also {
                                    check(!decoder.hasUnreleasedResources)
                                    session.set(null)
                                    check(it.width == size.width && it.height == size.height)
                                }
                            }
                            stop.check()
                            timing.firstFrameStarted()
                            owner.submit(PhotoAdjustmentPolicy.defaults)
                            owner.renderer.openPhoto(image)
                        } catch (e: Throwable) { timing.failed(e) }
                        finally { drained.countDown() }
                    }
                }
            }
            val records = result.get(180, TimeUnit.SECONDS).associateBy { it.stage }
            check(records.getValue(GpuPreviewTimingStage.OPEN).outcome == GpuPreviewTimingOutcome.PRESENTED)
            check(records.getValue(GpuPreviewTimingStage.GUIDE).outcome == GpuPreviewTimingOutcome.NOT_REACHED)
            return buildString {
                append(mode.name).append(" thermal ").append(thermalStart).append('/').append(thermalEnd)
                for (stage in GpuPreviewTimingStage.entries.filter { it != GpuPreviewTimingStage.GUIDE }) {
                    val row = records.getValue(stage)
                    check(row.nanos != null)
                    append("\n").append(stage.name).append(": ").append(row.nanos / 1_000_000.0).append(" ms ").append(row.outcome.name)
                }
            }
        } finally {
            stop.signal.setOnCancelListener(null)
            session.get()?.requestStop()
            try {
                onMain { surface.disposeComposition(); owner.close() }
                check(!started.get() || drained.await(30, TimeUnit.SECONDS))
                session.get()?.let { it.close(); check(!it.hasUnreleasedResources); session.set(null) }
                check(owner.renderer.awaitClosed(10_000))
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            } catch (e: Throwable) {
                ComparisonLease.retain(listOf(owner, session, input))
                throw e
            }
        }
    }
    private fun cancel() { generation.next(); stop?.cancel(); stop = null }
    override fun onStop() { cancel(); if (::surface.isInitialized) surface.disposeComposition(); super.onStop() }
    override fun onDestroy() { cancel(); super.onDestroy() }
}