package dev.cinematic.p0.raw.original

import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** RAW4 interaction regression; golden and default paths stay unchanged. */
class OriginalRaw4CombinedTest {
    private class Source(override val width: Int, override val height: Int) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            repeat(height) { y ->
                originalCheckpoint(stop)
                consume(y, FloatArray(width * 3) { i -> ((i * 37L + y * 19) % 4096) / 2048f - .05f })
            }
        }
    }
    private fun bits(raster: OriginalRaster): IntArray {
        val out = IntArray(raster.width * raster.height * 3)
        raster.rows({ false }) { y, row -> row.indices.forEach { out[y * row.size + it] = row[it].toRawBits() } }
        return out
    }
    @Test fun allFourCandidatesTogetherKeepPixelsFaceCutAndExternalRoi() {
        for ((width, height) in listOf(48 to 32, 37 to 51, 1 to 17, 17 to 1)) {
            for (strength in listOf(0.0, .5, 1.0)) {
                val root = Files.createTempDirectory("raw4-bits").toFile()
                val results = mutableListOf<Pair<IntArray, Double>>()
                try {
                    for (on in listOf(false, true)) {
                        val work = OriginalRasterWorkspace(root.resolve("arm-$on"))
                        try {
                            val caller = Thread.currentThread()
                            var next = 0
                            val trace = mutableListOf<OriginalStage>()
                            val renderer = OriginalStageClear24(work,
                                OriginalStageTrace { stage, _, complete -> assertTrue(complete); trace += stage },
                                parallelOrtonWorkers = 4, parallelCharacterWorkers = 2, parallelFoundationWorkers = 2,
                                parallelGlowWorkers = if (on) 2 else 1, parallelToneWorkers = if (on) 2 else 1,
                                parallelMistWorkers = if (on) 2 else 1, fuseR7ColorRows = on)
                            val result = renderer.render(Source(width, height), .23, strength,
                                foundation = OriginalMask { y, row ->
                                    assertSame(caller, Thread.currentThread()); assertEquals(next++, y); row.fill(.2f)
                                }, faces = OriginalMask { _, row -> row.fill(1f) })
                            assertEquals(height, next)
                            assertEquals(on, OriginalStage.R7_COLOR_FUSED in trace)
                            assertEquals(!on, OriginalStage.R7_VIBRANCE in trace)
                            results += bits(result.raster) to result.faceCutFactor
                            assertFalse(work.hasPendingOwners)
                        } finally { work.closePending(); work.disposeConfirmed() }
                    }
                    assertArrayEquals(results[0].first, results[1].first)
                    assertEquals(results[0].second, results[1].second, 0.0)
                } finally { check(root.delete()) }
            }
        }
    }
    @Test fun combinedCancellationKeepsOwnerContract() {
        val root = Files.createTempDirectory("raw4-cancel").toFile()
        val work = OriginalRasterWorkspace(root.resolve("attempt"))
        val armed = AtomicBoolean()
        try {
            val renderer = OriginalStageClear24(work,
                OriginalStageTrace { stage, _, complete -> if (stage == OriginalStage.R7_FOUNDATION && complete) armed.set(true) },
                parallelOrtonWorkers = 4, parallelCharacterWorkers = 2, parallelFoundationWorkers = 2,
                parallelGlowWorkers = 2, parallelToneWorkers = 2, parallelMistWorkers = 2, fuseR7ColorRows = true)
            assertThrows(CancellationException::class.java) {
                renderer.render(Source(73, 41), .23, stop = {
                    armed.get() && Thread.currentThread().name == "original-r7-row"
                })
            }
            assertTrue(armed.get())
            assertFalse(work.hasPendingOwners)
        } finally { work.closePending(); work.disposeConfirmed(); check(root.delete()) }
    }
}