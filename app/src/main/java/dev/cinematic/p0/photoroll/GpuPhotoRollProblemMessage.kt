package dev.cinematic.p0.photoroll

import dev.cinematic.p0.gpu.gpuFailurePolicy
import dev.cinematic.p0.gpu.preview.GpuInputReadinessTimeout
import dev.cinematic.p0.gpu.preview.GpuInputWaitReason

/** Presentation only: never grants admission, retries, clears a frame or changes a save owner. */
internal fun gpuPhotoRollProblemMessage(problem: Throwable?, notice: String? = null): String? = when (problem) {
        null -> notice
        is GpuInputReadinessTimeout -> when (problem.reason) {
            GpuInputWaitReason.CAMERA_IN_USE -> "カメラが使用中のため読み込めません。再試行してください"
            GpuInputWaitReason.CAMERA_UNKNOWN -> "カメラの状態を確認できません。再試行してください"
            GpuInputWaitReason.OTHER_PREPARATION -> "入力の準備を確認できません。再試行してください"
        }
        is dev.cinematic.p0.jpegrender.original.Original24MemoryUnavailable ->
            if (problem.observation != null)
                "メモリ不足のため現像を開始・継続できません（空きメモリが受付条件未満、またはOSの低メモリ状態）。原本は変更していません。時間をおいて再試行してください"
            else "現像用のメモリを確保できませんでした。原本は変更していません。時間をおいて再試行してください"
        is PhotoRollSourceFailure -> problem.userMessage
        is dev.cinematic.p0.rawcapture.DngContentChanged -> "原本の内容が変わりました。Filesから選び直してください"
        is SecurityException -> "原本の読み取り権限がありません。Filesから選び直してください"
        else -> gpuFailurePolicy(problem)?.reason ?: "現像を完了できません。原本は変更していません。再試行してください"
    }

/** Normalize only allocation failure from the input worker; unknown Errors retain their identity. */
internal inline fun <T> gpuPhotoRollInputResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (failure: OutOfMemoryError) {
    Result.failure(dev.cinematic.p0.jpegrender.original.Original24MemoryUnavailable(failure))
} catch (failure: Throwable) {
    Result.failure(failure)
}
