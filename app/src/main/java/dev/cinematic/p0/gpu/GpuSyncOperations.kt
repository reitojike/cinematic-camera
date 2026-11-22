package dev.cinematic.p0.gpu

/** Fence commands on the current context. The caller owns polling and cancellation. */
internal interface GpuSyncOperations {
    enum class WaitResult { SIGNALED, TIMEOUT, FAILED }

    fun createFence(): Long
    fun flush()
    fun clientWait(sync: Long, timeoutNanos: Long): WaitResult
    fun deleteFence(sync: Long)
}
