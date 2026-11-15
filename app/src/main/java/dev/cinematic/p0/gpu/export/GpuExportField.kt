package dev.cinematic.p0.gpu.export

import android.opengl.GLES32
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.StageClearBlur

/** A single global reduced field, not a tile-local blur. All methods run on its GL thread.
 * The caller owns resample and every returned expanded band. No full-size intermediate is retained.
 */
internal class GpuExportField(
    private val egl: EglContexts,
    private val ledger: GpuMemoryLedger,
    private val resample: GpuResample,
    val imageWidth: Int,
    val imageHeight: Int,
    val width: Int,
    val height: Int,
    private val format: GpuTexture.Format,
    private val interpolation: StageClearBlur.Filter = StageClearBlur.Filter.BILINEAR,
    private val pool: GpuTexturePool? = null,
) : AutoCloseable {
    // Match GpuResample/StageClearBlur's axis order. The vertical-first temporary
    // is imageWidth x reducedHeight, never imageWidth x imageHeight.
    private val verticalFirst = imageHeight.toLong() - height > 0 &&
        imageHeight.toLong() - height > 2 * (imageWidth.toLong() - width)
    private val accumulationWidth = if (verticalFirst) imageWidth else width
    private var accumulation: GpuTexture? = null
    private var scratch: GpuTexture? = null
    private var field: GpuTexture? = null
    private var nextY = 0
    private var closed = false

    init {
        try {
            require(width in 1..imageWidth && height in 1..imageHeight)
            require(format == GpuTexture.Format.R32F || format == GpuTexture.Format.RGBA32F)
            require(interpolation != StageClearBlur.Filter.BOX)
            accumulation = GpuTexture.create(egl, ledger, accumulationWidth, height, format, pool)
            scratch = GpuTexture.create(egl, ledger, accumulationWidth, height, format, pool)
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, accumulation!!.framebufferId)
            GLES32.glDisable(GLES32.GL_SCISSOR_TEST)
            GLES32.glColorMask(true, true, true, true)
            GLES32.glClearBufferfv(GLES32.GL_COLOR, 0, floatArrayOf(0f, 0f, 0f, 0f), 0)
            egl.checkGl("clear export field")
        } catch (failure: Throwable) { close(); throw failure }
    }

    /** Borrow the source band. Vertical adds never regroup at band boundaries.
     * Horizontal BOX is before/after those adds according to the full-frame axis order.
     */
    fun accept(originY: Int, input: GpuTexture) {
        check(!closed && originY == nextY) { "Export bands must be contiguous and ordered" }
        input.requireContext(egl)
        require(input.memoryLedger === ledger && input.format == format)
        require(input.width == imageWidth && input.height <= imageHeight - originY)
        val previous = checkNotNull(accumulation)
        val destination = checkNotNull(scratch)
        val horizontal = if (verticalFirst) input else resample.reduce(input, width, input.height)
        try {
            val weights = resample.weights(imageHeight, height, StageClearBlur.Filter.BOX)
            val path = if (format == GpuTexture.Format.R32F) "export/accumulate_plane.comp" else "export/accumulate_rgb.comp"
            // A recycled allocation can have earlier sampling commands queued on this context.
            if (pool != null) GLES32.glMemoryBarrier(GLES32.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            egl.passes().run(ComputePass(egl.shaders().compute(path),
                listOf(TextureInput("s_band", horizontal, 0), TextureInput("s_weights", weights.values, 1),
                    TextureInput("s_first", weights.first, 2), TextureInput("s_previous", previous, 3)),
                (accumulationWidth + 7) / 8, (height + 7) / 8,
                uniforms = { program ->
                    GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "u_originY"), originY)
                    GLES32.glUniform1i(GLES32.glGetUniformLocation(program, "u_bandHeight"), input.height)
                    GLES32.glUniform2i(GLES32.glGetUniformLocation(program, "u_fieldSize"), accumulationWidth, height)
                }, bindResources = {
                    // RGBA32F read-write images are not legal in GLES 3.2. Read the
                    // previous texture and write a distinct texture, then swap after the barrier.
                    GLES32.glBindImageTexture(0, destination.textureId, 0, false, 0, GLES32.GL_WRITE_ONLY, format.internalFormat)
                }))
            accumulation = destination
            scratch = previous
            nextY += input.height
            if (nextY == imageHeight) {
                field = if (verticalFirst) resample.reduce(destination, width, height) else destination
                if (field !== destination) destination.close()
                accumulation = null
                previous.close()
                scratch = null
            }
        } finally { if (horizontal !== input) horizontal.close() }
    }

    /** Borrowed, complete global field, for diagnostic comparison only. */
    fun reduced(): GpuTexture {
        check(!closed && nextY == imageHeight) { "Incomplete export field" }
        return checkNotNull(field)
    }

    fun band(originY: Int, rows: Int): GpuTexture {
        val completeField = reduced()
        require(originY >= 0 && rows > 0 && originY.toLong() + rows <= imageHeight)
        val x = resample.weights(width, imageWidth, interpolation)
        val y = resample.weights(height, imageHeight, interpolation)
        val output = GpuTexture.create(egl, ledger, imageWidth, rows, format, pool)
        try {
            egl.passes().run(FragmentPass(egl.shaders().fragment("export/upsample_band.frag"),
                listOf(TextureInput("s_field", completeField, 0), TextureInput("s_xWeights", x.values, 1),
                    TextureInput("s_xFirst", x.first, 2), TextureInput("s_yWeights", y.values, 3),
                    TextureInput("s_yFirst", y.first, 4)), output,
                uniforms = { GLES32.glUniform1i(GLES32.glGetUniformLocation(it, "u_originY"), originY) }))
            return output
        } catch (failure: Throwable) { output.close(); throw failure }
    }

    override fun close() {
        if (closed) return
        try { field?.close() } finally {
            try { accumulation?.close() } finally { scratch?.close(); closed = true }
        }
    }
}
