package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GpuSyncOperations
import org.lwjgl.opengles.GLES32.*

internal object MesaGpuSyncOperations : GpuSyncOperations {
    override fun createFence(): Long = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

    override fun flush() = glFlush()

    override fun clientWait(sync: Long, timeoutNanos: Long): GpuSyncOperations.WaitResult =
        when (glClientWaitSync(sync, 0, timeoutNanos)) {
            GL_ALREADY_SIGNALED, GL_CONDITION_SATISFIED -> GpuSyncOperations.WaitResult.SIGNALED
            GL_TIMEOUT_EXPIRED -> GpuSyncOperations.WaitResult.TIMEOUT
            else -> GpuSyncOperations.WaitResult.FAILED
        }

    override fun deleteFence(sync: Long) = glDeleteSync(sync)
}
