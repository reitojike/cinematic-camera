package dev.cinematic.p0.gpu

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
    val memoryBarrier: Int = GPU_ALL_BARRIER_BITS,
)

/** 明示したプログラム/入力/出力/領域/uniformから1パスを実行する。 */
internal class PassGraph(private val egl: GpuExecutionContext) : AutoCloseable {
    private val commands = egl.passOperations
    private var vao: Int
    init {
        egl.requireCurrent()
        vao = commands.createVertexArray()
        check(vao != 0) { "glGenVertexArrays failed" }
    }
    private fun bind(program: GpuProgram, inputs: List<TextureInput>): Int {
        egl.requireCurrent(); check(vao != 0) { "Pass graph is closed" }; program.requireContext(egl)
        val maximum = commands.maxTextureUnits()
        require(inputs.map { it.unit }.distinct().size == inputs.size) { "Duplicate texture units" }
        val id = program.id; commands.useProgram(id)
        for (input in inputs) {
            require(input.unit in 0 until maximum); input.texture.requireContext(egl)
            val location = program.uniformLocation(input.uniformName)
            require(location >= 0) { "Input sampler is missing or inactive: ${input.uniformName}" }
            commands.activateTextureUnit(input.unit)
            commands.bindTexture2d(input.texture.textureId)
            commands.uniform1i(location, input.unit)
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
                val attachmentLimit = commands.maxColorAttachments()
                val drawLimit = commands.maxDrawBuffers()
                require(outputs.size <= minOf(attachmentLimit, drawLimit)) { "Too many output attachments" }
                multipleFramebuffer = commands.createFramebuffer()
                check(multipleFramebuffer != 0) { "glGenFramebuffers failed" }
                commands.bindFramebuffer(multipleFramebuffer)
                outputs.forEachIndexed { index, texture ->
                    commands.attachColorTexture(index, texture.textureId)
                }
                commands.drawColorAttachments(outputs.size)
                check(commands.isFramebufferComplete()) { "Incomplete multiple-output FBO" }
            } else {
                commands.bindFramebuffer(pass.output?.framebufferId ?: 0)
            }
            commands.viewport(viewport.x, viewport.y, viewport.width, viewport.height)
            commands.disableFullscreenDrawCapabilities()
            commands.enableColorWrites()
            commands.bindVertexArray(vao)
            pass.uniforms(program)
            commands.drawFullscreenTriangle()
            egl.checkGl("fragment pass")
            return System.nanoTime() - start
        } finally {
            if (multipleFramebuffer != 0) egl.releaseResource {
                commands.deleteFramebuffer(multipleFramebuffer)
            }
        }
    }
    fun run(pass: ComputePass): Long {
        require(pass.program.compute) { "Compute pass requires a compute program" }
        val start = System.nanoTime()
        val program = bind(pass.program, pass.inputs)
        intArrayOf(pass.groupsX, pass.groupsY, pass.groupsZ).forEachIndexed { axis, groups ->
            val max = commands.maxComputeGroups(axis)
            require(groups in 1..max) { "Invalid compute work-group count" }
        }
        pass.uniforms(program); pass.bindResources()
        commands.dispatchCompute(pass.groupsX, pass.groupsY, pass.groupsZ)
        commands.memoryBarrier(pass.memoryBarrier)
        egl.checkGl("compute pass")
        return System.nanoTime() - start
    }
    override fun close() {
        egl.requireOwnerThread()
        if (vao == 0) return
        egl.releaseResource { commands.deleteVertexArray(vao) }; vao = 0
    }
}
