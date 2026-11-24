package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.gpu.GpuSyncOperations
import dev.cinematic.p0.gpu.GpuTexture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lwjgl.opengles.GLES20.GL_VERSION
import org.lwjgl.opengles.GLES20.glGetString
import org.lwjgl.system.MemoryStack.stackPush

class MesaExecutionContextTest {
    @Test
    fun capabilitiesAndDelegatesRoundTrip() {
        MesaExecutionContext().use { context ->
            val capabilities = context.capabilities()
            println("GPU_MESA_EXECUTION renderer=${capabilities.renderer} version=${glGetString(GL_VERSION)}")
            assertTrue(capabilities.missing.joinToString(), capabilities.supported)
            verifyTextureRoundTrip(context)
            verifySsboAndFence(context)
        }
    }

    @Test
    fun productShaderAndPassGraphInitialize() {
        MesaExecutionContext().use { context ->
            val program = context.shaders().fragment("display/encode.frag")
            assertTrue(program.id != 0)
            context.passes()
            context.checkGl("initialize shader and pass graph")
        }
    }

    private fun verifyTextureRoundTrip(context: MesaExecutionContext) {
        val textures = context.textureOperations
        val pixels = context.pixelTransferOperations
        val format = GpuTexture.Format.RGBA32F
        val texture = textures.generateTexture()
        try {
            assertTrue(texture != 0)
            textures.allocateTexture(texture, 1, 1, format)
            stackPush().use { stack ->
                val expected = floatArrayOf(0.125f, 0.5f, 0.75f, 1f)
                val upload = stack.mallocFloat(expected.size)
                upload.put(expected).flip()
                textures.prepareTextureUpload()
                textures.uploadTexture(texture, 1, 1, format, upload)

                val framebuffer = textures.generateFramebuffer()
                try {
                    assertTrue(framebuffer != 0)
                    textures.attachFramebufferTexture(framebuffer, texture)
                    assertTrue(textures.framebufferComplete())
                    pixels.prepareReadback(framebuffer)
                    val readback = stack.mallocFloat(expected.size)
                    pixels.readRgbaFloat(1, 1, readback)
                    context.checkGl("RGBA32F round trip")
                    assertArrayEquals(expected, FloatArray(expected.size) { readback[it] }, 0f)
                } finally {
                    context.releaseResource { textures.deleteFramebuffer(framebuffer) }
                }
            }
        } finally {
            context.releaseResource { textures.deleteTexture(texture) }
        }
    }

    private fun verifySsboAndFence(context: MesaExecutionContext) {
        val ssbo = context.ssboOperations
        val sync = context.syncOperations
        val names = IntArray(1)
        ssbo.generateBuffers(names.size, names)
        try {
            assertTrue(names[0] != 0)
            ssbo.bindBuffer(names[0])
            val expected: Byte = 0x5a
            stackPush().use { stack ->
                val upload = stack.malloc(2)
                upload.put(0, expected)
                ssbo.allocateDynamicRead(1, upload)
                context.checkGl("allocate SSBO")

                val fence = sync.createFence()
                try {
                    assertTrue(fence != 0L)
                    sync.flush()
                    assertEquals(GpuSyncOperations.WaitResult.SIGNALED, sync.clientWait(fence, 1_000_000_000L))
                } finally {
                    if (fence != 0L) context.releaseResource { sync.deleteFence(fence) }
                }
            }
            val mapped = checkNotNull(ssbo.mapRead(0, 1)) { "SSBO map failed" }
            try {
                assertEquals(expected, mapped.get(0))
            } finally {
                assertTrue(ssbo.unmapBuffer())
            }
            context.checkGl("SSBO and fence round trip")
        } finally {
            context.releaseResource { ssbo.deleteBuffers(names.size, names) }
        }
    }
}
