package dev.cinematic.p0.gpu

import android.opengl.GLES32

internal object AndroidGpuPassOperations : GpuPassOperations {
    override fun createVertexArray() = IntArray(1).also { GLES32.glGenVertexArrays(1, it, 0) }[0]
    override fun deleteVertexArray(array: Int) = GLES32.glDeleteVertexArrays(1, intArrayOf(array), 0)
    override fun bindVertexArray(array: Int) = GLES32.glBindVertexArray(array)
    override fun maxTextureUnits() = integer(GLES32.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)
    override fun useProgram(program: Int) = GLES32.glUseProgram(program)
    override fun activateTextureUnit(unit: Int) = GLES32.glActiveTexture(GLES32.GL_TEXTURE0 + unit)
    override fun bindTexture2d(texture: Int) = GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, texture)
    override fun uniform1i(location: Int, value: Int) = GLES32.glUniform1i(location, value)
    override fun uniform2i(location: Int, x: Int, y: Int) = GLES32.glUniform2i(location, x, y)
    override fun uniform1f(location: Int, value: Float) = GLES32.glUniform1f(location, value)
    override fun uniform2f(location: Int, x: Float, y: Float) = GLES32.glUniform2f(location, x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GLES32.glUniform3f(location, x, y, z)

    override fun maxColorAttachments() = integer(GLES32.GL_MAX_COLOR_ATTACHMENTS)
    override fun maxDrawBuffers() = integer(GLES32.GL_MAX_DRAW_BUFFERS)
    override fun createFramebuffer() = IntArray(1).also { GLES32.glGenFramebuffers(1, it, 0) }[0]
    override fun deleteFramebuffer(framebuffer: Int) = GLES32.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
    override fun bindFramebuffer(framebuffer: Int) = GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, framebuffer)
    override fun attachColorTexture(index: Int, texture: Int) =
        GLES32.glFramebufferTexture2D(
            GLES32.GL_FRAMEBUFFER, GLES32.GL_COLOR_ATTACHMENT0 + index, GLES32.GL_TEXTURE_2D, texture, 0,
        )

    override fun drawColorAttachments(count: Int) =
        GLES32.glDrawBuffers(count, IntArray(count) { GLES32.GL_COLOR_ATTACHMENT0 + it }, 0)

    override fun isFramebufferComplete() =
        GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER) == GLES32.GL_FRAMEBUFFER_COMPLETE

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES32.glViewport(x, y, width, height)
    override fun disableFullscreenDrawCapabilities() {
        for (cap in intArrayOf(
            GLES32.GL_BLEND, GLES32.GL_DITHER, GLES32.GL_DEPTH_TEST, GLES32.GL_STENCIL_TEST,
            GLES32.GL_SCISSOR_TEST, GLES32.GL_CULL_FACE, GLES32.GL_RASTERIZER_DISCARD,
        )) GLES32.glDisable(cap)
    }

    override fun enableColorWrites() = GLES32.glColorMask(true, true, true, true)
    override fun drawFullscreenTriangle() = GLES32.glDrawArrays(GLES32.GL_TRIANGLES, 0, 3)

    override fun clearColorToZero(framebuffer: Int) {
        bindFramebuffer(framebuffer)
        GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
        enableColorWrites()
        GLES32.glClearBufferfv(GLES32.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
    }

    override fun copyTexture2d(source: Int, destination: Int, width: Int, height: Int) =
        GLES32.glCopyImageSubData(
            source, GLES32.GL_TEXTURE_2D, 0, 0, 0, 0,
            destination, GLES32.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1,
        )

    override fun maxComputeGroups(axis: Int) =
        IntArray(1).also { GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis, it, 0) }[0]

    override fun bindWriteImage2d(unit: Int, texture: Int, format: Int) =
        GLES32.glBindImageTexture(unit, texture, 0, false, 0, GLES32.GL_WRITE_ONLY, format)

    override fun dispatchCompute(x: Int, y: Int, z: Int) = GLES32.glDispatchCompute(x, y, z)
    override fun memoryBarrier(bits: Int) = GLES32.glMemoryBarrier(bits)

    private fun integer(name: Int) = IntArray(1).also { GLES32.glGetIntegerv(name, it, 0) }[0]
}
