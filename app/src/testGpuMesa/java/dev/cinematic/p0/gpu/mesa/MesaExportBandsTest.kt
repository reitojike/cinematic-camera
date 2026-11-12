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
    @Test fun fieldsHaveNo64Or256RowSeamsAndReportKotlinReference() = MesaContext().use {
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
                    val full = ExportGl(shaders).use { gl -> gl.fullResize(input, width, height, channels, fw, fh, StageClearBlur.Filter.BOX) }
                    sameGpu(full, result64, "BOX $width x $height channels=$channels radius=$radius")
                    val expected = FloatArray(result64.size)
                    repeat(if (channels == 1) 1 else 3) { c ->
                        val plane = FloatPlane(width, height, FloatArray(width * height) { input[it * channels + c] })
                        val reduced = StageClearBlur.resize(plane, fw, fh, StageClearBlur.Filter.BOX)
                        repeat(fw * fh) { expected[it * channels + c] = reduced[it] }
                    }
                    agreement(expected, result64, channels, "BOX ${width}x$height -> ${fw}x$fh radius=$radius", if (channels == 1) 1e-4 else 1e-5)
                    // Check the entire expanded image, including every band seam and image edge.
                    for (filter in listOf(StageClearBlur.Filter.BILINEAR, StageClearBlur.Filter.BICUBIC)) {
                        val expanded64 = ExportGl(shaders).use { gl ->
                            gl.expand(result64, fw, fh, channels, width, height, filter, 64) }
                        val expanded256 = ExportGl(shaders).use { gl ->
                            gl.expand(result256, fw, fh, channels, width, height, filter, 256) }
                        assertTrue("expanded band partition changes", expanded64.contentEquals(expanded256))
                        val fullExpanded = ExportGl(shaders).use { gl ->
                            gl.fullResize(full, fw, fh, channels, width, height, filter) }
                        sameGpu(fullExpanded, expanded64, "$filter $width x $height channels=$channels radius=$radius")
                        val expandedReference = FloatArray(expanded64.size)
                        repeat(if (channels == 1) 1 else 3) { c ->
                            // Compare the combined BOX + interpolation chain to unchanged Kotlin.
                            val plane = FloatPlane(fw, fh, FloatArray(fw * fh) { expected[it * channels + c] })
                            val expanded = StageClearBlur.resize(plane, width, height, filter)
                            repeat(width * height) { expandedReference[it * channels + c] = expanded[it] }
                        }
                        agreement(expandedReference, expanded64, channels, "$filter ${width}x$height", if (channels == 1) 1e-4 else 1e-5)
                    }
                }
            }
        }
    }

    @Test fun structuredFieldsPreserveFullFrameOrderAndReportErrorDistribution() = MesaContext().use {
        val shaders = MesaShaders()
        data class Case(val w: Int, val h: Int, val radius: Double, val channels: Int, val pattern: String)
        val cases = buildList {
            // Counterexamples that failed before matching the full-frame vertical-first order.
            for ((w, h) in listOf(257 to 4096, 1025 to 4000)) {
                for (channels in listOf(1, 4)) add(Case(w, h, .07, channels, "constant4"))
            }
            for (pattern in listOf("log12", "logFloor")) {
                add(Case(1600, 1200, .04, 1, pattern))
                add(Case(1600, 1200, .12, 1, pattern))
            }
            // Every look radius, with structure that random input averages away.
            for (radius in listOf(.006, .01, .012, .02, .025, .03, .06, .07)) {
                for (pattern in listOf("impulse", "rowStripe", "columnStripe"))
                    add(Case(257, 1025, radius, 4, pattern))
            }
        }
        for (case in cases) {
            val (w, h, radius, channels, pattern) = case
            val label = "$w x $h radius=$radius channels=$channels pattern=$pattern"
            val input = FloatArray(w * h * channels) { i ->
                val pixel = i / channels; val x = pixel % w; val y = pixel / w
                if (channels == 4 && i % 4 == 3) 1f else when (pattern) {
                    "constant4" -> 4f
                    "log12" -> -12f
                    "logFloor" -> -16.60964f
                    "rowStripe" -> if (y % 2 == 0) 4f else 0f
                    "columnStripe" -> if (x % 2 == 0) 4f else 0f
                    else -> if (x == w / 2 && y == 256) 4f else 0f
                }
            }
            val (fw, fh) = StageClearBlur.reducedSize(w, h, radius)
            val band64 = ExportGl(shaders).use { it.reduce(input, w, h, channels, fw, fh, 64) }
            val band256 = ExportGl(shaders).use { it.reduce(input, w, h, channels, fw, fh, 256) }
            sameGpu(band64, band256, "partition $label")
            val full = ExportGl(shaders).use { it.fullResize(input, w, h, channels, fw, fh, StageClearBlur.Filter.BOX) }
            sameGpu(full, band64, "BOX $label")
            val kotlin = FloatArray(fw * fh * channels)
            repeat(if (channels == 1) 1 else 3) { c ->
                val plane = FloatPlane(w, h, FloatArray(w * h) { input[it * channels + c] })
                val reduced = StageClearBlur.resize(plane, fw, fh, StageClearBlur.Filter.BOX)
                repeat(fw * fh) { kotlin[it * channels + c] = reduced[it] }
            }
            val log = pattern.startsWith("log")
            val tolerance = if (log) 1e-4 else 1e-5
            if (pattern == "constant4" || log) {
                reportWeights(w, fw, "x $label")
                reportWeights(h, fh, "y $label")
            }
            agreement(kotlin, band64, channels, "BOX $label", tolerance)
            val filter = if (log) StageClearBlur.Filter.BICUBIC else StageClearBlur.Filter.BILINEAR
            val expanded64 = ExportGl(shaders).use { it.expand(band64, fw, fh, channels, w, h, filter, 64) }
            val expanded256 = ExportGl(shaders).use { it.expand(band256, fw, fh, channels, w, h, filter, 256) }
            sameGpu(expanded64, expanded256, "$filter partition $label")
            val fullExpanded = ExportGl(shaders).use { it.fullResize(full, fw, fh, channels, w, h, filter) }
            sameGpu(fullExpanded, expanded64, "$filter $label")
            val kotlinExpanded = FloatArray(w * h * channels)
            repeat(if (channels == 1) 1 else 3) { c ->
                val plane = FloatPlane(fw, fh, FloatArray(fw * fh) { kotlin[it * channels + c] })
                val result = StageClearBlur.resize(plane, w, h, filter)
                repeat(w * h) { kotlinExpanded[it * channels + c] = result[it] }
            }
            reportDistribution(kotlinExpanded, expanded64, w, h, channels, label)
            agreement(kotlinExpanded, expanded64, channels, "$filter chain $label", tolerance)
        }
    }

    private fun reportWeights(input: Int, output: Int, label: String) {
        val samples = StageClearBlur.samples(input, output, StageClearBlur.Filter.BOX)
        val double = samples.map { it.weights.sum() }
        val roundedCoefficients = samples.map { it.weights.sumOf { weight -> weight.toFloat().toDouble() } }
        val floatAdds = samples.map { it.weights.fold(0f) { sum, weight -> sum + weight.toFloat() } }
        println("GPU_EXPORT_WEIGHT_SUM $label double=${double.min()}..${double.max()} " +
            "floatWeightsDoubleAdds=${roundedCoefficients.min()}..${roundedCoefficients.max()} " +
            "floatWeightsFloatAdds=${floatAdds.min()}..${floatAdds.max()}")
    }

    private fun reportDistribution(expected: FloatArray, actual: FloatArray, width: Int, height: Int,
        channels: Int, label: String) {
        // Rows either side of every seam; 256 seams are a subset of 64 seams.
        for (partition in listOf(64, 256)) {
            val count = LongArray(2); val nonzero = LongArray(2); val sum = DoubleArray(2); val maximum = DoubleArray(2)
            for (i in expected.indices) {
                if (channels == 4 && i % 4 == 3) continue
                val y = i / channels / width
                val seam = (y > 0 && y % partition == 0) || (y + 1 < height && (y + 1) % partition == 0)
                val group = if (seam) 0 else 1
                val error = abs(actual[i].toDouble() - expected[i])
                count[group]++; if (error != 0.0) nonzero[group]++
                sum[group] += error; maximum[group] = maxOf(maximum[group], error)
            }
            println("GPU_EXPORT_ERROR_DISTRIBUTION $label partition=$partition " +
                "seamMax=${maximum[0]} seamMean=${sum[0] / count[0]} seamNonzero=${nonzero[0]}/${count[0]} " +
                "interiorMax=${maximum[1]} interiorMean=${sum[1] / count[1]} interiorNonzero=${nonzero[1]}/${count[1]}")
        }
    }

    private fun sameGpu(expected: FloatArray, actual: FloatArray, label: String) {
        require(expected.isNotEmpty() && expected.size == actual.size) { "$label: GPU shapes differ" }
        require(expected.all { it.isFinite() } && actual.all { it.isFinite() }) { "$label: non-finite GPU output" }
        val mismatches = expected.indices.count { expected[it].toRawBits() != actual[it].toRawBits() }
        println("GPU_EXPORT_FULL_FRAME $label bitMismatches=$mismatches")
        assertEquals("$label: band GPU differs from #284 full-frame GPU", 0, mismatches)
    }

    private fun agreement(expected: FloatArray, actual: FloatArray, channels: Int, label: String, tolerance: Double = 1e-5) {
        require(expected.isNotEmpty() && expected.size == actual.size) { "$label: channel counts differ or are empty" }
        require(expected.all { it.isFinite() } && actual.all { it.isFinite() }) { "$label: non-finite reference/readback" }
        var worst = 0.0
        var worstIndex = 0
        var outsideReference = 0
        for (i in expected.indices) {
            if (channels == 4 && i % 4 == 3) continue
            // Historical #279 bounds are reference scales, never stop conditions.
            val ratio = abs(actual[i].toDouble() - expected[i]) / tolerance
            if (ratio > 1.0) outsideReference++
            if (ratio > worst) { worst = ratio; worstIndex = i }
        }
        println("CPU_REFERENCE_ONLY GPU_EXPORT_BANDS $label channels=$channels outsideHistoricalReference=$outsideReference worstRatio=$worst " +
            "index=$worstIndex kotlin=${expected[worstIndex]} gpu=${actual[worstIndex]}")
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
    /** Same pass order and production shaders as GpuResample (#284 full-frame path). */
    fun fullResize(input: FloatArray, width: Int, height: Int, channels: Int, outWidth: Int, outHeight: Int,
        filter: StageClearBlur.Filter): FloatArray {
        val source = texture(width, height, channels, input)
        val verticalFirst = height.toLong() - outHeight > 0 && height.toLong() - outHeight > 2 * (width.toLong() - outWidth)
        val first = if (verticalFirst) axis(source, outHeight, false, filter) else axis(source, outWidth, true, filter)
        val last = if (verticalFirst) axis(first, outWidth, true, filter) else axis(first, outHeight, false, filter)
        return read(last)
    }

    private fun axis(input: Texture, size: Int, horizontal: Boolean, filter: StageClearBlur.Filter): Texture {
        val oldSize = if (horizontal) input.width else input.height
        if (size == oldSize) return input
        val table = weights(oldSize, size, filter)
        val output = texture(if (horizontal) size else input.width, if (horizontal) input.height else size, input.channels)
        val p = program(if (filter != StageClearBlur.Filter.BOX) "common/upsample_axis.frag"
            else if (input.channels == 1) "analysis/reduce_plane.comp" else "analysis/reduce_rgb.comp")
        bind(p, listOf("s_input" to input, "s_weights" to table.first, "s_first" to table.second))
        int(p, "u_horizontal", if (horizontal) 1 else 0)
        if (filter == StageClearBlur.Filter.BOX) {
            glUniform2i(glGetUniformLocation(p, "u_outputSize"), output.width, output.height)
            glBindImageTexture(0, output.id, 0, false, 0, GL_WRITE_ONLY, if (input.channels == 1) GL_R32F else GL_RGBA32F)
            glDispatchCompute(if (horizontal) output.height else output.width, 1, 1)
            glMemoryBarrier(GL_ALL_BARRIER_BITS)
        } else draw(output)
        checkGl("full-frame axis")
        delete(table.first); delete(table.second)
        return output
    }

    private fun draw(output: Texture) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, output.id, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE)
        glBindVertexArray(vao); glViewport(0, 0, output.width, output.height); glDisable(GL_DITHER); glDisable(GL_BLEND)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    fun reduce(input: FloatArray, width: Int, height: Int, channels: Int, fw: Int, fh: Int, rows: Int): FloatArray {
        val verticalFirst = height.toLong() - fh > 0 && height.toLong() - fh > 2 * (width.toLong() - fw)
        val accumulationWidth = if (verticalFirst) width else fw
        var field = texture(accumulationWidth, fh, channels, FloatArray(accumulationWidth * fh * channels))
        var scratch = texture(accumulationWidth, fh, channels)
        val y = weights(height, fh, StageClearBlur.Filter.BOX)
        for (origin in 0 until height step rows) {
            val count = minOf(rows, height - origin)
            val band = texture(width, count, channels, input.copyOfRange(origin * width * channels, (origin + count) * width * channels))
            val horizontal = if (verticalFirst) band else axis(band, fw, true, StageClearBlur.Filter.BOX)
            val p = program(if (channels == 1) "export/accumulate_plane.comp" else "export/accumulate_rgb.comp")
            bind(p, listOf("s_band" to horizontal, "s_weights" to y.first, "s_first" to y.second, "s_previous" to field))
            int(p, "u_originY", origin); int(p, "u_bandHeight", count)
            glUniform2i(glGetUniformLocation(p, "u_fieldSize"), accumulationWidth, fh)
            glBindImageTexture(0, scratch.id, 0, false, 0, GL_WRITE_ONLY, if (channels == 1) GL_R32F else GL_RGBA32F)
            glDispatchCompute((accumulationWidth + 7) / 8, (fh + 7) / 8, 1); glMemoryBarrier(GL_ALL_BARRIER_BITS)
            val previous = field; field = scratch; scratch = previous
            checkGl("band accumulate"); if (horizontal !== band) delete(horizontal); delete(band)
        }
        return read(if (verticalFirst) axis(field, fw, true, StageClearBlur.Filter.BOX) else field)
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
            draw(output)
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
