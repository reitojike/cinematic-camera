package dev.cinematic.p0.gpu.mesa

import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.StageClearBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lwjgl.opengles.GLES32.*
import org.lwjgl.system.MemoryUtil.*
import kotlin.math.abs
import java.util.Random

/** Executes the product export shaders on Mesa before Android integration.
 * These synthetic tests do not claim Android lifecycle/resource ownership acceptance.
 */
class MesaExportBandsTest {
    @Test fun fieldsHaveNo64Or256RowSeamsAndMatchKotlin() = MesaContext().use {
        val shaders = MesaShaders()
        // Include a verticalFirst geometry, final short bands, and CI export dimensions.
        for ((width, height) in listOf(257 to 1025, 1600 to 1200, 1603 to 1201)) {
            for (channels in listOf(1, 4)) {
                val random = Random(20261110L)
                val input = FloatArray(width * height * channels) { index ->
                    if (channels == 1) -12f + random.nextFloat() * 16f
                    else if (index % 4 == 3) 1f else random.nextFloat() * 4f
                }
                for (radius in listOf(.004, .04, .12)) {
                    val (fw, fh) = StageClearBlur.reducedSize(width, height, radius)
                    val result64 = ExportGl(shaders).use { gl -> gl.reduce(input, width, height, channels, fw, fh, 64) }
                    val result256 = ExportGl(shaders).use { gl -> gl.reduce(input, width, height, channels, fw, fh, 256) }
                    assertTrue("band partition changes $width x $height channels=$channels radius=$radius",
                        result64.contentEquals(result256))
                    val expected = FloatArray(result64.size)
                    repeat(if (channels == 1) 1 else 3) { c ->
                        val plane = FloatPlane(width, height, FloatArray(width * height) { input[it * channels + c] })
                        val reduced = StageClearBlur.resize(plane, fw, fh, StageClearBlur.Filter.BOX)
                        repeat(fw * fh) { expected[it * channels + c] = reduced[it] }
                    }
                    agreement(expected, result64, channels, "BOX ${width}x$height -> ${fw}x$fh radius=$radius")
                    // Check the entire expanded image, including every band seam and image edge.
                    for (filter in listOf(StageClearBlur.Filter.BILINEAR, StageClearBlur.Filter.BICUBIC)) {
                        val expanded64 = ExportGl(shaders).use { gl ->
                            gl.expand(result64, fw, fh, channels, width, height, filter, 64) }
                        val expanded256 = ExportGl(shaders).use { gl ->
                            gl.expand(result256, fw, fh, channels, width, height, filter, 256) }
                        assertTrue("expanded band partition changes", expanded64.contentEquals(expanded256))
                        val expandedReference = FloatArray(expanded64.size)
                        repeat(if (channels == 1) 1 else 3) { c ->
                            // Isolate interpolation from reduction; both have their own Kotlin check.
                            val plane = FloatPlane(fw, fh, FloatArray(fw * fh) { result64[it * channels + c] })
                            val expanded = StageClearBlur.resize(plane, width, height, filter)
                            repeat(width * height) { expandedReference[it * channels + c] = expanded[it] }
                        }
                        agreement(expandedReference, expanded64, channels, "$filter ${width}x$height", if (filter == StageClearBlur.Filter.BICUBIC && channels == 1) 1e-4 else 1e-5)
                    }
                }
            }
        }
    }

    private fun agreement(expected: FloatArray, actual: FloatArray, channels: Int, label: String, tolerance: Double = 1e-5) {
        var worst = 0.0
        var worstIndex = 0
        var failures = 0
        for (i in expected.indices) {
            if (channels == 4 && i % 4 == 3) continue
            // #279 existing blur/resample tolerance, unchanged.
            val ratio = abs(actual[i].toDouble() - expected[i]) / tolerance
            if (!actual[i].isFinite() || ratio > 1.0) failures++
            if (ratio > worst) { worst = ratio; worstIndex = i }
        }
        println("GPU_EXPORT_BANDS $label channels=$channels failures=$failures worstRatio=$worst " +
            "index=$worstIndex kotlin=${expected[worstIndex]} gpu=${actual[worstIndex]}")
        assertEquals("$label exceeds fixed resample tolerance", 0, failures)
    }
}

/** Test-only GL plumbing. Every numerical pass is an unmodified production shader. */
private class ExportGl(private val shaders: MesaShaders) : AutoCloseable {
    private data class Texture(val id: Int, val width: Int, val height: Int, val channels: Int, val integer: Boolean)
    private val textures = mutableSetOf<Int>()
    private val programs = mutableMapOf<String, Int>()
    private val framebuffer = glGenFramebuffers()
    private val vao = glGenVertexArrays()

