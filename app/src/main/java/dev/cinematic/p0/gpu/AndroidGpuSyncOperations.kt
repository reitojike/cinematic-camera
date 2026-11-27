package dev.cinematic.p0.gpu

import android.opengl.GLES32

internal object AndroidGpuSyncOperations : GpuSyncOperations {
    override fun createFence(): Long = GLES32.glFenceSync(GLES32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)

    override fun flush() = GLES32.glFlush()

    override fun clientWait(sync: Long, timeoutNanos: Long): GpuSyncOperations.WaitResult =
        when (GLES32.glClientWaitSync(sync, 0, timeoutNanos)) {
            GLES32.GL_ALREADY_SIGNALED, GLES32.GL_CONDITION_SATISFIED -> GpuSyncOperations.WaitResult.SIGNALED
            GLES32.GL_TIMEOUT_EXPIRED -> GpuSyncOperations.WaitResult.TIMEOUT
            else -> GpuSyncOperations.WaitResult.FAILED
        }

    override fun deleteFence(sync: Long) = GLES32.glDeleteSync(sync)
}
