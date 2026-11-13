package dev.cinematic.p0.memory

import dev.cinematic.p0.BuildConfig

/** No runtime preference/intent/property can enable this in a distribution APK. */
internal object MemoryAdmissionDiagnostic {
    val enabled: Boolean get() = isolatedMemoryDiagnostic(
        BuildConfig.MEMORY_ADMISSION_RECORD_ONLY, BuildConfig.BUILD_TYPE, BuildConfig.APPLICATION_ID)

    fun record(stage: String, availableBytes: Long?, lowMemory: Boolean?, thresholdBytes: Long?,
        requiredBytes: Long, wouldPermit: Boolean, outputPixels: Long?) {
        if (!enabled) return
        try {
            android.util.Log.i("CinematicMemory", memoryAdmissionDiagnosticMessage(stage, availableBytes,
                lowMemory, thresholdBytes, requiredBytes, wouldPermit, outputPixels,
                android.os.SystemClock.elapsedRealtime(), android.os.Process.myPid()))
        } catch (_: Throwable) { /* Observation failure must not replace allocation/error handling. */ }
    }
}

internal fun isolatedMemoryDiagnostic(flag: Boolean, buildType: String, applicationId: String): Boolean =
    flag && buildType == "memoryDiagnostic" && applicationId == "dev.cinematic.p0.memorydiagnostic"

/** Only fixed stage names and scalar observations; never a URI, filename or exception message. */
internal fun memoryAdmissionDiagnosticMessage(stage: String, availableBytes: Long?, lowMemory: Boolean?,
    thresholdBytes: Long?, requiredBytes: Long, wouldPermit: Boolean, outputPixels: Long?,
    elapsedMs: Long, pid: Int): String {
    require(stage in setOf("ROLL_PREVIEW", "ROLL_SAVE", "ORIGINAL_DECODE", "BOUNDED_PREVIEW", "ORIGINAL_SAVE"))
    val reason = when {
        lowMemory == null || availableBytes == null -> "UNKNOWN"
        lowMemory -> "OS_LOW_MEMORY"
        availableBytes < requiredBytes -> "BELOW_ADMISSION_REQUIREMENT"
        else -> "NONE"
    }
    return "MEMORY_ADMISSION mode=RECORD_ONLY stage=$stage elapsedMs=$elapsedMs pid=$pid " +
        "availMemBytes=${availableBytes ?: "unknown"} lowMemory=${lowMemory ?: "unknown"} " +
        "thresholdBytes=${thresholdBytes ?: "unknown"} requiredAvailableBytes=$requiredBytes " +
        "wouldPermit=$wouldPermit reason=$reason outputPixels=${outputPixels ?: "unknown"} " +
        "estimatedAdditionalBytes=unknown rgb16PayloadBytes=${outputPixels?.takeIf { it in 1..24_000_000 }?.times(6) ?: "unknown"}"
}