    private fun texture(width: Int, height: Int, channels: Int, values: FloatArray? = null): Texture {
        val id = glGenTextures(); textures += id
        glBindTexture(GL_TEXTURE_2D, id)
        glTexStorage2D(GL_TEXTURE_2D, 1, if (channels == 1) GL_R32F else GL_RGBA32F, width, height)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        if (values != null) {
            val data = memAllocFloat(values.size)
            try {
                data.put(values).flip()
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height,
                    if (channels == 1) GL_RED else GL_RGBA, GL_FLOAT, data)
            } finally { memFree(data) }
        }
        return Texture(id, width, height, channels, false)
    }

    private fun weights(input: Int, output: Int, filter: StageClearBlur.Filter): Pair<Texture, Texture> {
        val samples = StageClearBlur.samples(input, output, filter)
        val taps = samples.maxOf { it.weights.size }
        val values = FloatArray(taps * output)
        samples.forEachIndexed { y, row -> row.weights.forEachIndexed { x, weight -> values[y * taps + x] = weight.toFloat() } }
        val weights = texture(taps, output, 1, values)
        val id = glGenTextures(); textures += id
        glBindTexture(GL_TEXTURE_2D, id)
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32I, 1, output)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        val starts = memAllocInt(output)
        try {
            samples.forEach { starts.put(it.first) }; starts.flip()
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, output, GL_RED_INTEGER, GL_INT, starts)
        } finally { memFree(starts) }
        return weights to Texture(id, 1, output, 1, true)
    }

    private fun program(path: String) = programs.getOrPut(path) { shaders.compileAsset(path) }.also(::glUseProgram)
    private fun bind(program: Int, inputs: List<Pair<String, Texture>>) {
        inputs.forEachIndexed { unit, (name, texture) ->
            glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, texture.id)
            val location = glGetUniformLocation(program, name); check(location >= 0) { name }
            glUniform1i(location, unit)
        }
    }
    private fun int(program: Int, name: String, value: Int) = glUniform1i(glGetUniformLocation(program, name), value)
    private fun delete(texture: Texture) { if (textures.remove(texture.id)) glDeleteTextures(texture.id) }
    private fun read(texture: Texture): FloatArray {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture.id, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
        // ES guarantees float RGBA readback for float render targets, including R32F.
        val buffer = memAllocFloat(texture.width * texture.height * 4)
        try {
            glReadPixels(0, 0, texture.width, texture.height, GL_RGBA, GL_FLOAT, buffer)
            checkGl("export read")
            return FloatArray(texture.width * texture.height * texture.channels) {
                buffer[it / texture.channels * 4 + it % texture.channels] }
        } finally { memFree(buffer) }
    }
    fun reduce(input: FloatArray, width: Int, height: Int, channels: Int, fw: Int, fh: Int, rows: Int): FloatArray {
        val field = texture(fw, fh, channels, FloatArray(fw * fh * channels))
        val x = weights(width, fw, StageClearBlur.Filter.BOX)
        val y = weights(height, fh, StageClearBlur.Filter.BOX)
        for (origin in 0 until height step rows) {
            val count = minOf(rows, height - origin)
            val band = texture(width, count, channels, input.copyOfRange(origin * width * channels, (origin + count) * width * channels))
            val horizontal = texture(fw, count, channels)
            var p = program(if (channels == 1) "analysis/reduce_plane.comp" else "analysis/reduce_rgb.comp")
            bind(p, listOf("s_input" to band, "s_weights" to x.first, "s_first" to x.second))
            int(p, "u_horizontal", 1); glUniform2i(glGetUniformLocation(p, "u_outputSize"), fw, count)
            glBindImageTexture(0, horizontal.id, 0, false, 0, GL_WRITE_ONLY, if (channels == 1) GL_R32F else GL_RGBA32F)
            glDispatchCompute(count, 1, 1); glMemoryBarrier(GL_ALL_BARRIER_BITS)
            p = program(if (channels == 1) "export/accumulate_plane.comp" else "export/accumulate_rgb.comp")
            bind(p, listOf("s_band" to horizontal, "s_weights" to y.first, "s_first" to y.second))
            int(p, "u_originY", origin); int(p, "u_bandHeight", count)
            glUniform2i(glGetUniformLocation(p, "u_fieldSize"), fw, fh)
            glBindImageTexture(0, field.id, 0, false, 0, GL_READ_WRITE, if (channels == 1) GL_R32F else GL_RGBA32F)
            glDispatchCompute((fw + 7) / 8, (fh + 7) / 8, 1); glMemoryBarrier(GL_ALL_BARRIER_BITS)
            checkGl("band accumulate"); delete(horizontal); delete(band)
        }
        return read(field)
    }
    fun expand(values: FloatArray, fw: Int, fh: Int, channels: Int, width: Int, height: Int,
        filter: StageClearBlur.Filter, rows: Int): FloatArray {
        val field = texture(fw, fh, channels, values)
        val x = weights(fw, width, filter); val y = weights(fh, height, filter)
        val result = FloatArray(width * height * channels)
        for (origin in 0 until height step rows) {
            val count = minOf(rows, height - origin)
            val output = texture(width, count, channels)
            val p = program("export/upsample_band.frag")
            bind(p, listOf("s_field" to field, "s_xWeights" to x.first, "s_xFirst" to x.second,
                "s_yWeights" to y.first, "s_yFirst" to y.second))
            int(p, "u_originY", origin)
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output.id, 0)
            check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
            glBindVertexArray(vao); glViewport(0, 0, width, count); glDisable(GL_DITHER); glDisable(GL_BLEND)
            glDrawArrays(GL_TRIANGLES, 0, 3)
            read(output).copyInto(result, origin * width * channels); delete(output)
        }
        return result
    }
    override fun close() {
        programs.values.forEach(::glDeleteProgram)
        textures.forEach(::glDeleteTextures)
        glDeleteFramebuffers(framebuffer); glDeleteVertexArrays(vao)
    }
}