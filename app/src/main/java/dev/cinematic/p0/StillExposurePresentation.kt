package dev.cinematic.p0

/** Labels deliberately distinguish logical capture settings from runtime API receipts and observations. */
data class StillExposurePresentation(
    val stage: String,
    val phase: String,
    val captureTarget: String,
    val previewTarget: String,
    val previewApi: String,
    val captureApi: String,
    val previewActual: String,
    val stillActual: String,
    val files: String,
    val recoveryRequired: Boolean,
)

private fun exposurePairText(value: ManualExposure?): String = value?.let {
    "ISO ${it.iso} · ${ExposureStops.label(it.exposureNs)}"
} ?: "—"

private fun exposureApiText(receipt: ControlReceipt): String = when (receipt.phase) {
    ControlPhase.UNBOUND -> "未受付"
    ControlPhase.PENDING -> "送信中"
    ControlPhase.ACCEPTED -> "受付済 ${exposurePairText(receipt.accepted?.exposure)}"
    ControlPhase.REJECTED -> "拒否（最終受付 ${exposurePairText(receipt.accepted?.exposure)}）"
    ControlPhase.UNKNOWN -> "不明（最終受付 ${exposurePairText(receipt.accepted?.exposure)}）"
}

fun StillExposureState.presentation(): StillExposurePresentation = StillExposurePresentation(
    stage = when (target.plan.stage) {
        SimulationStage.COMPENSATED -> "第1段階 · 短露光プレビュー"
        SimulationStage.LONGER_PREVIEW -> "第2段階 · 明るさ優先プレビュー"
    },
    phase = when (phase) {
        StillExposurePhase.PREVIEW_APPLYING -> "プレビュー反映待ち"
        StillExposurePhase.READY -> "撮影可"
        StillExposurePhase.CAPTURE_APPLYING -> "撮影設定の受付待ち"
        StillExposurePhase.CAPTURING -> "撮影中・保存結果待ち"
        StillExposurePhase.RESTORING -> "プレビュー復帰待ち"
        StillExposurePhase.UNKNOWN -> "状態不明・復旧が必要"
        StillExposurePhase.SUSPENDED -> "背景からの復帰待ち"
    },
    captureTarget = exposurePairText((captureTarget ?: target).plan.capture),
    previewTarget = exposurePairText(target.plan.preview),
    previewApi = exposureApiText(previewReceipt),
    captureApi = (if (!ownsCapture && captureReceipt.sequence > 0) "前回 " else "") + exposureApiText(captureReceipt),
    previewActual = previewFrame?.actual?.let { "ISO ${it.iso ?: "—"} · ${ExposureStops.label(it.exposureNs)}" } ?: "未観測",
    stillActual = stillFrame?.actual?.let { "ISO ${it.iso ?: "—"} · ${ExposureStops.label(it.exposureNs)}" } ?: "未観測",
    files = if (!filesTerminal) "未確定" else when (filesSaved) { true -> "保存成功"; false -> "保存失敗"; null -> "結果不明" },
    recoveryRequired = phase == StillExposurePhase.UNKNOWN && ownsCapture,
)
