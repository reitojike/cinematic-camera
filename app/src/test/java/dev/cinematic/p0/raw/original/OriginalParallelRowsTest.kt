package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7Base
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OriginalParallelRowsTest {
    private class ReusedRows(override val width: Int, override val height: Int) : OriginalRaster {
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val borrowed = FloatArray(width * 3)
            repeat(height) { y ->
                originalCheckpoint(stop)
                borrowed.indices.forEach { borrowed[it] = (y * borrowed.size + it) / 4096f }
                consume(y, borrowed)
            }
        }
    }

    private fun pixels(raster: OriginalRasterFile): FloatArray =
        FloatArray(raster.width * raster.height * 3).also { all ->
            raster.rows({ false }) { y, row -> row.copyInto(all, y * row.size) }
        }

    @Test fun borrowedRowsRemainOrderedAndOnlyTwoWorkersRun() {
        val root = Files.createTempDirectory("original-parallel-rows").toFile()
        val serial = OriginalRasterWorkspace(root.resolve("serial"))
        val parallel = OriginalRasterWorkspace(root.resolve("parallel"))
        try {
            val input = ReusedRows(37, 47)
            val expected = serial.map(input, { false }) { y, row ->
                FloatArray(row.size) { i -> row[i] * (1 + y / 100f) }
            }
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val actual = parallel.mapParallelRows(input, { false }) { y, row ->
                val now = active.incrementAndGet()
                peak.accumulateAndGet(now, ::maxOf)
                try {
                    Thread.sleep(1)
                    FloatArray(row.size) { i -> row[i] * (1 + y / 100f) }
                } finally { active.decrementAndGet() }
            }
            assertArrayEquals(pixels(expected), pixels(actual), 0f)
            assertTrue("workers=$peak", peak.get() in 1..2)
            assertEquals(0, active.get())
            assertFalse(serial.hasPendingOwners)
            assertFalse(parallel.hasPendingOwners)
        } finally {
            serial.disposeConfirmed()
            parallel.disposeConfirmed()
            assertTrue(root.delete())
        }
    }

    @Test fun allFourR7StagesMatchSerialFloatBits() {
        val sampleWidth = 73
        val sampleHeight = 41
        val input = object : OriginalRaster {
            override val width = sampleWidth
            override val height = sampleHeight
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                val borrowed = FloatArray(width * 3)
                repeat(height) { y ->
                    originalCheckpoint(stop)
                    borrowed.indices.forEach { i ->
                        borrowed[i] = (((y * borrowed.size + i) * 37L % 7000L) / 5000f) - .08f
                    }
                    consume(y, borrowed)
                }
            }
        }
        val stages: List<(LinearSrgbImage) -> LinearSrgbImage> = listOf(
            R7Base::profile, R7Base::vibrance, R7Base::crisp, R7Base::skin,
        )
        for ((index, stage) in stages.withIndex()) {
            val root = Files.createTempDirectory("original-r7-bits-$index").toFile()
            val serial = OriginalRasterWorkspace(root.resolve("serial"))
            val parallel = OriginalRasterWorkspace(root.resolve("parallel"))
            try {
                val transform: (Int, FloatArray) -> FloatArray = { _, row ->
                    stage(LinearSrgbImage.takeOwnership(sampleWidth, 1, row)).copyPixels()
                }
                val expected = pixels(serial.map(input, { false }, transform))
                val actual = pixels(parallel.mapParallelRows(input, { false }, transform))
                assertEquals(expected.size, actual.size)
                expected.indices.forEach { i ->
                    assertEquals("stage $index pixel $i", expected[i].toRawBits(), actual[i].toRawBits())
                }
            } finally {
                serial.disposeConfirmed()
                parallel.disposeConfirmed()
                assertTrue(root.delete())
            }
        }
    }

    @Test fun transformFailureAndCancellationKeepOutputPrivateAndCloseWorkers() {
        for (mode in listOf("transform", "cancel", "output")) {
            val root = Files.createTempDirectory("original-parallel-failure").toFile()
            val work = OriginalRasterWorkspace(root.resolve("attempt"))
            try {
                val input = ReusedRows(27, 15)
                val checks = AtomicInteger()
                val active = AtomicInteger()
                val stop = { mode == "cancel" && checks.incrementAndGet() > 6 }
                if (mode == "cancel") {
                    assertThrows(CancellationException::class.java) {
                        work.mapParallelRows(input, stop) { _, row -> row.copyOf() }
                    }
                } else {
                    val expected = if (mode == "transform") IllegalStateException::class.java else IllegalArgumentException::class.java
                    val error = assertThrows(expected) {
                        work.mapParallelRows(input, stop) { y, row ->
                            active.incrementAndGet()
                            try {
                                if (mode == "transform" && y == 2) throw IllegalStateException("synthetic row fault")
                                if (mode == "output" && y >= 3) Thread.sleep(10)
                                row.copyOf().also { if (mode == "output" && y == 2) it[0] = Float.NaN }
                            } finally { active.decrementAndGet() }
                        }
                    }
                    if (mode == "transform") assertEquals("synthetic row fault", error.message)
                }
                assertFalse(work.hasPendingOwners)
                assertEquals(0, active.get())
                assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "original-r7-row" })
            } finally {
                work.disposeConfirmed()
                assertTrue(root.delete())
            }
        }
    }

    @Test fun originalSizeAndWorkspaceBudgetAreStillEnforcedBeforeWorkersStart() {
        val root = Files.createTempDirectory("original-parallel-budget").toFile()
        val work = OriginalRasterWorkspace(root.resolve("attempt"), 100)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                work.mapParallelRows(ReusedRows(6001, 4000), { false }) { _, row -> row.copyOf() }
            }
            assertThrows(IOException::class.java) {
                work.mapParallelRows(ReusedRows(12, 8), { false }) { _, row -> row.copyOf() }
            }
            assertEquals(0, work.reservedBytes)
            assertFalse(work.hasPendingOwners)
        } finally {
            work.disposeConfirmed()
            assertTrue(root.delete())
        }
    }
}
