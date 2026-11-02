package dev.cinematic.p0

data class NormalExposureRestoration(val original: ManualExposure, val target: ManualExposure) {
    val migrated get() = original != target
    val input get() = ManualExposureInput(target.iso.toString(), java.math.BigDecimal.valueOf(target.exposureNs, 6).toPlainString())
}

/** Temporary application policy, independent of the immutable sensor capability report. */
object NormalExposurePolicy {
    const val MAX_EXPOSURE_NS = 1_000_000_000L
    const val DESCRIPTION = "通常MANUAL撮影targetの暫定上限1秒（端末能力・AUTO実測の上限ではありません）"

    fun validate(value: ManualExposure): ManualExposure {
        require(value.exposureNs <= MAX_EXPOSURE_NS) { "通常MANUAL撮影targetは暫定1秒以下です" }
        return value
    }

    /** Historical time may exceed today's sensor upper bound; validate the migrated target instead. */
    fun restoreSaved(iso: String, milliseconds: String, isoRange: IntRange?, nsRange: LongRange?,
        choices: ExposureChoices): NormalExposureRestoration {
        require(iso.isNotBlank() && milliseconds.isNotBlank()) { "Saved MANUAL pair is incomplete" }
        require(isoRange != null && nsRange != null) { "ISO / exposure range unavailable" }
        val original = ManualExposure(iso.trim().toInt(), Exposure.millisecondsToNs(milliseconds))
        require(original.iso in isoRange && original.exposureNs > 0) { "Saved ISO / exposure is invalid" }
        return restore(original, choices).also {
            require(it.target.exposureNs in nsRange) { "Restored exposure is outside current device range" }
        }
    }

    /** Only restoration migrates historical long requests; new invalid input is rejected. */
    fun restore(value: ManualExposure, choices: ExposureChoices): NormalExposureRestoration {
        if (value.exposureNs <= MAX_EXPOSURE_NS) return NormalExposureRestoration(value, value)
        require(choices.iso.isNotEmpty()) { "1秒上限へ移行できる標準ISO候補がありません" }
        val maximum = choices.shutter.filter { it.exposureNs in 1..MAX_EXPOSURE_NS }.maxByOrNull { it.exposureNs }
            ?: error("1秒上限へ移行できる標準SS候補がありません・AUTOまたは接続停止で復旧してください")
        return NormalExposureRestoration(value, value.copy(exposureNs = maximum.exposureNs))
    }
}
