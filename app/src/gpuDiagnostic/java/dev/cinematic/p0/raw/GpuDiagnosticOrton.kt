package dev.cinematic.p0.raw

import dev.cinematic.p0.gpu.constants.StageClearOrtonV1
import dev.cinematic.p0.gpu.constants.StageClearV1
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** Test-only: no product call site. Full-image CPU blur, bounded GPU composition. */
internal object GpuDiagnosticOrton {
    data class Observation(val output: LinearSrgbImage, val transferBytes: Long, val textureBytes: Long, val gpuWallNs: Long = 0, val cpuPrepareNs: Long = 0,
        val uploadNs: Long = 0, val drawNs: Long = 0, val readbackNs: Long = 0,
        val detail: GpuWorkTiming.Snapshot? = null)

    fun render(session: GpuDiagnosticSession, mist: StageClearMist.Result, skin: FloatPlane,
               bandHeight: Int = 64, stop: () -> Boolean = { false }): Observation {
        val image = mist.image
        require(bandHeight in 1..256)
        require(skin.width == image.width && skin.height == image.height)
        fun checkpoint() { session.requireCurrent(); if (stop()) throw CancellationException("GPU probe cancelled") }
        checkpoint()
        if (mist.lightAddingStrength == 0.0) return Observation(image, 0, 0)
        val preparationStarted = System.nanoTime()
        val bright = FloatArray(image.pixelCount * 3) {
            val inverse = 1f - StageClearColor.linearToUnit(image[it])
            1f - inverse * inverse
        }
        val soft = StageClearBlur.rgb(LinearSrgbImage.takeOwnership(image.width, image.height, bright), StageClearOrtonV1.RADIUS)
        val preparationNs = System.nanoTime() - preparationStarted
        val started = System.nanoTime()
        val result = prepared(session, image, soft, skin, mist.lightAddingStrength, bandHeight, stop)
        return result.copy(gpuWallNs = System.nanoTime() - started, cpuPrepareNs = preparationNs)
    }

