package dev.cinematic.p0.raw.original

import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.R7Base
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class OriginalParallelRowsTest {
    private fun assertOwnWorkersStopped(workers: Set<Thread>) {
        // awaitTermination acknowledges executor state before Java Thread.run has necessarily
        // returned. Join only this invocation's workers with a total five-second test bound.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        workers.forEach { worker ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("Timed out waiting for owned row worker ${describe(worker)}")
            worker.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1)
            if (worker.isAlive) fail("Owned row worker remains alive: ${describe(worker)}")
        }
    }

    private fun describe(worker: Thread) =
        "id=${worker.id} name=${worker.name} state=${worker.state} stack=${worker.stackTrace.take(8).joinToString()}"

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
                val workers = ConcurrentHashMap.newKeySet<Thread>()
                val stop = { mode == "cancel" && checks.incrementAndGet() > 6 }
                if (mode == "cancel") {
                    assertThrows(CancellationException::class.java) {
                        work.mapParallelRows(input, stop) { _, row ->
                            workers.add(Thread.currentThread())
                            row.copyOf()
                        }
                    }
                } else {
                    val expected = if (mode == "transform") IllegalStateException::class.java else IllegalArgumentException::class.java
                    val error = assertThrows(expected) {
                        work.mapParallelRows(input, stop) { y, row ->
                            workers.add(Thread.currentThread())
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
                assertOwnWorkersStopped(workers)
            } finally {
                work.disposeConfirmed()
                assertTrue(root.delete())
            }
        }
    }

    @Test fun oneAttemptCanCloseWhileAnotherOwnsAnAliveRowWorker() {
        val root = Files.createTempDirectory("original-parallel-peer").toFile()
        val own = OriginalRasterWorkspace(root.resolve("own"))
        val peer = OriginalRasterWorkspace(root.resolve("peer"))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val peerWorker = AtomicReference<Thread>()
        val ownWorkers = ConcurrentHashMap.newKeySet<Thread>()
        val caller = Executors.newSingleThreadExecutor()
        try {
            val peerResult = caller.submit {
                peer.mapParallelRows(ReusedRows(27, 15), { false }) { y, row ->
                    if (y == 0) {
                        peerWorker.set(Thread.currentThread()); started.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                    row.copyOf()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            own.mapParallelRows(ReusedRows(27, 15), { false }) { _, row ->
                ownWorkers.add(Thread.currentThread()); row.copyOf()
            }
            assertTrue(ownWorkers.isNotEmpty())
            assertOwnWorkersStopped(ownWorkers)
            assertTrue("the separate attempt legitimately still owns a worker", peerWorker.get().isAlive)
            release.countDown()
            peerResult.get(5, TimeUnit.SECONDS)
            assertOwnWorkersStopped(setOf(peerWorker.get()))
        } finally {
            release.countDown()
            caller.shutdownNow()
            assertTrue(caller.awaitTermination(10, TimeUnit.SECONDS))
            own.disposeConfirmed(); peer.disposeConfirmed()
            assertTrue(root.delete())
        }
    }

    @Test fun executorTerminationCanPrecedeJavaThreadExit() {
        val afterExecutor = CountDownLatch(1)
        val releaseThread = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { task ->
            object : Thread(task, "executor-termination-witness") {
                override fun run() {
                    super.run()
                    afterExecutor.countDown()
                    releaseThread.await(5, TimeUnit.SECONDS)
                }
            }.also(worker::set)
        }
        try {
            executor.submit {}.get(5, TimeUnit.SECONDS)
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(afterExecutor.await(5, TimeUnit.SECONDS))
            assertTrue("TERMINATED does not join the Java worker", worker.get().isAlive)
        } finally {
            releaseThread.countDown()
            executor.shutdownNow()
            worker.get()?.join(5000)
        }
        assertFalse(worker.get().isAlive)
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
