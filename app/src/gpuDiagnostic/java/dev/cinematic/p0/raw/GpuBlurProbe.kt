package dev.cinematic.p0.raw

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** 診断専用。製品に接続せず、BOX縮小とBILINEAR復元を帯単位で比較する。 */
internal object GpuBlurProbe {
    data class Times(val elapsedNs: Long, val uploadNs: Long, val drawNs: Long,
                     val readbackNs: Long, val renderer: String, val largestPassBytes: Long)
    data class Frame(val width: Int, val height: Int, val rgba: FloatArray)

    /** source/consume は合成入力と比較専用。原寸の全画像バッファは持たない。 */
    fun run(width: Int, height: Int, radius: Double, band: Int = 64,
            source: (Int, Int) -> FloatArray, consume: (Int, Frame) -> Unit,
            cancelled: () -> Boolean = { false }): Times {
        require(width in 2..4096 && height in 2..4096 && band in 1..128)
        val started = System.nanoTime()
        val (smallW, smallH) = StageClearBlur.reducedSize(width, height, radius)
        // 横優先の既存経路だけ。縦優先になる特殊な寸法は別途検証する。
        require(height - smallH <= 2 * (width - smallW)) { "縦優先の縮小はこの診断の対象外です" }
        fun checkpoint() { if (cancelled()) throw CancellationException("GPU診断を取り消しました") }
        checkpoint()
        val observation = Session(cancelled).use { gpu ->
            val reducedHorizontal = FloatArray(Math.multiplyExact(Math.multiplyExact(smallW, height), 4))
            for (top in 0 until height step band) {
                checkpoint()
                val rows = minOf(band, height - top)
                val input = Frame(width, rows, source(top, rows))
                val reduced = gpu.resize(input, smallW, rows, true, StageClearBlur.Filter.BOX)
                reduced.rgba.copyInto(reducedHorizontal, top * smallW * 4)
            }
            checkpoint()
            val small = gpu.resize(Frame(smallW, height, reducedHorizontal), smallW, smallH,
                false, StageClearBlur.Filter.BOX)
            val wide = gpu.resize(small, width, smallH, true, StageClearBlur.Filter.BILINEAR)
            for (top in 0 until height step band) {
                checkpoint()
                val rows = minOf(band, height - top)
                val result = gpu.resize(wide, width, height, false, StageClearBlur.Filter.BILINEAR, top, rows)
                consume(top, result)
            }
            checkpoint()
            Times(0, gpu.uploadNs, gpu.drawNs,
                gpu.readbackNs, gpu.renderer, gpu.largestPassBytes)
        }
        return observation.copy(elapsedNs = System.nanoTime() - started)
    }

