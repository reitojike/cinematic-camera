package dev.cinematic.p0.gpu

import java.nio.Buffer

/** Texture and attachment operations for the current context. Storage owns their lifetimes. */
internal interface GpuTextureOperations {
    fun maxTextureSize(): Int
    fun generateTexture(): Int
    fun allocateTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format)
    fun uploadTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format, data: Buffer)
    fun createFramebuffer(texture: Int, format: GpuTexture.Format): Int
    fun deleteFramebuffer(framebuffer: Int)
    fun deleteTexture(texture: Int)
}
