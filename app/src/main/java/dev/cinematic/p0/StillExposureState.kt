package dev.cinematic.p0

data class StillExposureTarget(
    val epoch: Int,
    val revision: Long,
    val plan: SimulationPlan,
    /** Logical still settings; never substitute the compensated preview pair here. */
    val controls: ControlSnapshot,
    val boostPercent: Int?,
)

enum class StillExposurePhase {
    PREVIEW_APPLYING, READY, CAPTURE_APPLYING, CAPTURING, RESTORING, UNKNOWN, SUSPENDED,
}

/** Pure ownership and observation model. File success is independent of preview recovery. */
data class StillExposureState(
    val target: StillExposureTarget,
    val phase: StillExposurePhase = StillExposurePhase.PREVIEW_APPLYING,
    val previewReceipt: ControlReceipt = ControlReceipt(epoch = target.epoch),
    val captureReceipt: ControlReceipt = ControlReceipt(epoch = target.epoch),
    val previewFrame: SimulationFrame? = null,
    val stillFrame: SimulationFrame? = null,
    val captureTarget: StillExposureTarget? = null,
    val singleIssued: Boolean = false,
    val filesSaved: Boolean? = null,
    val filesTerminal: Boolean = false,
    val latestFrame: Long = -1,
    val returnAfterFrame: Long = -1,
    val detail: String = "プレビュー要求の受付・実測待ち",
) {
    val epoch get() = target.epoch
    val ownsCapture get() = captureTarget != null
    val canCapture get() = phase == StillExposurePhase.READY && !ownsCapture && hasFreshPreview()
    val canIssueCapture get(): Boolean {
        val fixed = captureTarget ?: return false
        return phase == StillExposurePhase.CAPTURE_APPLYING && !singleIssued &&
            fixed.epoch == epoch && fixed.controls.exposure == fixed.plan.capture &&
            captureReceipt.epoch == epoch && captureReceipt.phase == ControlPhase.ACCEPTED &&
            captureReceipt.acceptedAtMs != null && captureReceipt.accepted?.exposure == fixed.plan.capture
    }

    fun retarget(value: StillExposureTarget): StillExposureState {
        if (ownsCapture || phase == StillExposurePhase.SUSPENDED || value.epoch < epoch ||
            (value.epoch == epoch && value.revision <= target.revision)
        ) return this
        val newEpoch = value.epoch != epoch
        return copy(
            target = value, phase = StillExposurePhase.PREVIEW_APPLYING,
            previewReceipt = ControlReceipt(epoch = value.epoch),
            captureReceipt = ControlReceipt(epoch = value.epoch),
            previewFrame = if (newEpoch) null else previewFrame,
            stillFrame = if (newEpoch) null else stillFrame,
            latestFrame = if (newEpoch) -1 else latestFrame,
            returnAfterFrame = -1, singleIssued = false, filesSaved = null, filesTerminal = false,
            detail = "最新の撮影targetに対応するプレビュー受付・実測待ち",
        )
    }

    fun previewReceipt(revision: Long, value: ControlReceipt): StillExposureState {
        if (revision != target.revision || value.epoch != epoch ||
            phase in setOf(StillExposurePhase.UNKNOWN, StillExposurePhase.SUSPENDED) ||
            (ownsCapture && phase != StillExposurePhase.RESTORING) || !newReceipt(previewReceipt, value)
        ) return this
        val updated = copy(previewReceipt = value)
        if (value.phase in setOf(ControlPhase.REJECTED, ControlPhase.UNKNOWN)) return updated.unknown(value.detail)
        return updated.copy(
            phase = if (phase == StillExposurePhase.RESTORING) phase else StillExposurePhase.PREVIEW_APPLYING,
            detail = "プレビュー要求の受付・実測待ち",
        ).readyIfObserved()
    }

    fun beginCapture(): StillExposureState = if (!canCapture) this else copy(
        phase = StillExposurePhase.CAPTURE_APPLYING, captureTarget = target,
        captureReceipt = ControlReceipt(epoch = epoch,
            sequence = maxOf(previewReceipt.sequence, captureReceipt.sequence)),
        stillFrame = null, singleIssued = false, filesSaved = null, filesTerminal = false,
        returnAfterFrame = -1, detail = "撮影targetを固定・撮影設定の受付待ち",
    )

    fun captureReceipt(value: ControlReceipt): StillExposureState {
        if (phase != StillExposurePhase.CAPTURE_APPLYING || captureTarget == null ||
            value.epoch != epoch || !newReceipt(captureReceipt, value)
        ) return this
        val updated = copy(captureReceipt = value)
        return if (value.phase in setOf(ControlPhase.REJECTED, ControlPhase.UNKNOWN)) updated.unknown(value.detail)
        else updated.copy(detail = "撮影設定の受付と単発撮影の開始待ち")
    }

    fun singleStarted(): StillExposureState = if (!canIssueCapture) this else copy(
        phase = StillExposurePhase.CAPTURING, singleIssued = true,
        detail = "単発撮影中・全形式の結果確定待ち",
    )

    /** Safe only before takePicture is issued; it cannot cancel an existing single capture. */
    fun preparationAborted(reason: String): StillExposureState {
        if (!ownsCapture || singleIssued || filesTerminal) return this
        return copy(
            phase = StillExposurePhase.RESTORING, filesTerminal = true, filesSaved = false,
            returnAfterFrame = latestFrame,
            previewReceipt = ControlReceipt(epoch = epoch,
                sequence = maxOf(previewReceipt.sequence, captureReceipt.sequence)),
            detail = "撮影未開始: $reason・新しいプレビュー受付と実測による復帰待ち",
        )
    }

    /** The caller aggregates all requested formats; one format callback is not terminal. */
    fun filesFinished(success: Boolean): StillExposureState {
        if (!ownsCapture || !singleIssued || filesTerminal) return this
        return copy(
            phase = if (phase == StillExposurePhase.SUSPENDED) phase else StillExposurePhase.RESTORING,
            filesTerminal = true, filesSaved = success, returnAfterFrame = latestFrame,
            previewReceipt = ControlReceipt(epoch = epoch,
                sequence = maxOf(previewReceipt.sequence, captureReceipt.sequence)),
            detail = if (phase == StillExposurePhase.SUSPENDED) "背景中・保存結果確定・通常プレビューの復旧待ち"
                else if (success) "保存済・新しいプレビュー受付と実測による復帰待ち"
                else "保存失敗・新しいプレビュー受付と実測による復帰待ち",
        )
    }

    fun observe(value: SimulationFrame): StillExposureState {
        if (phase == StillExposurePhase.SUSPENDED || value.epoch != epoch || value.frame <= latestFrame) return this
        return copy(
            phase = if (phase == StillExposurePhase.READY && value.intent == SimulationIntent.PREVIEW)
                StillExposurePhase.PREVIEW_APPLYING else phase,
            latestFrame = value.frame,
            previewFrame = if (value.intent == SimulationIntent.PREVIEW) value else previewFrame,
            stillFrame = if (value.intent == SimulationIntent.STILL) value else stillFrame,
        ).readyIfObserved()
    }

    fun unknown(reason: String) = copy(phase = StillExposurePhase.UNKNOWN, detail = reason)
    fun suspend() = copy(phase = StillExposurePhase.SUSPENDED, detail = "背景化・現世代の通常プレビューへの復旧待ち")

    private fun readyIfObserved(): StillExposureState {
        if (phase !in setOf(StillExposurePhase.PREVIEW_APPLYING, StillExposurePhase.RESTORING) || !hasFreshPreview()) return this
        return copy(phase = StillExposurePhase.READY, captureTarget = null, singleIssued = false,
            detail = "プレビュー受付と実測到着を確認・撮影target/明るさ/画質の一致保証ではない")
    }

    private fun hasFreshPreview(): Boolean {
        val frame = previewFrame ?: return false
        val at = previewReceipt.acceptedAtMs ?: return false
        val pair = target.plan.preview
        if (target.controls.exposure != target.plan.capture || previewReceipt.epoch != epoch ||
            previewReceipt.phase != ControlPhase.ACCEPTED || previewReceipt.accepted?.exposure != pair ||
            frame.epoch != epoch || frame.intent != SimulationIntent.PREVIEW ||
            frame.frame <= returnAfterFrame || frame.receivedAtMs <= at ||
            frame.requested.aeMode != 0 || frame.requested.iso != pair.iso || frame.requested.exposureNs != pair.exposureNs ||
            frame.actual.aeMode != 0 || frame.actual.iso == null || frame.actual.iso <= 0 ||
            frame.actual.exposureNs == null || frame.actual.exposureNs <= 0
        ) return false
        return target.boostPercent?.let { frame.requestedBoost == it && frame.actualBoost == it } ?: true
    }

    private fun newReceipt(previous: ControlReceipt, value: ControlReceipt): Boolean =
        value.sequence > previous.sequence ||
            (value.sequence == previous.sequence && previous.phase == ControlPhase.PENDING && value.phase != ControlPhase.UNBOUND)
}
