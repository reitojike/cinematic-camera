package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.GpuSyncOperations.WaitResult
import java.util.concurrent.CancellationException

internal class GpuFence(private val egl: GpuResourceContext) : AutoCloseable {
    private val operations = egl.syncOperations
    private var sync: Long
    init {
        egl.requireCurrent()
        sync = operations.createFence()
        check(sync != 0L) { "glFenceSync failed" }
        operations.flush()
    }
    fun await(stop: () -> Boolean = { false }) {
        egl.requireCurrent(); check(sync != 0L) { "GPU fence is closed" }
        while (true) {
            if (stop()) throw CancellationException("GPU wait cancelled")
            when (operations.clientWait(sync, 1_000_000L)) {
                WaitResult.SIGNALED -> return
                WaitResult.TIMEOUT -> Unit
                WaitResult.FAILED -> { egl.checkGl("glClientWaitSync"); error("glClientWaitSync failed") }
            }
        }
    }
    override fun close() {
        egl.requireOwnerThread()
        if (sync == 0L) return
        egl.releaseResource { operations.deleteFence(sync) }; sync = 0
    }
}