    /** 12MPでは呼び出し側が小さい帯を生成し、全原寸配列を保持しない。 */
    fun prepared(session: GpuDiagnosticSession, image: LinearSrgbImage, soft: LinearSrgbImage,
                 skin: FloatPlane, amount: Double, bandHeight: Int = 64,
                 stop: () -> Boolean = { false }): Observation {
        val gpuStarted = System.nanoTime()
        val timing = if (session.detailedTiming) GpuWorkTiming() else null
        timing?.count(GpuWorkTiming.Count.CALLS)
        require(bandHeight in 1..256 && amount.isFinite() && amount in 0.0..1.0)
        require(soft.width == image.width && soft.height == image.height)
        require(skin.width == image.width && skin.height == image.height)
        fun checkpoint() { session.requireCurrent(); if (stop()) throw CancellationException("GPU診断を取り消しました") }
        checkpoint()
        val textures = timing.allocate(12) { IntArray(3) }
        val framebuffer = timing.allocate(4) { IntArray(1) }
        val program = session.ortonProgram
        var uploadNs=0L; var drawNs=0L; var readbackNs=0L
        val h = minOf(bandHeight, image.height)
        val byteCount = Math.multiplyExact(Math.multiplyExact(image.width, h), 16)
        val imageOutput: LinearSrgbImage
        try {
            timing.measure(GpuWorkTiming.Phase.GPU_ALLOCATE) {
                GLES30.glUseProgram(program)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "source"), 0)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "softField"), 1)
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "amount"), amount.toFloat())
                GLES30.glGenTextures(3, textures, 0)
                GLES30.glGenFramebuffers(1, framebuffer, 0)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
                GLES30.glDisable(GLES30.GL_DITHER)
                GLES30.glDisable(GLES30.GL_BLEND)
            }
            fun buffer() = timing.allocate(byteCount.toLong()) {
                ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder()).asFloatBuffer()
            }
            val source = buffer(); val field = buffer(); val readback = buffer()
            val output = timing.allocate(image.pixelCount.toLong() * 12) { FloatArray(image.pixelCount * 3) }
            var firstRow = 0
            while (firstRow < image.height) {
                checkpoint()
                val rows = minOf(h, image.height - firstRow)
                timing?.count(GpuWorkTiming.Count.PASSES)
                val beforeUpload = System.nanoTime()
                timing.measure(GpuWorkTiming.Phase.CPU_PACK) {
                    source.clear(); field.clear(); readback.clear()
                    repeat(image.width * rows) { local ->
                        val pixel = firstRow * image.width + local
                        repeat(3) { c -> source.put(image[pixel * 3 + c]); field.put(soft[pixel * 3 + c]) }
                        source.put(skin[pixel]); field.put(1f)
                    }
                    source.flip(); field.flip()
                }
                fun texture(index: Int, data: java.nio.Buffer?) {
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + index)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[index])
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, image.width, rows, 0,
                        GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
                }
                if (timing == null) {
                    repeat(3) { texture(it, when (it) { 0 -> source; 1 -> field; else -> null }) }
                    GLES30.glFinish()
                } else {
                    // Separate storage request from transfer. This is a different diagnostic workload.
                    timing.measure(GpuWorkTiming.Phase.GPU_ALLOCATE) {
                        repeat(3) { texture(it, null) }
                        GLES30.glFinish()
                    }
                    timing.measure(GpuWorkTiming.Phase.UPLOAD) {
                        repeat(2) { index ->
                            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + index)
                            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[index])
                            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, image.width, rows,
                                GLES30.GL_RGBA, GLES30.GL_FLOAT, if (index == 0) source else field)
                        }
                        GLES30.glFinish()
                    }
                    timing.count(GpuWorkTiming.Count.TEXTURE_ALLOCATIONS, 3)
                    timing.count(GpuWorkTiming.Count.TEXTURE_BYTES, image.width.toLong() * rows * 48)
                    timing.count(GpuWorkTiming.Count.UPLOAD_BYTES, image.width.toLong() * rows * 32)
                }
                uploadNs += System.nanoTime() - beforeUpload
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, textures[2], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
                checkpoint()
                GLES30.glViewport(0, 0, image.width, rows)
                val beforeDraw = System.nanoTime()
                timing.measure(GpuWorkTiming.Phase.DRAW) {
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                    GLES30.glFinish()
                }
                drawNs += System.nanoTime() - beforeDraw
                checkpoint()
                val beforeRead = System.nanoTime()
                timing.measure(GpuWorkTiming.Phase.READBACK) {
                    GLES30.glReadPixels(0, 0, image.width, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, readback)
                }
                timing?.count(GpuWorkTiming.Count.READBACK_BYTES, image.width.toLong() * rows * 16)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "GPU probe draw/readback failed" }
                checkpoint()
                timing.measure(GpuWorkTiming.Phase.CPU_UNPACK) {
                    readback.rewind()
                    repeat(image.width * rows) { local ->
                        val offset = (firstRow * image.width + local) * 3
                        repeat(3) { c -> output[offset + c] = readback.get().also { require(it.isFinite()) } }
                        readback.get()
                    }
                }
                readbackNs += System.nanoTime() - beforeRead
                firstRow += rows
            }
            imageOutput = timing.measure(GpuWorkTiming.Phase.CPU_VALIDATE) {
                LinearSrgbImage.takeOwnership(image.width, image.height, output)
            }
        } finally {
            timing.measure(GpuWorkTiming.Phase.RELEASE) {
                GLES30.glDeleteTextures(3, textures, 0)
                GLES30.glDeleteFramebuffers(1, framebuffer, 0)
            }
        }
        val elapsedNs = System.nanoTime() - gpuStarted
        val detail = timing?.snapshot(elapsedNs)
        return Observation(imageOutput, byteCount.toLong() * 3, byteCount.toLong() * 3, elapsedNs,
            uploadNs = detail?.intervals?.get(GpuWorkTiming.Phase.UPLOAD.ordinal) ?: uploadNs,
            drawNs = detail?.intervals?.get(GpuWorkTiming.Phase.DRAW.ordinal) ?: drawNs,
            readbackNs = detail?.intervals?.get(GpuWorkTiming.Phase.READBACK.ordinal) ?: readbackNs,
            detail = detail)
    }

    internal fun createProgram(): Int {
        val vertex = shader(GLES30.GL_VERTEX_SHADER, """#version 300 es
            void main(){ vec2 p=vec2(float((gl_VertexID << 1) & 2),float(gl_VertexID & 2)); gl_Position=vec4(p*2.0-1.0,0.0,1.0); }
        """)
        var fragment = 0
        var result = 0
        try {
            fragment = shader(GLES30.GL_FRAGMENT_SHADER, fragmentSource())
            result = GLES30.glCreateProgram()
            GLES30.glAttachShader(result, vertex); GLES30.glAttachShader(result, fragment)
            GLES30.glLinkProgram(result)
            val status = IntArray(1); GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(result) }
            return result
        } catch (error: Throwable) {
            if (result != 0) GLES30.glDeleteProgram(result)
            throw error
        } finally {
            GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
        }
    }
    private fun shader(type: Int, text: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, text); GLES30.glCompileShader(id)
        val status = IntArray(1); GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { val message = GLES30.glGetShaderInfoLog(id); GLES30.glDeleteShader(id); error(message) }
        return id
    }
    // Coefficients come from the same CPU parameter definitions; no second parameter table.
    private fun matrix(name: String, values: FloatArray) = "vec3 $name(vec3 v){ return vec3(" +
        (0..2).joinToString(",") { r -> "dot(v,vec3(" + (0..2).joinToString(",") { values[r * 3 + it].toString() } + "))" } + "); }"
    private fun fragmentSource() = """#version 300 es
        precision highp float;
        precision highp sampler2D;
        uniform sampler2D source;
        uniform sampler2D softField;
        uniform float amount;
        out vec4 result;
        ${matrix("rgbLms", StageClearV1.RGB_TO_LMS)}
        ${matrix("lmsLab", StageClearV1.LMS_TO_LAB)}
        ${matrix("labLms", StageClearV1.LAB_TO_LMS)}
        ${matrix("lmsRgb", StageClearV1.LMS_TO_RGB)}
        vec3 lab(vec3 v){ vec3 l=rgbLms(v); return lmsLab(sign(l)*pow(abs(l),vec3(1.0/3.0))); }
        vec3 rgb(vec3 v){ vec3 l=labLms(v); return lmsRgb(l*l*l); }
        float enc(float v){ v=clamp(v,0.0,1.0); return v<=0.0031308 ? 12.92*v : 1.055*pow(v,1.0/2.4)-0.055; }
        float dec(float v){ v=clamp(v,0.0,1.0); return v<=0.04045 ? v/12.92 : pow((v+0.055)/1.055,2.4); }
        void main(){
            ivec2 p=ivec2(gl_FragCoord.xy);
            vec4 original=texelFetch(source,p,0);
            vec3 e=vec3(enc(original.r),enc(original.g),enc(original.b));
            vec3 bright=1.0-(1.0-e)*(1.0-e);
            float y=dot(e,vec3(${StageClearV1.LUMA.joinToString(",")}));
            float fade=1.0-clamp((y-${StageClearOrtonV1.HIGHLIGHT_START})/${StageClearOrtonV1.HIGHLIGHT_SPAN},0.0,1.0);
            vec3 d=e+${StageClearOrtonV1.AMOUNT}*fade*(bright*clamp(texelFetch(softField,p,0).rgb,0.0,1.0)-e);
            vec3 candidate=vec3(dec(d.r),dec(d.g),dec(d.b));
            for(int c=0;c<3;c++){ if(original[c]>1.0) candidate[c]=max(original[c],candidate[c]); }
            vec3 before=lab(original.rgb), after=lab(candidate);
            float scale=(1.0-original.a)+original.a*min(1.0,length(before.yz)/max(length(after.yz),${StageClearOrtonV1.CHROMA_FLOOR}));
            after.yz*=scale;
            result=vec4(original.rgb+amount*(rgb(after)-original.rgb),1.0);
        }
    """
}
