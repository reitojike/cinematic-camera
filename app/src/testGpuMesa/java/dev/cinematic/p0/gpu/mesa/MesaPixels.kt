package dev.cinematic.p0.gpu.mesa

import org.lwjgl.opengles.GLES32.*
import org.lwjgl.system.MemoryUtil.memAllocFloat
import org.lwjgl.system.MemoryUtil.memFree

/** Host-only upload/draw/readback. This is not Android's resource or pass-graph acceptance. */
internal fun renderRgb(program: Int, width: Int, height: Int, rgb: FloatArray, operation: Int? = null): FloatArray {
    require(rgb.size == width * height * 3)
    val buffer = memAllocFloat(width * height * 4)
    val textures = IntArray(2)
    var framebuffer = 0
    var vao = 0
    try {
        repeat(width * height) { p ->
            repeat(3) { buffer.put(rgb[p * 3 + it]) }
            buffer.put(1f)
        }
        buffer.flip()
        glGenTextures(textures)
        for (texture in textures) {
            glBindTexture(GL_TEXTURE_2D, texture)
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, width, height)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }
        glBindTexture(GL_TEXTURE_2D, textures[0])
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_RGBA, GL_FLOAT, buffer)
        framebuffer = glGenFramebuffers()
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textures[1], 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) { "RGBA32F framebuffer incomplete" }
        vao = glGenVertexArrays()
        glBindVertexArray(vao)
        glViewport(0, 0, width, height)
        glDisable(GL_BLEND)
        glDisable(GL_DITHER)
        glUseProgram(program)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, textures[0])
        val sampler = glGetUniformLocation(program, "s_input")
        check(sampler >= 0) { "s_input was not active" }
        glUniform1i(sampler, 0)
        if (operation != null) {
            val location = glGetUniformLocation(program, "u_operation")
            check(location >= 0) { "u_operation was not active" }
            glUniform1i(location, operation)
        }
        glDrawArrays(GL_TRIANGLES, 0, 3)
        // Synchronous diagnostic readback, never a product rendering path.
        buffer.clear()
        glReadPixels(0, 0, width, height, GL_RGBA, GL_FLOAT, buffer)
        checkGl("RGBA32F render/readback ${width}x$height operation=$operation")
        return FloatArray(rgb.size) { buffer[(it / 3) * 4 + it % 3] }
    } finally {
        glUseProgram(0)
        glBindVertexArray(0)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glBindTexture(GL_TEXTURE_2D, 0)
        if (vao != 0) glDeleteVertexArrays(vao)
        if (framebuffer != 0) glDeleteFramebuffers(framebuffer)
        glDeleteTextures(textures)
        memFree(buffer)
    }
}
