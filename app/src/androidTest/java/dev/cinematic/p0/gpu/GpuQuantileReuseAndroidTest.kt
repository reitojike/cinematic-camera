package dev.cinematic.p0.gpu

import android.opengl.EGL14
import android.opengl.GLES32
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.analysis.HistogramRanks
import dev.cinematic.p0.gpu.analysis.GpuAnalysis
import dev.cinematic.p0.gpu.analysis.GpuQuantileWorkspace
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.r7.R7Gpu
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpuQuantileReuseAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
    private fun gpu(block: (EglContexts) -> Unit) = GlThread().use { thread ->
        thread.call { EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use {
            GlCapabilities.inspect(it).requireSupported(); block(it)
        } }
    }
    private fun texture(egl: EglContexts, ledger: GpuMemoryLedger, values: FloatArray): GpuTexture =
        GpuTexture.create(egl, ledger, values.size, 1, GpuTexture.Format.R32F).also {
            it.upload(ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(values); flip() })
        }
    @Test fun changedHistogramShapesFailureAndReuseKeepExactSortedQuantiles() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        val workspace = GpuQuantileWorkspace(egl)
        workspace.use {
            val cases = listOf(floatArrayOf(1f), floatArrayOf(-0f, 0f),
                floatArrayOf(-100f, -10f, -1f, -.5f, 0f, 1f, 10f, 100f),
                FloatArray(4097) { -.25f }, FloatArray(1024) { ((it * 73) % 511 - 255).toFloat() })
            repeat(2) {
                for (values in cases) texture(egl, ledger, values).use { input ->
                    // Use the actual GPU input (including any driver subnormal handling) as oracle.
                    val sorted = GpuStageTap.readPlane(egl, input).copyValues().sortedArray()
                    val actual = GpuAnalysis.localToneQuantiles(input, input.width, 1, workspace)
                    for ((i, q) in listOf(.5, .92).withIndex()) {
                        val rank = (sorted.size - 1) * q
                        val lower = sorted[floor(rank).toInt()].toDouble()
                        val upper = sorted[ceil(rank).toInt()].toDouble()
                        val expected = lower + (upper - lower) * (rank - floor(rank))
                        assertEquals(expected.toRawBits(), actual[i].toRawBits())
                    }
                }
                for (bad in floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
                    texture(egl, ledger, floatArrayOf(0f, bad)).use { input ->
                        assertThrows(IllegalArgumentException::class.java) { GpuAnalysis.localToneQuantiles(input, 2, 1, workspace) }
                    }
                }
            }
            assertEquals(1, workspace.allocations)
            assertEquals(1024 * 1024 + 8192 + 64, workspace.retainedGlBytes)
        }
        assertEquals(0, workspace.retainedGlBytes)
        workspace.close()
        assertThrows(IllegalStateException::class.java) { workspace.clear(65536) }
        assertEquals(0L, ledger.allocatedBytes)
    }
    @Test fun gpuRankSelectionMatchesCpuAtTileEdgesWithFourRowsAndLargeCounts() = gpu { egl ->
        GpuQuantileWorkspace(egl).use { workspace ->
            val bins = HistogramRanks.BINS
            val counts = IntArray(4 * bins)
            val edges = intArrayOf(0, 127, 128, 255, 256, 32767, 65407, 65535)
            repeat(4) { row -> edges.forEachIndexed { index, bin -> counts[row * bins + bin] = index + row + 1 } }
            // Exercise uint arithmetic close to the maximum supported texture pixel count.
            counts[0] = Int.MAX_VALUE - counts.take(bins).sum() + counts[0]
            counts.fill(0, 3 * bins, 4 * bins)
            counts[3 * bins + 128] = 8
            counts[4 * bins - 1] = 1
            val totals = LongArray(4) { row -> (0 until bins).sumOf { counts[row * bins + it].toLong() } }
            val rows = intArrayOf(0, 3, 2, 0)
            val ranks = intArrayOf(Int.MAX_VALUE - 1, 0, (totals[2] / 2).toInt(), 1)
            val expected = LongArray(4) { totals[rows[it]] }
            repeat(2) {
                workspace.clear(counts.size)
                val upload = ByteBuffer.allocateDirect(counts.size * 4).order(ByteOrder.nativeOrder())
                    .asIntBuffer().apply { put(counts); flip() }
                GLES32.glBufferSubData(GLES32.GL_SHADER_STORAGE_BUFFER, 0, counts.size * 4, upload)
                egl.checkGl("upload synthetic histogram")
                val actual = workspace.select(rows, ranks, expected, 4)
                for (i in rows.indices) assertEquals(
                    HistogramRanks.select(counts, rows[i] * bins, intArrayOf(ranks[i]), expected[i]).single(), actual[i])
                // Select both ends of every occupied bin, including all tile boundaries and all four rows.
                val probes = buildList {
                    repeat(4) { row ->
                        var before = 0L
                        repeat(bins) { bin ->
                            val size = counts[row * bins + bin].toLong()
                            if (size > 0) {
                                add(row to before.toInt())
                                add(row to (before + size - 1).toInt())
                            }
                            before += size
                        }
                    }
                }
                for (chunk in probes.chunked(4)) {
                    val query = List(4) { chunk[minOf(it, chunk.lastIndex)] }
                    val queryRows = IntArray(4) { query[it].first }
                    val queryRanks = IntArray(4) { query[it].second }
                    val queryTotals = LongArray(4) { totals[queryRows[it]] }
                    val result = workspace.select(queryRows, queryRanks, queryTotals, 4)
                    for (i in query.indices) assertEquals(HistogramRanks.select(counts,
                        queryRows[i] * bins, intArrayOf(queryRanks[i]), queryTotals[i]).single(), result[i])
                }
                assertThrows(IllegalStateException::class.java) {
                    workspace.select(rows, ranks, expected.copyOf().apply { this[0]-- }, 4)
                }
                assertThrows(IllegalStateException::class.java) {
                    workspace.select(rows, ranks.copyOf().apply { this[1] = totals[3].toInt() }, expected, 4)
                }
                // Nonfinite high bins must fail even if they do not contain a selected rank.
                assertThrows(IllegalArgumentException::class.java) {
                    workspace.select(IntArray(4) { 3 }, intArrayOf(0, 1, 2, 3), LongArray(4) { totals[3] }, 4, finite = true)
                }
                assertEquals(actual, workspace.select(rows, ranks, expected, 4))
            }
        }
    }
    @Test fun uniformCacheIsProgramOwnedAndClosedProgramsCannotBeRevived() = gpu { egl ->
        val library = egl.shaders()
        val source = """#version 320 es
precision highp float;
uniform float u_value;
layout(location=0) out vec4 color;
void main() { color=vec4(u_value); }
"""
        repeat(3) {
            val program = library.fragmentSource("uniform-cache", source)
            val id = program.id
            assertEquals(0, program.cachedUniformCount)
            val location = program.uniformLocation("u_value")
            assertTrue(location >= 0)
            repeat(10) { assertEquals(location, library.uniformLocation(id, "u_value")) }
            assertEquals(1, program.cachedUniformCount)
            assertEquals(-1, program.uniformLocation("missing"))
            assertEquals(2, program.cachedUniformCount)
            program.close()
            assertThrows(IllegalStateException::class.java) { program.uniformLocation("u_value") }
            assertThrows(IllegalStateException::class.java) { library.uniformLocation(id, "u_value") }
        }
    }
    @Test fun contextDestructionDoesNotAllowOldWorkspaceOnNewContext() = GlThread().use { thread ->
        thread.call {
            val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
            val first = EglContexts(thread, assets)
            val old = GpuQuantileWorkspace(first)
            old.clear(65536)
            first.close() // Context destruction owns GL names; workspace still drops CPU references.
            old.close()
            assertEquals(0, old.retainedGlBytes)
            EglContexts(thread, assets).use { second ->
                assertThrows(IllegalStateException::class.java) { old.requireContext(second) }
                GpuQuantileWorkspace(second).use { fresh -> fresh.clear(65536); assertEquals(1, fresh.allocations) }
            }
        }
    }
    @Test fun failedCloseKeepsProgramWorkspaceAndR7OwnedUntilContextReturns() = gpu { egl ->
        val ledger = GpuMemoryLedger()
        val input = GpuTexture.create(egl, ledger, 32, 24, GpuTexture.Format.RGBA32F)
        input.upload(ByteBuffer.allocateDirect(32 * 24 * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(FloatArray(32 * 24 * 4) { if (it % 4 == 3) 1f else .25f }); flip() })
        val r7 = R7Gpu(egl, ledger)
        val workspace = GpuQuantileWorkspace(egl)
        val program = egl.shaders().computeSource("owned-uniform", """#version 320 es
layout(local_size_x=1) in;
void main() {}
""")
        val id = program.id
        try {
            r7.render(input, 0.0, 0.0)
            workspace.clear(65536)
            assertTrue(r7.retainedQuantileGlBytes > 0)
            assertEquals(-1, program.uniformLocation("missing"))
            val display = EGL14.eglGetCurrentDisplay()
            assertTrue(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
            try {
                assertThrows(IllegalStateException::class.java) { program.close() }
                assertThrows(IllegalStateException::class.java) { workspace.close() }
                assertThrows(IllegalStateException::class.java) { r7.close() }
                assertTrue(workspace.retainedGlBytes > 0)
                assertTrue(r7.retainedQuantileGlBytes > 0)
            } finally { egl.makeOffscreenCurrent() }
            assertEquals(id, program.id)
            assertEquals(-1, egl.shaders().uniformLocation(id, "missing"))
            program.close(); workspace.close(); r7.close()
            assertEquals(0, workspace.retainedGlBytes)
            assertEquals(0, r7.retainedQuantileGlBytes)
        } finally { program.close(); workspace.close(); r7.close(); input.close() }
        assertEquals(0L, ledger.allocatedBytes)
    }
}
