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
                "OSがメモリ不足と判断しているため現像を開始できません。原本は変更していません。時間をおいて再試行してください"
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

/** Only a typed memory failure gets the memory card; no save command is generated. */
internal fun gpuPhotoRollMemoryProblem(problem: Throwable?, retryEnabled: Boolean): dev.cinematic.p0.UiMessage? {
    if (problem !is dev.cinematic.p0.jpegrender.original.Original24MemoryUnavailable) return null
    return dev.cinematic.p0.UiMessage(
        slot = dev.cinematic.p0.MessageSlot.PROBLEM,
        tone = dev.cinematic.p0.CaptureChromeTone.FAILURE,
        fact = "develop.memory",
        title = "メモリ不足",
        body = requireNotNull(gpuPhotoRollProblemMessage(problem)),
        note = "再試行では、このアプリの不要な一時メモリだけを解放して状態を確認します。保存は自動でやり直しません。",
        primary = dev.cinematic.p0.MessageControl(dev.cinematic.p0.MessageAction.RETRY, "再試行", retryEnabled),
    )
}


// A save-admission rejection must not discard its existing preview or retained export owner.
internal fun gpuMemoryRetryNeedsReopen(exportPaused: Boolean, hasInput: Boolean, sessionFailed: Boolean): Boolean =
    !exportPaused && (!hasInput || sessionFailed)
