package dev.cinematic.p0.gpu

/** Pass commands on the current context. The graph retains validation and command ordering. */
internal interface GpuPassOperations {
    fun createVertexArray(): Int
    fun deleteVertexArray(array: Int)
    fun bindVertexArray(array: Int)
    fun maxTextureUnits(): Int
    fun useProgram(program: Int)
    fun activateTextureUnit(unit: Int)
    fun bindTexture2d(texture: Int)
    fun uniform1i(location: Int, value: Int)
    fun uniform2i(location: Int, x: Int, y: Int)
    fun uniform1f(location: Int, value: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)

    fun maxColorAttachments(): Int
    fun maxDrawBuffers(): Int
    fun createFramebuffer(): Int
    fun deleteFramebuffer(framebuffer: Int)
    fun bindFramebuffer(framebuffer: Int)
    fun attachColorTexture(index: Int, texture: Int)
    fun drawColorAttachments(count: Int)
    fun isFramebufferComplete(): Boolean
    fun viewport(x: Int, y: Int, width: Int, height: Int)
    fun disableFullscreenDrawCapabilities()
    fun enableColorWrites()
    fun drawFullscreenTriangle()
    fun clearColorToZero(framebuffer: Int)
    fun copyTexture2d(source: Int, destination: Int, width: Int, height: Int)

    fun maxComputeGroups(axis: Int): Int
    fun bindWriteImage2d(unit: Int, texture: Int, format: Int)
    fun dispatchCompute(x: Int, y: Int, z: Int)
    fun memoryBarrier(bits: Int)
}

internal const val GPU_ALL_BARRIER_BITS = -1
internal const val GPU_SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x00000020
