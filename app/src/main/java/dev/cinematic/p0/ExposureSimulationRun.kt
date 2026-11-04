package dev.cinematic.p0

enum class SimulationPhase { ENTERING, READY, CAPTURING, RESTORING, UNKNOWN, SUSPENDED }
enum class SimulationIntent { PREVIEW, STILL, OTHER }

data class SimulationFixedSettings(
    val plan: SimulationPlan,
    val normalSnapshot: ControlSnapshot,
    val boostPercent: Int?,
)

data class SimulationFrame(
    val epoch: Int,
    val frame: Long,
    val receivedAtMs: Long,
    val intent: SimulationIntent,
    val requested: SensorValues,
    val actual: SensorValues,
    val frameDurationNs: Long? = null,
    val sensorTimestampNs: Long? = null,
    val requestedBoost: Int? = null,
    val actualBoost: Int? = null,
)

/** Session-local observations; neither API acceptance nor READY proves image quality. */
data class ExposureSimulationRun(
    val settings: SimulationFixedSettings,
    val epoch: Int,
    val phase: SimulationPhase = SimulationPhase.ENTERING,
    val previewReceipt: ControlReceipt = ControlReceipt(),
    val previewFrame: SimulationFrame? = null,
    val stillFrame: SimulationFrame? = null,
    val latestFrame: Long = -1,
    val returnAfterFrame: Long = -1,
    val exitPending: Boolean = false,
    val detail: String = "プレビュー要求の受付・実測待ち",
    /** File publication is independent of preview recovery; null means no terminal result yet. */
    val filesSaved: Boolean? = null,
) {
    val canCapture get() = phase == SimulationPhase.READY && !exitPending

    fun rebound(epoch: Int) = copy(
        epoch = epoch, phase = SimulationPhase.ENTERING,
        previewReceipt = ControlReceipt(epoch = epoch), previewFrame = null, stillFrame = null,
        latestFrame = -1, returnAfterFrame = -1, filesSaved = null,
        detail = "再接続: プレビュー要求の受付・実測待ち",
    )

    fun receipt(value: ControlReceipt): ExposureSimulationRun {
        if (value.epoch != epoch || value.sequence < previewReceipt.sequence ||
            phase == SimulationPhase.SUSPENDED || phase == SimulationPhase.UNKNOWN
        ) return this
        // A duplicated/late Pending must not undo an already completed receipt.
        if (value.sequence == previewReceipt.sequence &&
            previewReceipt.phase !in setOf(ControlPhase.UNBOUND, ControlPhase.PENDING)
        ) return this
        val updated = copy(previewReceipt = value)
        if (value.phase == ControlPhase.UNKNOWN || value.phase == ControlPhase.REJECTED) {
            return updated.unknown(value.detail)
        }
        return updated.copy(
            phase = if (phase == SimulationPhase.READY) SimulationPhase.ENTERING else phase,
            detail = if (phase == SimulationPhase.CAPTURING) detail else "プレビュー要求の受付・実測待ち",
        ).readyIfObserved()
    }

    fun observe(value: SimulationFrame): ExposureSimulationRun {
        if (phase == SimulationPhase.SUSPENDED || value.epoch != epoch || value.frame <= latestFrame) return this
        return copy(
            phase = if (phase == SimulationPhase.READY && value.intent == SimulationIntent.PREVIEW)
                SimulationPhase.ENTERING else phase,
            latestFrame = value.frame,
            previewFrame = if (value.intent == SimulationIntent.PREVIEW) value else previewFrame,
            stillFrame = if (value.intent == SimulationIntent.STILL) value else stillFrame,
        ).readyIfObserved()
    }

    fun captureStarted(): ExposureSimulationRun = if (!canCapture) this else copy(
        phase = SimulationPhase.CAPTURING, stillFrame = null, filesSaved = null,
        returnAfterFrame = -1, detail = "固定した静止画設定で撮影中・実測は別確認",
    )

    fun filesFinished(success: Boolean): ExposureSimulationRun = when {
        phase == SimulationPhase.CAPTURING -> copy(
            phase = SimulationPhase.RESTORING, filesSaved = success, returnAfterFrame = latestFrame,
            detail = if (success) "保存済・プレビュー復帰待ち" else "保存失敗・プレビュー復帰待ち",
        )
        // A terminal file callback must not make unknown/suspended controls trusted again.
        phase in setOf(SimulationPhase.UNKNOWN, SimulationPhase.SUSPENDED) && filesSaved == null ->
            copy(filesSaved = success)
        else -> this
    }

    fun requestExit() = copy(exitPending = true, detail = "終了要求・通常設定への復旧待ち")
    fun suspend() = copy(phase = SimulationPhase.SUSPENDED, exitPending = true, detail = "背景化・通常設定への復旧待ち")
    fun unknown(reason: String) = copy(phase = SimulationPhase.UNKNOWN, detail = reason)

    private fun readyIfObserved(): ExposureSimulationRun {
        if (phase !in setOf(SimulationPhase.ENTERING, SimulationPhase.RESTORING)) return this
        val receipt = previewReceipt
        val frame = previewFrame ?: return this
        val acceptedAt = receipt.acceptedAtMs ?: return this
        val target = settings.plan.preview
        if (receipt.epoch != epoch || frame.epoch != epoch || receipt.phase != ControlPhase.ACCEPTED ||
            receipt.accepted?.exposure != target || frame.receivedAtMs <= acceptedAt ||
            frame.frame <= returnAfterFrame || frame.requested.aeMode != 0 ||
            frame.actual.aeMode != 0 ||
            frame.requested.iso != target.iso || frame.requested.exposureNs != target.exposureNs ||
            frame.actual.iso == null || frame.actual.iso <= 0 ||
            frame.actual.exposureNs == null || frame.actual.exposureNs <= 0
        ) return this
        settings.boostPercent?.let { boost ->
            if (frame.requestedBoost != boost || frame.actualBoost != boost) return this
        }
        return copy(phase = SimulationPhase.READY,
            detail = "プレビュー要求と実測到着を確認・明るさ/画質/静止画設定の一致保証ではない")
    }
}
