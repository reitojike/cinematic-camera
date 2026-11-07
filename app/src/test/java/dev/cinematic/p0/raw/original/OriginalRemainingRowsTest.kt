package dev.cinematic.p0.raw.original

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OriginalRemainingRowsTest {
    private class MemoryRaster(override val width: Int, override val height: Int) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            repeat(height) { y ->
                originalCheckpoint(stop)
                consume(y, FloatArray(width * 3) { x -> (((x * 37L + y * 19) % 4096) / 2048f) - .05f })
            }
        }
    }

    private fun bits(raster: OriginalRaster): IntArray = IntArray(raster.width * raster.height * 3).also { out ->
        raster.rows({ false }) { y, row -> row.indices.forEach { out[y * row.size + it] = row[it].toRawBits() } }
    }

    @Test fun remainingWorkersPreserveEveryStageAndFinalRasterBits() {
        for ((width, height) in listOf(48 to 32, 174 to 116, 1 to 17, 17 to 1)) {
            for (strength in listOf(0.0, .5, 1.0)) {
                var expected: Pair<Map<String, IntArray>, Double>? = null
                for ((toneWorkers, mistWorkers) in listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2)) {
                    val root = Files.createTempDirectory("original-remaining-bits").toFile()
                    val work = OriginalRasterWorkspace(root.resolve("attempt"))
                    val stages = linkedMapOf<String, IntArray>()
                    val lightRows = AtomicInteger()
                    val diffusionRows = AtomicInteger()
                    val caller = Thread.currentThread()
                    try {
                        val result = OriginalStageClear24(work, parallelToneWorkers = toneWorkers, parallelMistWorkers = mistWorkers,
                            remainingRowProbe = { phase, _ ->
                                if ((if (phase == "tone") toneWorkers else mistWorkers) == 1) assertSame(caller, Thread.currentThread())
                                else assertNotSame(caller, Thread.currentThread())
                                if (phase == "tone") lightRows.incrementAndGet() else {
                                    assertEquals("light rows must finish before diffusion", height, lightRows.get())
                                    diffusionRows.incrementAndGet()
                                }
                            }).render(MemoryRaster(width, height), .23, strength,
                            faces = OriginalMask { _, row -> row.fill(1f) },
                            stage = { name, raster -> stages["$name-${stages.size}"] = bits(raster) })
                        stages["final"] = bits(result.raster)
                        assertEquals(height, lightRows.get())
                        assertEquals(if (strength == 0.0) 0 else height * (if (result.faceCutFactor < 1.0) 2 else 1), diffusionRows.get())
                        val baseline = expected
                        if (baseline == null) expected = stages to result.faceCutFactor else {
                            assertEquals(baseline.first.keys, stages.keys)
                            stages.forEach { (name, actual) ->
                                assertArrayEquals("$width/$height/$strength/$toneWorkers/$mistWorkers/$name", baseline.first.getValue(name), actual)
                            }
                            assertEquals(baseline.second, result.faceCutFactor, 0.0)
                        }
                        assertFalse(work.hasPendingOwners)
                    } finally { work.closePending(); work.disposeConfirmed(); check(root.delete()) }
                }
            }
        }
    }

    @Test fun remainingCancellationAndFailureCloseWorkersBeforeDisposal() {
        for (phase in listOf("tone", "mist")) for (workers in listOf(2)) {
            for (failure in listOf(false, true)) {
                val root = Files.createTempDirectory("original-remaining-stop").toFile()
                val work = OriginalRasterWorkspace(root.resolve("attempt"))
                val armed = AtomicBoolean()
                val cancelled = AtomicBoolean()
                val checkpoints = AtomicInteger()
                val fault = IOException("synthetic remaining worker failure")
                val stop = {
                    if (armed.get() && Thread.currentThread().name == "original-r7-row" &&
                        checkpoints.incrementAndGet() == 5) {
                        if (failure) throw fault else cancelled.set(true)
                    }
                    cancelled.get()
                }
                var glowEmitted = false
                try {
                    val render = {
                        OriginalStageClear24(work, parallelToneWorkers = workers, parallelMistWorkers = workers,
                            remainingRowProbe = { current, _ -> if (current == phase) armed.set(true) })
                            .render(MemoryRaster(128, 32), .23, .5, stop = stop,
                                stage = { name, _ -> if (name == if (phase == "tone") "r7_tone" else "mist") glowEmitted = true })
                    }
                    if (failure) assertSame(fault, assertThrows(IOException::class.java) { render() })
                    else assertThrows(CancellationException::class.java) { render() }
                    assertTrue("$phase did not reach worker checkpoints", checkpoints.get() >= 5)
                    assertFalse(glowEmitted)
                    assertFalse("readers, writers and worker owners must be closed", work.hasPendingOwners)
                } finally { work.closePending(); work.disposeConfirmed(); check(root.delete()) }
            }
        }
    }
}
