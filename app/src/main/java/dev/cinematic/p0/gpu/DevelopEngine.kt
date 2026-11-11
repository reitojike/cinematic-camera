package dev.cinematic.p0.gpu

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.CapturePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class DevelopEngine { CPU, GPU }

/** 通常 debug/release は保存済みの診断値からも GPU を有効にしない。Gate 2 で別途変更する。 */
@Suppress("UNUSED_PARAMETER")
internal fun developEngineSettingVisible(debug: Boolean, buildType: String): Boolean =
    buildType == "gpuDiagnostic"

internal sealed interface GpuAvailability {
    data object Checking : GpuAvailability
    data object Available : GpuAvailability
    data class Unavailable(val reason: String) : GpuAvailability
}

/** プレビューと保存の唯一の設定入口。通常版の既定は CPU のまま。 */
internal class DevelopEngineSetting(
    private val read: () -> String,
    private val write: (String) -> Unit,
    val visible: Boolean,
) {
    constructor(context: Context) : this(CapturePreferences(context))

    private constructor(preferences: CapturePreferences) : this(
        read = { preferences.text(KEY, DevelopEngine.CPU.name) },
        write = { preferences.put(KEY, it) },
        visible = developEngineSettingVisible(BuildConfig.DEBUG, BuildConfig.BUILD_TYPE),
    )

    fun current(): DevelopEngine = if (visible && read() == DevelopEngine.GPU.name)
        DevelopEngine.GPU else DevelopEngine.CPU

    /** 保存ownerが開始時に1回取得し、完了までこの値を保持する。 */
    fun beginExport(): DevelopEngine = current()

    /** 能力確認前・能力不足での GPU 選択は UI 以外からも拒否する。 */
    fun select(engine: DevelopEngine, availability: GpuAvailability): Boolean {
        if (!visible || (engine == DevelopEngine.GPU && availability != GpuAvailability.Available)) return false
        write(engine.name)
        return true
    }

    private companion object { const val KEY = "develop/engine" }
}

/** UI thread から呼ばない。GL の照会・解放を #277 の専用threadに閉じ込める。 */
private fun inspectDevelopGpuAvailability(): GpuAvailability = GlThread().use { thread ->
    thread.call {
        EglContexts(thread).use { egl ->
            val result = GlCapabilities.inspect(egl)
            if (result.supported) GpuAvailability.Available
            else GpuAvailability.Unavailable("必須の GPU 能力が不足しています: ${result.missing.joinToString()}")
        }
    }
}

/** 完了した能力結果だけをプロセス内で共有し、明示再確認時に更新する。Context/GL資源は保持しない。 */
internal class DevelopGpuAvailabilityCache(private val inspect: () -> GpuAvailability) {
    private val mutex = Mutex()
    private var cached: GpuAvailability? = null

    suspend fun get(forceRefresh: Boolean = false): GpuAvailability = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!forceRefresh) cached?.let { return@withLock it }
        // 同時に設定画面を開いても検査は1本。取消済みの遅い検査は結果を保存しない。
        val result = withContext(Dispatchers.IO) {
            try {
                inspect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // checked例外も扱う。message/causeは入力識別子を含み得るため転記しない。
                val policy = gpuFailurePolicy(failure)
                GpuAvailability.Unavailable(policy?.reason ?: "GPU の能力を確認できません。再試行してください")
            }
        }
        currentCoroutineContext().ensureActive()
        check(result != GpuAvailability.Checking) { "GPU inspection must return a completed result" }
        cached = result
        result
    }
}

private val processDevelopGpuAvailability = DevelopGpuAvailabilityCache(::inspectDevelopGpuAvailability)

/** 専用診断Hubでだけ使う。検査中/不足時は GPU ボタンを無効化。 */
internal class DevelopEngineSettingView(
    context: Context,
    private val setting: DevelopEngineSetting = DevelopEngineSetting(context),
    private val capabilities: DevelopGpuAvailabilityCache = processDevelopGpuAvailability,
) : LinearLayout(context) {
    /** 合成試験は専用cacheを使い、プロセスの実能力結果を汚さない。 */
    constructor(context: Context, setting: DevelopEngineSetting, inspect: () -> GpuAvailability) :
        this(context, setting, DevelopGpuAvailabilityCache(inspect))
    private var availability: GpuAvailability = GpuAvailability.Checking
    private var scope: CoroutineScope? = null
    private var editingEnabled = true
    private val cpu = Button(context).apply { text = "CPU"; contentDescription = "現像エンジン CPU" }
    private val gpu = Button(context).apply { text = "GPU"; contentDescription = "現像エンジン GPU" }
    private val status = TextView(context)
    private val retry = Button(context).apply { text = "GPU 能力を再確認" }

    init {
        orientation = VERTICAL
        setBackgroundColor(CaptureDesign.Panel.toArgb())
        visibility = if (setting.visible) View.VISIBLE else View.GONE
        addView(TextView(context).apply {
            text = "現像エンジン（開発・診断専用）"
            setTextColor(CaptureDesign.Text.toArgb())
        })
        listOf(cpu, gpu, retry).forEach {
            it.isAllCaps = false
            it.setTextColor(CaptureDesign.Text.toArgb())
            it.backgroundTintList = ColorStateList.valueOf(CaptureDesign.Selected.toArgb())
        }
        addView(LinearLayout(context).apply {
            addView(cpu, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(gpu, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        })
        addView(status.apply { setTextColor(CaptureDesign.Muted.toArgb()) })
        addView(retry)
        addView(TextView(context).apply {
            text = "プレビュー・保存で共通。保存中の変更は次回から。通常版の既定は CPU です。GPU 失敗時も CPU に自動で切り替えません。"
            setTextColor(CaptureDesign.Muted.toArgb())
        })
        cpu.setOnClickListener { select(DevelopEngine.CPU) }
        gpu.setOnClickListener { select(DevelopEngine.GPU) }
        retry.setOnClickListener { checkCapabilities(forceRefresh = true) }
        refresh()
    }

    fun setEditingEnabled(enabled: Boolean) { editingEnabled = enabled; refresh() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (setting.visible) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            checkCapabilities()
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    private fun checkCapabilities(forceRefresh: Boolean = false) {
        availability = GpuAvailability.Checking
        refresh()
        scope?.launch {
            availability = capabilities.get(forceRefresh)
            refresh()
        }
    }

    private fun select(engine: DevelopEngine) {
        if (editingEnabled && setting.select(engine, availability)) refresh()
    }

    private fun refresh() {
        val current = setting.current()
        cpu.isSelected = current == DevelopEngine.CPU
        gpu.isSelected = current == DevelopEngine.GPU
        cpu.isEnabled = setting.visible && editingEnabled
        gpu.isEnabled = setting.visible && editingEnabled && availability == GpuAvailability.Available
        retry.visibility = if (availability is GpuAvailability.Unavailable) View.VISIBLE else View.GONE
        retry.isEnabled = editingEnabled && availability is GpuAvailability.Unavailable
        status.text = "選択: ${current.name}\n" + when (val value = availability) {
            GpuAvailability.Checking -> "GPU の能力を確認しています"
            GpuAvailability.Available -> "GPU の必須能力を確認しました"
            is GpuAvailability.Unavailable -> value.reason
        }
    }
}

@Composable internal fun DevelopEngineSettingControl(enabled: Boolean, modifier: Modifier = Modifier) {
    if (developEngineSettingVisible(BuildConfig.DEBUG, BuildConfig.BUILD_TYPE)) {
        AndroidView(factory = { DevelopEngineSettingView(it) }, modifier = modifier,
            update = { it.setEditingEnabled(enabled) })
    }
}
