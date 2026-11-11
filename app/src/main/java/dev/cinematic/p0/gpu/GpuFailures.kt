package dev.cinematic.p0.gpu

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import dev.cinematic.p0.CaptureDesign

/** 必須の OpenGL ES 能力を満たさない。message に写真や個人用パスを含めない。 */
internal class GpuUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** EGL コンテキストが失われた。CPU への暗黙の切り替えは行わない。 */
internal class GpuContextLostException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** GPU メモリ台帳の予算を超えた。割り当てる前に失敗させる。 */
internal class GpuMemoryBudgetException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal enum class GpuFailureKind(val exportReason: String) {
    UNAVAILABLE("gpu_unavailable"),
    CONTEXT_LOST("gpu_context_lost"),
    MEMORY_BUDGET("gpu_memory_budget"),
}

internal data class GpuFailureHandling(
    val kind: GpuFailureKind,
    val reason: String,
    val retryEngine: DevelopEngine,
) {
    val title: String get() = "GPU を使えません"
}

/** Gate 2 前の提案: GPU の再試行だけを案内し、CPU へ黙って切り替えない。
 *  保存側は kind.exportReason を既存の失敗記録へ渡す。例外message/写真名は記録しない。
 *  取消や未知の例外は既存の扱いを維持するため null を返す。
 */
internal fun gpuFailurePolicy(failure: Throwable): GpuFailureHandling? {
    val (kind, reason) = when (failure) {
        is GpuUnavailableException -> GpuFailureKind.UNAVAILABLE to "この端末は必須の GPU 能力を満たしていません"
        is GpuContextLostException -> GpuFailureKind.CONTEXT_LOST to "GPU の接続が失われました。再試行してください"
        is GpuMemoryBudgetException -> GpuFailureKind.MEMORY_BUDGET to "GPU メモリの上限に達しました。再試行してください"
        else -> return null
    }
    return GpuFailureHandling(kind, reason, retryEngine = DevelopEngine.GPU)
}

/** #287 が失敗表示に使用する共通UI。再試行中の連打はownerが再表示するまで受け付けない。 */
internal class GpuFailureView(context: Context, handling: GpuFailureHandling, retry: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        setBackgroundColor(CaptureDesign.Panel.toArgb())
        addView(TextView(context).apply { text = handling.title; setTextColor(CaptureDesign.Text.toArgb()) })
        addView(TextView(context).apply { text = handling.reason; setTextColor(CaptureDesign.Muted.toArgb()) })
        addView(Button(context).apply {
            text = "再試行"
            setOnClickListener { isEnabled = false; retry() }
        })
    }
}
