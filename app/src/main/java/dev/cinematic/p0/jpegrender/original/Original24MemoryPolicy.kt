package dev.cinematic.p0.jpegrender.original

import java.io.IOException

internal enum class Original24MemoryStage { ORIGINAL_DECODE, BOUNDED_PREVIEW, ORIGINAL_SAVE }

/** Admission uses OS pressure, not an unmeasured fixed free-memory allowance.
 * This is not an allocation guarantee; typed allocation failures remain authoritative. */
internal object Original24MemoryPolicy {
    const val ORIGINAL_REQUIRED_BYTES = 768L * 1024 * 1024

    @Suppress("UNUSED_PARAMETER")
    fun permits(stage: Original24MemoryStage, lowMemory: Boolean?, availableBytes: Long?): Boolean =
        lowMemory == false
}

/** Actual ActivityManager observation at the rejected boundary; never /proc estimates or photo data. */
internal data class Original24MemoryObservation(
    val stage: Original24MemoryStage,
    val availableBytes: Long,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
    val outputPixels: Long? = null,
) {
    fun diagnosticMessage(): String = "ORIGINAL_MEMORY_REJECTED stage=${stage.name} " +
        "exception=Original24MemoryUnavailable availMemBytes=$availableBytes lowMemory=$lowMemory thresholdBytes=$thresholdBytes " +
        "requiredAvailableBytes=0 admissionPolicy=OS_LOW_MEMORY " +
        "outputPixels=${outputPixels ?: "unknown"} estimatedAdditionalBytes=unknown " +
        "rgb16PayloadBytes=${outputPixels?.takeIf { it in 1..24_000_000 }?.times(6) ?: "unknown"}"
}

internal class Original24MemoryUnavailable(cause: Throwable? = null,
    val observation: Original24MemoryObservation? = null) :
    IOException("Original rendering memory unavailable", cause)

/** Call only around a JNI original-raster operation, not arbitrary provider IO or wrapped causes. */
internal inline fun <T> originalNativeMemoryCall(block: () -> T): T = try { block() }
catch (failure: IOException) {
    if (failure.message == "DNG_MEMORY") throw Original24MemoryUnavailable(failure)
    throw failure
}

/** Best-effort diagnostic sink cannot replace the typed primary, including a logging OOM. */
internal fun reportOriginalMemoryRejection(failure: Original24MemoryUnavailable, report: (String) -> Unit) {
    try { failure.observation?.let { report(it.diagnosticMessage()) } }
    catch (_: Throwable) { /* Admission failure remains authoritative. */ }
}
