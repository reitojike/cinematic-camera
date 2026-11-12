package dev.cinematic.p0.rawcapture

import java.io.IOException

/** Fixed, non-identifying reasons only. Never persist exception messages, URIs or file names. */
internal enum class GpuSaveFailureReason(val code: String, val description: String) {
    UNAVAILABLE("gpu_unavailable", "必須の GPU 能力を確認できません"),
    CONTEXT_LOST("gpu_context_lost", "GPU の接続が失われました"),
    MEMORY_BUDGET("gpu_memory_budget", "GPU メモリの上限に達しました"),
    SAVE_FAILED("gpu_save_failed", "GPU 選択中の保存処理に失敗しました"),
    RENDER_FAILED("gpu_render_failed", "GPU の画像処理に失敗しました"),
}
internal data class GpuSaveFailure(val reason: GpuSaveFailureReason, val count: Long) {
    init { require(count > 0) }
    val description get() = "GPU 保存失敗 ${count}回: ${reason.description}（${reason.code}）。CPU には切り替えていません"
}

/** Emitted only inside the GPU image producer, before any JPEG output/publication starts. */
internal class GpuSaveRenderException(val reason: GpuSaveFailureReason, val cleanupConfirmed: Boolean,
    cause: Throwable) : IOException(reason.code, cause)
