package dev.cinematic.p0.raw

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** 診断専用。製品に接続せず、BOX縮小とBILINEAR復元を帯単位で比較する。 */
internal object GpuBlurProbe {
    data class Times(val elapsedNs: Long, val uploadNs: Long, val drawNs: Long,
                     val readbackNs: Long, val renderer: String, val largestPassBytes: Long,
                     val detail: GpuWorkTiming.Snapshot? = null)
    data class Frame(val width: Int, val height: Int, val rgba: FloatArray)

    /** source/consume は合成入力と比較専用。原寸の全画像バッファは持たない。 */
    fun run(width: Int, height: Int, radius: Double, band: Int = 64,
            source: (Int, Int) -> FloatArray, consume: (Int, Frame) -> Unit,
            cancelled: () -> Boolean = { false }): Times {
        val started = System.nanoTime()
        val result = GpuDiagnosticSession(cancelled = cancelled).use { session ->
            run(session, width, height, radius, band, source, consume, cancelled)
        }
        return result.copy(elapsedNs = System.nanoTime() - started)
    }

    /** 準備済みsession。転送・同期・readback・CPU変換を含み、初期化/最終解放を除く。 */
    fun run(session: GpuDiagnosticSession, width: Int, height: Int, radius: Double, band: Int = 64,
            source: (Int, Int) -> FloatArray, consume: (Int, Frame) -> Unit,
            cancelled: () -> Boolean = { false },
            timing: GpuWorkTiming? = if (session.detailedTiming) GpuWorkTiming() else null,
            started: Long = System.nanoTime()): Times {
        require(width in 2..4096 && height in 2..4096 && band in 1..128)
        timing?.count(GpuWorkTiming.Count.CALLS)
        val (smallW, smallH) = StageClearBlur.reducedSize(width, height, radius)
        require(height - smallH <= 2 * (width - smallW)) { "縦優先の縮小はこの診断の対象外です" }
        fun checkpoint() { session.requireCurrent(); if (cancelled()) throw CancellationException("GPU診断を取り消しました") }
        checkpoint()
        val gpu = Passes(session, cancelled, timing)
        val reducedHorizontal = timing.allocate(smallW.toLong() * height * 16) {
            FloatArray(Math.multiplyExact(Math.multiplyExact(smallW, height), 4))
        }
        for (top in 0 until height step band) {
            checkpoint()
            val rows = minOf(band, height - top)
            // Callback owns synthetic input creation, including any allocations inside it.
            val input = timing.measure(GpuWorkTiming.Phase.CPU_PREPARE) { Frame(width, rows, source(top, rows)) }
            val reduced = gpu.resize(input, smallW, rows, true, StageClearBlur.Filter.BOX)
            timing.measure(GpuWorkTiming.Phase.CPU_UNPACK) { reduced.rgba.copyInto(reducedHorizontal, top * smallW * 4) }
        }
        checkpoint()
        val small = gpu.resize(Frame(smallW, height, reducedHorizontal), smallW, smallH,
            false, StageClearBlur.Filter.BOX)
        val wide = gpu.resize(small, width, smallH, true, StageClearBlur.Filter.BILINEAR)
        for (top in 0 until height step band) {
            checkpoint()
            val rows = minOf(band, height - top)
            val result = gpu.resize(wide, width, height, false, StageClearBlur.Filter.BILINEAR, top, rows)
            timing.measure(GpuWorkTiming.Phase.CPU_UNPACK) { consume(top, result) }
        }
        checkpoint()
        val elapsedNs = System.nanoTime() - started
        val detail = timing?.snapshot(elapsedNs)
        return Times(elapsedNs,
            detail?.intervals?.get(GpuWorkTiming.Phase.UPLOAD.ordinal) ?: gpu.uploadNs,
            detail?.intervals?.get(GpuWorkTiming.Phase.DRAW.ordinal) ?: gpu.drawNs,
            detail?.intervals?.get(GpuWorkTiming.Phase.READBACK.ordinal) ?: gpu.readbackNs,
            gpu.renderer, gpu.largestPassBytes, detail)
    }

