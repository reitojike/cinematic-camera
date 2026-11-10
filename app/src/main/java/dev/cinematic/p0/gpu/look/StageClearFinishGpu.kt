package dev.cinematic.p0.gpu.look

import android.opengl.GLES32
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.StageClearCharacterV1
import dev.cinematic.p0.raw.StageClearOrtonV1
import kotlin.math.max
import kotlin.math.min

/** Photo-local finishing passes. Inputs and synchronous tap textures are borrowed.
 * render/encode return a new caller-owned texture; intermediates are released eagerly.
 */
internal class StageClearFinishGpu(private val egl: EglContexts, private val ledger: GpuMemoryLedger,
    private val resample: GpuResample = GpuResample(egl, ledger),
    private val ownsResample: Boolean = true) : AutoCloseable {
    private var closed = false

    fun render(mist: MistGpuResult, strength: Double, faceMask: GpuTexture? = null,
        faceCutFactor: Double = 1.0, tap: ((Point, GpuTexture) -> Unit)? = null): GpuTexture {
        require(faceCutFactor == 1.0) { "GPU face cut is not implemented" }
        require(strength.isFinite() && strength in 0.0..1.0)
        require(mist.lightAddingStrength.isFinite() && mist.lightAddingStrength in 0.0..1.0)
        validate(mist.image, GpuTexture.Format.RGBA32F)
        validate(mist.skinWeight, GpuTexture.Format.R32F, mist.image)
        faceMask?.let { validate(it, GpuTexture.Format.R32F, mist.image) }
        var current = mist.image
        try {
            if (mist.lightAddingStrength != 0.0) current = orton(mist)
            tap?.invoke(Point.ORTON, current)
            fun next(point: Point, create: () -> GpuTexture) {
                val previous = current
                current = create()
                if (previous !== mist.image) previous.close()
                tap?.invoke(point, current)
            }
            next(Point.CHARACTER) {
                pass("look/character.frag", current, listOf(TextureInput("s_orton", current, 0))) { program ->
                    val aspect = current.width.toDouble() / current.height
                    GLES32.glUniform2i(location(program, "u_imageSize"), current.width, current.height)
                    GLES32.glUniform2i(location(program, "u_origin"), 0, 0)
                    GLES32.glUniform2f(location(program, "u_aspect"), min(aspect, 1.0).toFloat(), max(aspect, 1.0).toFloat())
                    GLES32.glUniform1f(location(program, "u_vignetteAmount"), (StageClearCharacterV1.VIGNETTE * strength).toFloat())
                    GLES32.glUniform1f(location(program, "u_vibranceAmount"), (StageClearCharacterV1.VIBRANCE * strength).toFloat())
                }
            }
            next(Point.SKIN_CAP) { pass("look/skin_chroma_cap.frag", current,
                listOf(TextureInput("s_character", current, 0), TextureInput("s_faceMask", faceMask ?: current, 1))) { program ->
                    GLES32.glUniform1i(location(program, "u_hasFaceMask"), if (faceMask == null) 0 else 1)
                } }
            next(Point.SHOULDER) { pass("look/shoulder.frag", current, listOf(TextureInput("s_skinCap", current, 0))) }
            return current
        } catch (failure: Throwable) {
            if (current !== mist.image) current.close()
            throw failure
        }
    }

    private fun orton(mist: MistGpuResult): GpuTexture {
        val bright = pass("look/orton_prepare.frag", mist.image, listOf(TextureInput("s_mist", mist.image, 0)))
        val soft = try { resample.blurRgb(bright, StageClearOrtonV1.RADIUS) }
            catch (failure: Throwable) { bright.close(); throw failure }
        if (soft !== bright) bright.close()
        val mixed = try { pass("look/orton_mix.frag", mist.image,
            listOf(TextureInput("s_mist", mist.image, 0), TextureInput("s_soft", soft, 1))) }
            finally { soft.close() }
        return mixed.use { pass("look/orton_protect.frag", mist.image,
            listOf(TextureInput("s_mist", mist.image, 0), TextureInput("s_mixed", mixed, 1), TextureInput("s_skinWeight", mist.skinWeight, 2))) { program ->
                GLES32.glUniform1f(location(program, "u_amount"), mist.lightAddingStrength.toFloat())
            }
        }
    }

    fun encode(linear: GpuTexture): GpuTexture {
        validate(linear, GpuTexture.Format.RGBA32F)
        return pass("display/encode.frag", linear, listOf(TextureInput("s_linear", linear, 0)), GpuTexture.Format.RGBA8)
    }
    private fun pass(path: String, size: GpuTexture, inputs: List<TextureInput>,
        format: GpuTexture.Format = GpuTexture.Format.RGBA32F, uniforms: (Int) -> Unit = {}): GpuTexture {
        val output = size.createSibling(format = format)
        try {
            egl.passes().run(FragmentPass(egl.shaders().fragment(path), inputs, output, uniforms = uniforms))
            return output
        } catch (failure: Throwable) { output.close(); throw failure }
    }
    private fun location(program: Int, name: String) = GLES32.glGetUniformLocation(program, name).also { check(it >= 0) { "Missing uniform $name" } }
    private fun validate(texture: GpuTexture, format: GpuTexture.Format, aligned: GpuTexture? = null) {
        egl.requireCurrent(); check(!closed) { "GPU finish is closed" }
        texture.requireContext(egl)
        require(texture.memoryLedger === ledger && texture.format == format)
        if (aligned != null) require(texture.width == aligned.width && texture.height == aligned.height)
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        if (ownsResample) resample.close(); closed = true
    }
}
