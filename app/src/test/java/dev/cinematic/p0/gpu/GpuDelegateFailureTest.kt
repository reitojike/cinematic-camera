package dev.cinematic.p0.gpu

import java.nio.Buffer
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GpuDelegateFailureTest {
    private val calls = mutableListOf<String>()
    private val context = RecordingResourceContext(calls)
    private val programs = RecordingProgramOperations(calls)
    private val library = ShaderLibrary(context, ShaderSource({ "#version 320 es\nvoid main() {}" }), programs)
    private val textures = RecordingTextureOperations(calls)
    private val execution = RecordingExecutionContext(context, textures)
    private val ledger = GpuMemoryLedger()

    private val compileCalls = listOf(
        "current", "current",
        "createShader VERTEX", "compileShader 1", "shaderCompiled 1",
        "createShader FRAGMENT", "compileShader 2", "shaderCompiled 2",
    )
    private val linkCalls = compileCalls + listOf("createProgram", "linkProgram 3 [1, 2]", "programLinked 3")
    private val deleteShaders = listOf("deleteShader 1", "deleteShader 2")
    private val framebufferCalls = listOf("current", "generateFramebuffer", "attach 5 4", "framebufferComplete")

    @Test fun compileFailureDeletesBothShadersAndRetriesFromCreation() {
        programs.failingShader = 2
        repeat(2) {
            assertFailure("Shader compile failed: test.frag\ncompile log") { library.fragment("test.frag") }
        }
        val attempt = compileCalls + "shaderInfoLog 2" + deleteShaders
        assertEquals(attempt + attempt, calls)
    }

    @Test fun linkFailureDeletesShadersThenProgramAndDoesNotCache() {
        programs.linked = false
        repeat(2) {
            assertFailure("Shader link failed: test.frag\nlink log") { library.fragment("test.frag") }
        }
        val attempt = linkCalls + "programInfoLog 3" + deleteShaders + "deleteProgram 3"
        assertEquals(attempt + attempt, calls)
    }

    @Test fun programGlErrorPropagatesAndDeletesShadersThenProgram() {
        val failure = IllegalStateException("compile/link test.frag failed: GL 0x502")
        context.glFailure = failure
        val thrown = assertThrows(IllegalStateException::class.java) { library.fragment("test.frag") }
        assertSame(failure, thrown)
        assertEquals(linkCalls + "checkGl compile/link test.frag" + deleteShaders + "deleteProgram 3", calls)
    }

    @Test fun zeroProgramDeletesOnlyShaders() {
        programs.program = 0
        assertFailure("glCreateProgram failed: test.frag") { library.fragment("test.frag") }
        assertEquals(compileCalls + "createProgram" + deleteShaders, calls)
    }

    @Test fun successfulProgramIsCachedAndUniformLookupUsesOperations() {
        library.use {
            val program = it.fragment("test.frag")
            assertEquals(3, program.id)
            assertFalse(program.compute)
            assertSame(program, it.fragment("test.frag"))
            assertEquals(6, it.uniformLocation(program.id, "uInput"))
            assertEquals(6, program.uniformLocation("uInput"))
            assertEquals(
                linkCalls + "checkGl compile/link test.frag" + deleteShaders +
                    listOf(
                        "current", "current", // Initial cache insertion and program.id.
                        "current", "current", // Cached fragment lookup.
                        "current", "current", "current", "uniformLocation 3 uInput",
                        "current", // Cached uniform lookup still checks the context.
                    ),
                calls,
            )
        }
    }

    @Test fun zeroFramebufferIsDeletedAndRetried() {
        textures.framebuffer = 0
        val storage = storage()
        calls.clear()
        repeat(2) {
            assertFailure("glGenFramebuffers failed") { storage.framebufferId }
        }
        val attempt = listOf("current", "generateFramebuffer", "deleteFramebuffer 0")
        assertEquals(attempt + attempt, calls)
    }

    @Test fun incompleteFramebufferIsDeletedAndRetried() {
        textures.complete = false
        val storage = storage()
        calls.clear()
        repeat(2) {
            assertFailure("RGBA8 cannot be attached to a complete FBO") { storage.framebufferId }
        }
        val attempt = framebufferCalls + "deleteFramebuffer 5"
        assertEquals(attempt + attempt, calls)
    }

    @Test fun framebufferGlErrorPropagatesAndDeletesFramebuffer() {
        val storage = storage()
        calls.clear()
        val failure = IllegalStateException("create framebuffer failed: GL 0x502")
        context.glFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { storage.framebufferId })
        assertEquals(framebufferCalls + listOf("checkGl create framebuffer", "deleteFramebuffer 5"), calls)

        context.glFailure = null
        calls.clear()
        assertEquals(5, storage.framebufferId)
        assertEquals(framebufferCalls + "checkGl create framebuffer", calls)
    }

    @Test fun allocationGlErrorPropagatesAndReleasesTextureAndLedgerReservation() {
        val failure = IllegalStateException("allocate RGBA8 texture failed: GL 0x502")
        context.glFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { storage() })
        assertEquals(
            listOf(
                "current", "maxTextureSize", "generateTexture", "allocateTexture 4", "checkGl allocate RGBA8 texture",
                "ownerThread", "releaseResource", "deleteTexture 4",
            ),
            calls,
        )
        assertEquals(0L, ledger.allocatedBytes)
        assertEquals(0, ledger.textureCount)
        assertEquals(16L, ledger.peakAllocatedBytes)
    }

    @Test fun successfulFramebufferIsGeneratedOnce() {
        val storage = storage()
        calls.clear()
        assertEquals(5, storage.framebufferId)
        assertEquals(5, storage.framebufferId)
        assertEquals(framebufferCalls + listOf("checkGl create framebuffer", "current"), calls)
    }

    @Test fun uploadChecksCurrentContextBetweenPreparationAndTransferAndPropagatesGlError() {
        val storage = storage()
        calls.clear()
        val data = ByteBuffer.allocateDirect(16)
        val currentFailure = IllegalStateException("EGL context is not current")
        context.currentFailure = currentFailure
        assertSame(currentFailure, assertThrows(IllegalStateException::class.java) { storage.upload(data) })
        assertEquals(listOf("prepareTextureUpload", "current"), calls)

        calls.clear()
        context.currentFailure = null
        val glFailure = IllegalStateException("upload texture failed: GL 0x502")
        context.glFailure = glFailure
        assertSame(glFailure, assertThrows(IllegalStateException::class.java) { storage.upload(data) })
        assertEquals(listOf("prepareTextureUpload", "current", "uploadTexture 4", "checkGl upload texture"), calls)
    }

    private fun storage() = GpuTexture.Storage(execution, ledger, 2, 2, GpuTexture.Format.RGBA8)

    private fun assertFailure(message: String, block: () -> Unit) {
        assertEquals(message, assertThrows(IllegalStateException::class.java) { block() }.message)
    }

    private class RecordingResourceContext(private val calls: MutableList<String>) : GpuResourceContext {
        var currentFailure: IllegalStateException? = null
        var glFailure: IllegalStateException? = null

        override fun requireOwnerThread() {
            calls += "ownerThread"
        }

        override fun requireCurrent() {
            calls += "current"
            currentFailure?.let { throw it }
        }

        override fun checkGl(operation: String) {
            calls += "checkGl $operation"
            glFailure?.let { throw it }
        }

        override fun releaseResource(block: () -> Unit) {
            calls += "releaseResource"
            block()
        }
    }

    private class RecordingExecutionContext(
        context: GpuResourceContext,
        override val textureOperations: GpuTextureOperations,
    ) : GpuExecutionContext, GpuResourceContext by context {
        override fun capabilities(): GlCapabilities.Result = error("Unexpected capabilities lookup")
        override fun shaders(): ShaderLibrary = error("Unexpected shader library lookup")
        override fun passes(): PassGraph = error("Unexpected pass graph lookup")
        override val passOperations: GpuPassOperations
            get() = error("Unexpected pass operations lookup")
    }

    private class RecordingProgramOperations(private val calls: MutableList<String>) : GpuProgramOperations {
        var failingShader: Int? = null
        var linked = true
        var program = 3

        override fun createShader(stage: GpuShaderStage): Int {
            calls += "createShader $stage"
            return stage.ordinal + 1
        }

        override fun compileShader(shader: Int, source: String) {
            calls += "compileShader $shader"
        }

        override fun shaderCompiled(shader: Int): Boolean {
            calls += "shaderCompiled $shader"
            return shader != failingShader
        }

        override fun shaderInfoLog(shader: Int): String {
            calls += "shaderInfoLog $shader"
            return "compile log"
        }

        override fun createProgram(): Int {
            calls += "createProgram"
            return program
        }

        override fun linkProgram(program: Int, shaders: List<Int>) {
            calls += "linkProgram $program $shaders"
        }

        override fun programLinked(program: Int): Boolean {
            calls += "programLinked $program"
            return linked
        }

        override fun programInfoLog(program: Int): String {
            calls += "programInfoLog $program"
            return "link log"
        }

        override fun uniformLocation(program: Int, name: String): Int {
            calls += "uniformLocation $program $name"
            return 6
        }

        override fun deleteShader(shader: Int) {
            calls += "deleteShader $shader"
        }

        override fun deleteProgram(program: Int) {
            calls += "deleteProgram $program"
        }
    }

    private class RecordingTextureOperations(private val calls: MutableList<String>) : GpuTextureOperations {
        var framebuffer = 5
        var complete = true

        override fun maxTextureSize(): Int {
            calls += "maxTextureSize"
            return 16
        }

        override fun generateTexture(): Int {
            calls += "generateTexture"
            return 4
        }

        override fun allocateTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format) {
            calls += "allocateTexture $texture"
        }

        override fun prepareTextureUpload() {
            calls += "prepareTextureUpload"
        }

        override fun uploadTexture(texture: Int, width: Int, height: Int, format: GpuTexture.Format, data: Buffer) {
            calls += "uploadTexture $texture"
        }

        override fun generateFramebuffer(): Int {
            calls += "generateFramebuffer"
            return framebuffer
        }

        override fun attachFramebufferTexture(framebuffer: Int, texture: Int) {
            calls += "attach $framebuffer $texture"
        }

        override fun framebufferComplete(): Boolean {
            calls += "framebufferComplete"
            return complete
        }

        override fun deleteFramebuffer(framebuffer: Int) {
            calls += "deleteFramebuffer $framebuffer"
        }

        override fun deleteTexture(texture: Int) {
            calls += "deleteTexture $texture"
        }
    }
}
