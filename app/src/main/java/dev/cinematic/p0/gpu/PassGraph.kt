package dev.cinematic.p0.gpu

import android.opengl.GLES32

internal data class TextureInput(val uniformName: String, val texture: GpuTexture, val unit: Int)
internal data class GpuViewport(val x: Int, val y: Int, val width: Int, val height: Int)
internal data class FragmentPass(
    val program: GpuProgram,
    val inputs: List<TextureInput>,
    val output: GpuTexture?,
    val viewport: GpuViewport? = null,
    val uniforms: (Int) -> Unit = {},
    // output is location 0; these are locations 1..N. Textures remain caller-owned.
    val additionalOutputs: List<GpuTexture> = emptyList(),
)
internal data class ComputePass(
    val program: GpuProgram,
    val inputs: List<TextureInput>,
    val groupsX: Int, val groupsY: Int = 1, val groupsZ: Int = 1,
    val uniforms: (Int) -> Unit = {},
    val bindResources: () -> Unit = {},
    val memoryBarrier: Int = GLES32.GL_ALL_BARRIER_BITS,
)

/** 明示したプログラム/入力/出力/領域/uniformから1パスを実行する。 */
internal class PassGraph(private val egl: EglContexts) : AutoCloseable {
    private var vao: Int
    init {
        egl.requireCurrent()
        vao = IntArray(1).also { GLES32.glGenVertexArrays(1, it, 0) }[0]
        check(vao != 0) { "glGenVertexArrays failed" }
    }
    private fun bind(program: GpuProgram, inputs: List<TextureInput>): Int {
        egl.requireCurrent(); check(vao != 0) { "Pass graph is closed" }; program.requireContext(egl)
        val maximum = IntArray(1).also { GLES32.glGetIntegerv(GLES32.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, it, 0) }[0]
        require(inputs.map { it.unit }.distinct().size == inputs.size) { "Duplicate texture units" }
        val id = program.id; GLES32.glUseProgram(id)
        for (input in inputs) {
            require(input.unit in 0 until maximum); input.texture.requireContext(egl)
            val location = GLES32.glGetUniformLocation(id, input.uniformName)
            require(location >= 0) { "Input sampler is missing or inactive: ${input.uniformName}" }
            GLES32.glActiveTexture(GLES32.GL_TEXTURE0 + input.unit)
            GLES32.glBindTexture(GLES32.GL_TEXTURE_2D, input.texture.textureId)
            GLES32.glUniform1i(location, input.unit)
        }
        return id
    }
    /** 戻り値はCPU投入時間。GPU時間・表示待ちは含まない。 */
    fun run(pass: FragmentPass): Long {
        require(!pass.program.compute) { "Fragment pass requires a graphics program" }
        require(pass.output != null || pass.additionalOutputs.isEmpty()) { "Window output cannot have texture attachments" }
        val outputs = listOfNotNull(pass.output) + pass.additionalOutputs
        outputs.forEach { it.requireContext(egl) }
        require(outputs.distinct().size == outputs.size) { "Duplicate output attachments" }
        require(pass.inputs.none { it.texture in outputs }) { "Texture feedback is not allowed" }
        require(outputs.all { it.width == pass.output!!.width && it.height == pass.output.height }) { "Output dimensions differ" }
        val viewport = pass.viewport ?: pass.output?.let { GpuViewport(0, 0, it.width, it.height) }
            ?: error("Window output requires an explicit viewport")
        require(viewport.x >= 0 && viewport.y >= 0 && viewport.width > 0 && viewport.height > 0)
        pass.output?.let { require(viewport.x.toLong() + viewport.width <= it.width && viewport.y.toLong() + viewport.height <= it.height) }
        val start = System.nanoTime()
        val program = bind(pass.program, pass.inputs)
        var multipleFramebuffer = 0
        try {
            if (outputs.size > 1) {
                val attachmentLimit = IntArray(1).also { GLES32.glGetIntegerv(GLES32.GL_MAX_COLOR_ATTACHMENTS, it, 0) }[0]
                val drawLimit = IntArray(1).also { GLES32.glGetIntegerv(GLES32.GL_MAX_DRAW_BUFFERS, it, 0) }[0]
                require(outputs.size <= minOf(attachmentLimit, drawLimit)) { "Too many output attachments" }
                multipleFramebuffer = IntArray(1).also { GLES32.glGenFramebuffers(1, it, 0) }[0]
                check(multipleFramebuffer != 0) { "glGenFramebuffers failed" }
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, multipleFramebuffer)
                outputs.forEachIndexed { index, texture ->
                    GLES32.glFramebufferTexture2D(GLES32.GL_FRAMEBUFFER, GLES32.GL_COLOR_ATTACHMENT0 + index,
                        GLES32.GL_TEXTURE_2D, texture.textureId, 0)
                }
                GLES32.glDrawBuffers(outputs.size, IntArray(outputs.size) { GLES32.GL_COLOR_ATTACHMENT0 + it }, 0)
                check(GLES32.glCheckFramebufferStatus(GLES32.GL_FRAMEBUFFER) == GLES32.GL_FRAMEBUFFER_COMPLETE) { "Incomplete multiple-output FBO" }
            } else {
                GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, pass.output?.framebufferId ?: 0)
            }
            GLES32.glViewport(viewport.x, viewport.y, viewport.width, viewport.height)
            for (cap in intArrayOf(GLES32.GL_BLEND, GLES32.GL_DITHER, GLES32.GL_DEPTH_TEST, GLES32.GL_STENCIL_TEST,
                GLES32.GL_SCISSOR_TEST, GLES32.GL_CULL_FACE, GLES32.GL_RASTERIZER_DISCARD)) GLES32.glDisable(cap)
            GLES32.glColorMask(true, true, true, true)
            GLES32.glBindVertexArray(vao)
            pass.uniforms(program)
            GLES32.glDrawArrays(GLES32.GL_TRIANGLES, 0, 3)
            egl.checkGl("fragment pass")
            return System.nanoTime() - start
        } finally {
            if (multipleFramebuffer != 0) egl.releaseResource {
                GLES32.glDeleteFramebuffers(1, intArrayOf(multipleFramebuffer), 0)
            }
        }
    }
    fun run(pass: ComputePass): Long {
        require(pass.program.compute) { "Compute pass requires a compute program" }
        val start = System.nanoTime()
        val program = bind(pass.program, pass.inputs)
        intArrayOf(pass.groupsX, pass.groupsY, pass.groupsZ).forEachIndexed { axis, groups ->
            val max = IntArray(1).also { GLES32.glGetIntegeri_v(GLES32.GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis, it, 0) }[0]
            require(groups in 1..max) { "Invalid compute work-group count" }
        }
        pass.uniforms(program); pass.bindResources()
        GLES32.glDispatchCompute(pass.groupsX, pass.groupsY, pass.groupsZ)
        GLES32.glMemoryBarrier(pass.memoryBarrier)
        egl.checkGl("compute pass")
        return System.nanoTime() - start
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (vao == 0) return
        egl.releaseResource { GLES32.glDeleteVertexArrays(1, intArrayOf(vao), 0) }; vao = 0
    }
}
