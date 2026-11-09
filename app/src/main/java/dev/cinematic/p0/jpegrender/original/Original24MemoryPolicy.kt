package dev.cinematic.p0.jpegrender.original

import java.io.IOException

internal enum class Original24MemoryStage { ORIGINAL_DECODE, BOUNDED_PREVIEW, ORIGINAL_SAVE }

/** Admission is not an allocation guarantee. The existing original allowance is not a measured
 * minimum; only the bounded post-decode path stops requiring that full-original allowance. */
internal object Original24MemoryPolicy {
    const val ORIGINAL_REQUIRED_BYTES = 768L * 1024 * 1024

    fun permits(stage: Original24MemoryStage, lowMemory: Boolean?, availableBytes: Long?): Boolean =
        lowMemory == false && availableBytes != null && availableBytes > 0L &&
            (stage == Original24MemoryStage.BOUNDED_PREVIEW || availableBytes >= ORIGINAL_REQUIRED_BYTES)
}

internal class Original24MemoryUnavailable(cause: Throwable? = null) :
    IOException("Original rendering memory unavailable", cause)