    private class Passes(private val session: GpuDiagnosticSession, private val cancelled: () -> Boolean,
                         private val timing: GpuWorkTiming?) {
        private var transferBuffer = timing.allocate(0) { ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()) }
        private var resultBuffer = timing.allocate(0) { ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()) }
        var uploadNs = 0L; private set
        var drawNs = 0L; private set
        var readbackNs = 0L; private set
        var largestPassBytes = 0L; private set
        val renderer get() = session.renderer
        private fun checkpoint() { session.requireCurrent(); if (cancelled()) throw CancellationException("GPU診断を取り消しました") }
        fun resize(input: Frame, outputWidth: Int, outputHeight: Int, horizontal: Boolean,
                   filter: StageClearBlur.Filter, firstRow: Int = 0, rows: Int = outputHeight): Frame {
            checkpoint()
            require(input.rgba.size == input.width * input.height * 4)
            timing?.count(GpuWorkTiming.Count.PASSES)
            val relevant = timing.measure(GpuWorkTiming.Phase.CPU_PREPARE) {
                val samples = StageClearBlur.samples(if (horizontal) input.width else input.height,
                    if (horizontal) outputWidth else outputHeight, filter)
                if (horizontal) samples.toList() else samples.drop(firstRow).take(rows)
            }
            val maxTaps = relevant.maxOf { it.weights.size }.coerceAtLeast(1)
            val coefficients = timing.allocate(maxTaps.toLong() * relevant.size * 16) { FloatArray(maxTaps * relevant.size * 4) }
            timing.measure(GpuWorkTiming.Phase.CPU_PACK) {
                relevant.forEachIndexed { index, sample ->
                    sample.weights.forEachIndexed { tap, weight ->
                        val i = (index * maxTaps + tap) * 4
                        coefficients[i] = weight.toFloat(); coefficients[i + 1] = sample.first.toFloat()
                        coefficients[i + 2] = sample.weights.size.toFloat()
                    }
                }
            }
            val textures = timing.allocate(12) { IntArray(3) }
            val framebuffer = timing.allocate(4) { IntArray(1) }
            val result = timing.allocate(outputWidth.toLong() * rows * 16) { FloatArray(outputWidth * rows * 4) }
            val bytes = (input.rgba.size.toLong() + coefficients.size + result.size) * 4
            largestPassBytes = maxOf(largestPassBytes, bytes)
            try {
                timing.measure(GpuWorkTiming.Phase.GPU_ALLOCATE) {
                    GLES30.glGenTextures(3, textures, 0); GLES30.glGenFramebuffers(1, framebuffer, 0)
                }
                val beforeUpload = System.nanoTime()
                if (timing == null) {
                    upload(textures[0], 0, input.width, input.height, input.rgba)
                    upload(textures[1], 1, maxTaps, relevant.size, coefficients)
                    upload(textures[2], 2, outputWidth, rows, null)
                    GLES30.glFinish()
                } else {
                    timing.measure(GpuWorkTiming.Phase.GPU_ALLOCATE) {
                        upload(textures[0], 0, input.width, input.height, null)
                        upload(textures[1], 1, maxTaps, relevant.size, null)
                        upload(textures[2], 2, outputWidth, rows, null)
                        GLES30.glFinish()
                    }
                    transfer(textures[0], 0, input.width, input.height, input.rgba)
                    transfer(textures[1], 1, maxTaps, relevant.size, coefficients)
                    timing.count(GpuWorkTiming.Count.TEXTURE_ALLOCATIONS, 3)
                    timing.count(GpuWorkTiming.Count.TEXTURE_BYTES, bytes)
                    timing.count(GpuWorkTiming.Count.UPLOAD_BYTES, (input.rgba.size.toLong() + coefficients.size) * 4)
                }
                uploadNs += System.nanoTime() - beforeUpload
                checkpoint()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, textures[2], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
                GLES30.glUseProgram(session.blurProgram)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(session.blurProgram, "source"), 0)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(session.blurProgram, "weights"), 1)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(session.blurProgram, "horizontal"), if (horizontal) 1 else 0)
                GLES30.glViewport(0, 0, outputWidth, rows)
                val beforeDraw = System.nanoTime()
                timing.measure(GpuWorkTiming.Phase.DRAW) { GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3); GLES30.glFinish() }
                drawNs += System.nanoTime() - beforeDraw
                checkpoint()
                if (resultBuffer.capacity() < result.size * 4) {
                    resultBuffer = timing.allocate(result.size.toLong() * 4) {
                        ByteBuffer.allocateDirect(result.size * 4).order(ByteOrder.nativeOrder())
                    }
                }
                resultBuffer.clear()
                val read = resultBuffer.asFloatBuffer().apply { limit(result.size) }
                val beforeRead = System.nanoTime()
                timing.measure(GpuWorkTiming.Phase.READBACK) {
                    GLES30.glReadPixels(0, 0, outputWidth, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, read)
                }
                timing.measure(GpuWorkTiming.Phase.CPU_UNPACK) { read.rewind(); read.get(result) }
                readbackNs += System.nanoTime() - beforeRead
                timing?.count(GpuWorkTiming.Count.READBACK_BYTES, result.size.toLong() * 4)
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                timing.measure(GpuWorkTiming.Phase.CPU_VALIDATE) { check(result.all { it.isFinite() }) }
                checkpoint()
                return Frame(outputWidth, rows, result)
            } finally {
                timing.measure(GpuWorkTiming.Phase.RELEASE) {
                    GLES30.glDeleteFramebuffers(1, framebuffer, 0); GLES30.glDeleteTextures(3, textures, 0)
                }
            }
        }
        private fun buffer(data: FloatArray): java.nio.FloatBuffer {
            if (transferBuffer.capacity() < data.size * 4) {
                transferBuffer = timing.allocate(data.size.toLong() * 4) {
                    ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
                }
            }
            return timing.measure(GpuWorkTiming.Phase.CPU_PACK) {
                transferBuffer.clear()
                transferBuffer.asFloatBuffer().apply { put(data); flip() }
            }
        }
        private fun transfer(texture: Int, unit: Int, width: Int, height: Int, data: FloatArray) {
            val input = buffer(data)
            timing.measure(GpuWorkTiming.Phase.UPLOAD) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_FLOAT, input)
                // The same CPU transfer buffer is reused; finish before it can be rewritten.
                GLES30.glFinish()
            }
        }
        private fun upload(texture: Int, unit: Int, width: Int, height: Int, data: FloatArray?) {
            val input = data?.let { buffer(it) }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, width, height, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, input)
        }
    }
    private fun shader(type: Int, source: String): Int {
        val id = GLES30.glCreateShader(type)
        GLES30.glShaderSource(id, source); GLES30.glCompileShader(id)
        val status = IntArray(1); GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { val message = GLES30.glGetShaderInfoLog(id); GLES30.glDeleteShader(id); error(message) }
        return id
    }
    internal fun createProgram(): Int {
        val vertex = shader(GLES30.GL_VERTEX_SHADER, """#version 300 es
            void main(){vec2 p=vec2(float((gl_VertexID<<1)&2),float(gl_VertexID&2));gl_Position=vec4(p*2.0-1.0,0.0,1.0);}
        """)
        var fragment = 0; var result = 0
        try {
            fragment = shader(GLES30.GL_FRAGMENT_SHADER, """#version 300 es
                precision highp float; precision highp sampler2D;
                uniform sampler2D source; uniform sampler2D weights; uniform int horizontal;
                out vec4 result;
                void main(){
                  ivec2 p=ivec2(gl_FragCoord.xy); int index=horizontal==1?p.x:p.y;
                  vec4 meta=texelFetch(weights,ivec2(0,index),0); int first=int(meta.g); int count=int(meta.b);
                  vec4 sum=vec4(0.0);
                  for(int i=0;i<count;i++){
                    float w=texelFetch(weights,ivec2(i,index),0).r;
                    ivec2 q=horizontal==1?ivec2(first+i,p.y):ivec2(p.x,first+i);
                    sum+=texelFetch(source,q,0)*w;
                  }
                  result=sum;
                }
            """)
            result = GLES30.glCreateProgram(); GLES30.glAttachShader(result, vertex); GLES30.glAttachShader(result, fragment)
            GLES30.glLinkProgram(result)
            val status = IntArray(1); GLES30.glGetProgramiv(result, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { GLES30.glGetProgramInfoLog(result) }
            return result
        } catch (failure: Throwable) { if (result != 0) GLES30.glDeleteProgram(result); throw failure }
        finally { GLES30.glDeleteShader(vertex); if (fragment != 0) GLES30.glDeleteShader(fragment) }
    }
}
