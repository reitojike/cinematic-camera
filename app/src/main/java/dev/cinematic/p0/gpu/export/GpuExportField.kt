package dev.cinematic.p0.gpu.export

import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.StageClearBlur

/** A single global reduced field, not a tile-local blur. All methods run on its GL thread.
 * The caller owns resample and every returned expanded band. No full-size intermediate is retained.
 */
internal class GpuExportField(
    private val egl: GpuExecutionContext,
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
    private val commands = egl.passOperations

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
            commands.clearColorToZero(accumulation!!.framebufferId)
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
            if (pool != null) commands.memoryBarrier(GPU_SHADER_IMAGE_ACCESS_BARRIER_BIT)
            egl.passes().run(ComputePass(egl.shaders().compute(path),
                listOf(TextureInput("s_band", horizontal, 0), TextureInput("s_weights", weights.values, 1),
                    TextureInput("s_first", weights.first, 2), TextureInput("s_previous", previous, 3)),
                (accumulationWidth + 7) / 8, (height + 7) / 8,
                uniforms = { program ->
                    commands.uniform1i(egl.shaders().uncachedUniformLocation(program, "u_originY"), originY)
                    commands.uniform1i(egl.shaders().uncachedUniformLocation(program, "u_bandHeight"), input.height)
                    commands.uniform2i(egl.shaders().uncachedUniformLocation(program, "u_fieldSize"), accumulationWidth, height)
                }, bindResources = {
                    // RGBA32F read-write images are not legal in GLES 3.2. Read the
                    // previous texture and write a distinct texture, then swap after the barrier.
                    commands.bindWriteImage2d(0, destination.textureId, format.internalFormat)
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
                uniforms = { commands.uniform1i(egl.shaders().uncachedUniformLocation(it, "u_originY"), originY) }))
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
