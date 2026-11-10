package dev.cinematic.p0.gpu

import android.opengl.GLES32
import java.util.concurrent.CancellationException

internal class GpuFence(private val egl: EglContexts) : AutoCloseable {
    private var sync: Long
    init {
        egl.requireCurrent()
        sync = GLES32.glFenceSync(GLES32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        check(sync != 0L) { "glFenceSync failed" }
        GLES32.glFlush()
    }
    fun await(stop: () -> Boolean = { false }) {
        egl.requireCurrent(); check(sync != 0L) { "GPU fence is closed" }
        while (true) {
            if (stop()) throw CancellationException("GPU wait cancelled")
            when (GLES32.glClientWaitSync(sync, 0, 1_000_000L)) {
                GLES32.GL_ALREADY_SIGNALED, GLES32.GL_CONDITION_SATISFIED -> return
                GLES32.GL_TIMEOUT_EXPIRED -> Unit
                else -> { egl.checkGl("glClientWaitSync"); error("glClientWaitSync failed") }
            }
        }
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (sync == 0L) return
        egl.releaseResource { GLES32.glDeleteSync(sync) }; sync = 0
    }
}