    private class Session(private val cancelled: () -> Boolean) : AutoCloseable {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private var context = EGL14.EGL_NO_CONTEXT
        private var surface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var transferBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        private var resultBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
        var uploadNs = 0L; private set
        var drawNs = 0L; private set
        var readbackNs = 0L; private set
        var largestPassBytes = 0L; private set
        var renderer = ""; private set
        init {
            check(EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT)
            try {
                check(display != EGL14.EGL_NO_DISPLAY)
                val version = IntArray(2)
                check(EGL14.eglInitialize(display, version, 0, version, 1))
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1); val count = IntArray(1)
                check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
                val config = checkNotNull(configs[0])
                context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                check(context != EGL14.EGL_NO_CONTEXT)
                surface = EGL14.eglCreatePbufferSurface(display, config,
                    intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                check(surface != EGL14.EGL_NO_SURFACE)
                check(EGL14.eglMakeCurrent(display, surface, surface, context))
                check(GLES30.glGetString(GLES30.GL_EXTENSIONS).orEmpty().contains("GL_EXT_color_buffer_float"))
                renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
                program = link()
                GLES30.glDisable(GLES30.GL_BLEND); GLES30.glDisable(GLES30.GL_DITHER)
            } catch (failure: Throwable) { close(); throw failure }
        }
        private fun checkpoint() { if (cancelled()) throw CancellationException("GPU診断を取り消しました") }
        fun resize(input: Frame, outputWidth: Int, outputHeight: Int, horizontal: Boolean,
                   filter: StageClearBlur.Filter, firstRow: Int = 0, rows: Int = outputHeight): Frame {
            checkpoint()
            require(input.rgba.size == input.width * input.height * 4)
            val samples = StageClearBlur.samples(if (horizontal) input.width else input.height,
                if (horizontal) outputWidth else outputHeight, filter)
            val relevant = if (horizontal) samples.toList() else samples.drop(firstRow).take(rows)
            val maxTaps = relevant.maxOf { it.weights.size }.coerceAtLeast(1)
            val coefficients = FloatArray(maxTaps * relevant.size * 4)
            relevant.forEachIndexed { index, sample ->
                sample.weights.forEachIndexed { tap, weight ->
                    val i = (index * maxTaps + tap) * 4
                    coefficients[i] = weight.toFloat(); coefficients[i + 1] = sample.first.toFloat()
                    coefficients[i + 2] = sample.weights.size.toFloat()
                }
            }
            val textures = IntArray(3); val framebuffer = IntArray(1)
            val result = FloatArray(outputWidth * rows * 4)
            val bytes = (input.rgba.size.toLong() + coefficients.size + result.size) * 4
            largestPassBytes = maxOf(largestPassBytes, bytes)
            try {
                GLES30.glGenTextures(3, textures, 0); GLES30.glGenFramebuffers(1, framebuffer, 0)
                val beforeUpload = System.nanoTime()
                upload(textures[0], 0, input.width, input.height, input.rgba)
                upload(textures[1], 1, maxTaps, relevant.size, coefficients)
                upload(textures[2], 2, outputWidth, rows, null)
                GLES30.glFinish(); uploadNs += System.nanoTime() - beforeUpload
                checkpoint()
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer[0])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                    GLES30.GL_TEXTURE_2D, textures[2], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE)
                GLES30.glUseProgram(program)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "source"), 0)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "weights"), 1)
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "horizontal"), if (horizontal) 1 else 0)
                GLES30.glViewport(0, 0, outputWidth, rows)
                val beforeDraw = System.nanoTime()
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3); GLES30.glFinish()
                drawNs += System.nanoTime() - beforeDraw
                checkpoint()
                if (resultBuffer.capacity() < result.size * 4) resultBuffer = ByteBuffer.allocateDirect(result.size * 4).order(ByteOrder.nativeOrder())
                resultBuffer.clear()
                val read = resultBuffer.asFloatBuffer().apply { limit(result.size) }
                val beforeRead = System.nanoTime()
                GLES30.glReadPixels(0, 0, outputWidth, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, read)
                read.rewind(); read.get(result)
                readbackNs += System.nanoTime() - beforeRead
                check(GLES30.glGetError() == GLES30.GL_NO_ERROR)
                check(result.all { it.isFinite() })
                checkpoint()
                return Frame(outputWidth, rows, result)
            } finally {
                GLES30.glDeleteFramebuffers(1, framebuffer, 0); GLES30.glDeleteTextures(3, textures, 0)
            }
        }
        private fun upload(texture: Int, unit: Int, width: Int, height: Int, data: FloatArray?) {
            val buffer = data?.let {
                if (transferBuffer.capacity() < it.size * 4) transferBuffer = ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.nativeOrder())
                transferBuffer.clear()
                transferBuffer.asFloatBuffer().apply { put(it); flip() }
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, width, height, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer)
        }
        private fun shader(type: Int, source: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source); GLES30.glCompileShader(id)
            val status = IntArray(1); GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) { val message = GLES30.glGetShaderInfoLog(id); GLES30.glDeleteShader(id); error(message) }
            return id
        }
        private fun link(): Int {
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
        override fun close() {
            if (context != EGL14.EGL_NO_CONTEXT && EGL14.eglGetCurrentContext() == context) {
                if (program != 0) GLES30.glDeleteProgram(program)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }
}