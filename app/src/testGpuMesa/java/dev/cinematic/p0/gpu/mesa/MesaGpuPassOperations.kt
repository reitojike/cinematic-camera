package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GpuPassOperations
import org.lwjgl.opengles.GLES32.*
import org.lwjgl.system.MemoryStack.stackPush

internal object MesaGpuPassOperations : GpuPassOperations {
    override fun createVertexArray() = glGenVertexArrays()
    override fun deleteVertexArray(array: Int) = glDeleteVertexArrays(array)
    override fun bindVertexArray(array: Int) = glBindVertexArray(array)
    override fun maxTextureUnits() = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS)
    override fun useProgram(program: Int) = glUseProgram(program)
    override fun activateTextureUnit(unit: Int) = glActiveTexture(GL_TEXTURE0 + unit)
    override fun bindTexture2d(texture: Int) = glBindTexture(GL_TEXTURE_2D, texture)
    override fun uniform1i(location: Int, value: Int) = glUniform1i(location, value)
    override fun uniform2i(location: Int, x: Int, y: Int) = glUniform2i(location, x, y)
    override fun uniform4uiv(location: Int, count: Int, values: IntArray, offset: Int) =
        stackPush().use { stack ->
            val valueCount = Math.multiplyExact(count, 4)
            require(offset >= 0 && valueCount >= 0 && offset <= values.size - valueCount)
            val selected = stack.mallocInt(valueCount)
            selected.put(values, offset, valueCount).flip()
            glUniform4uiv(location, selected)
        }
    override fun uniform1f(location: Int, value: Float) = glUniform1f(location, value)
    override fun uniform2f(location: Int, x: Float, y: Float) = glUniform2f(location, x, y)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = glUniform3f(location, x, y, z)

    override fun maxColorAttachments() = glGetInteger(GL_MAX_COLOR_ATTACHMENTS)
    override fun maxDrawBuffers() = glGetInteger(GL_MAX_DRAW_BUFFERS)
    override fun createFramebuffer() = glGenFramebuffers()
    override fun deleteFramebuffer(framebuffer: Int) = glDeleteFramebuffers(framebuffer)
    override fun bindFramebuffer(framebuffer: Int) = glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    override fun attachColorTexture(index: Int, texture: Int) =
        glFramebufferTexture2D(
            GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + index, GL_TEXTURE_2D, texture, 0,
        )

    override fun drawColorAttachments(count: Int) =
        stackPush().use { stack ->
            val attachments = stack.mallocInt(count)
            for (index in 0 until count) attachments.put(index, GL_COLOR_ATTACHMENT0 + index)
            glDrawBuffers(attachments)
        }

    override fun isFramebufferComplete() =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = glViewport(x, y, width, height)
    override fun disableFullscreenDrawCapabilities() {
        for (cap in intArrayOf(
            GL_BLEND, GL_DITHER, GL_DEPTH_TEST, GL_STENCIL_TEST,
            GL_SCISSOR_TEST, GL_CULL_FACE, GL_RASTERIZER_DISCARD,
        )) glDisable(cap)
    }

    override fun enableColorWrites() = glColorMask(true, true, true, true)
    override fun drawFullscreenTriangle() = glDrawArrays(GL_TRIANGLES, 0, 3)

    override fun clearColorToZero(framebuffer: Int) {
        bindFramebuffer(framebuffer)
        glDisable(GL_SCISSOR_TEST)
        enableColorWrites()
        stackPush().use { stack ->
            glClearBufferfv(GL_COLOR, 0, stack.callocFloat(4))
        }
    }

    override fun copyTexture2d(source: Int, destination: Int, width: Int, height: Int) =
        glCopyImageSubData(
            source, GL_TEXTURE_2D, 0, 0, 0, 0,
            destination, GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1,
        )

    override fun maxComputeGroups(axis: Int) =
        glGetIntegeri(GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis)

    override fun bindWriteImage2d(unit: Int, texture: Int, format: Int) =
        glBindImageTexture(unit, texture, 0, false, 0, GL_WRITE_ONLY, format)

    override fun dispatchCompute(x: Int, y: Int, z: Int) = glDispatchCompute(x, y, z)
    override fun memoryBarrier(bits: Int) = glMemoryBarrier(bits)
}
