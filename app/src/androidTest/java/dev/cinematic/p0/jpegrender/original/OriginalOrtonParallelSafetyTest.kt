package dev.cinematic.p0.jpegrender.original

import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.original.*
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Private synthetic Orton-worker lifecycle probe; no DNG, camera, shared media or publication. */
@RunWith(AndroidJUnit4::class)
class OriginalOrtonParallelSafetyTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun fixture(): Fixture {
        val context = inst.targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        return Fixture(File(context.noBackupFilesDir, "orton-worker-safety-${UUID.randomUUID()}").also { check(it.mkdir()) })
    }
    private fun status(name: String, value: JSONObject) =
        inst.sendStatus(0, Bundle().apply { putString("stream", "\nOrtonWorkerSafety $name $value\n") })

    @Test fun cancellationStopsQueuedWorkersAndAllowsCleanNewAttempt() = fixture().use { f ->
        val cancelled = AtomicBoolean(false)
        val started = ConcurrentLinkedQueue<Int>()
        assertThrows(CancellationException::class.java) {
            f.render("cancel", 4, OriginalMask.NONE, { cancelled.get() }) { y ->
                started.add(y)
                if (y == 2) cancelled.set(true)
            }
        }
        assertTrue(cancelled.get())
        assertTrue(started.contains(2))
        assertTrue(started.size < f.height)
        f.verifySource()
        val retry = f.render("retry-after-cancel", 4)
        assertEquals(f.render("retry-baseline", 1).sha256, retry.sha256)
        status("cancel", JSONObject().put("startedRows", started.size).put("maxPendingRows", 8)
            .put("retryExact", true).put("sourceUnchanged", true))
    }

    @Test fun oneWorkerExceptionPropagatesAndSiblingsStop() = fixture().use { f ->
        val failure = IOException("injected Orton worker error")
        val started = ConcurrentLinkedQueue<Int>()
        val actual = assertThrows(IOException::class.java) {
            f.render("worker-failure", 4, OriginalMask.NONE, { false }) { y ->
                started.add(y)
                if (y == 2) throw failure
            }
        }
        assertSame(failure, actual)
        assertTrue(started.contains(2))
        assertTrue(started.size < f.height)
        f.verifySource()
        val retry = f.render("retry-after-failure", 4)
        assertEquals(f.render("failure-baseline", 1).sha256, retry.sha256)
        status("failure", JSONObject().put("startedRows", started.size).put("maxPendingRows", 8)
            .put("sameFailureObject", true).put("retryExact", true).put("sourceUnchanged", true))
    }

    @Test fun cancellationInsideOrtonPixelMathStopsSiblings() = fixture().use { f ->
        val cancelled = AtomicBoolean(false)
        val target = AtomicReference<Thread?>()
        val polls = AtomicInteger()
        val stop = {
            if (Thread.currentThread() === target.get() && polls.incrementAndGet() == 5) cancelled.set(true)
            cancelled.get()
        }
        assertThrows(CancellationException::class.java) {
            f.render("cancel-during-pixels", 4, OriginalMask.NONE, stop) { y ->
                if (y == 2) target.set(Thread.currentThread())
            }
        }
        assertTrue(cancelled.get())
        assertTrue("the target worker must pass multiple in-row checkpoints", polls.get() >= 5)
        f.verifySource()
        status("mid-row-cancel", JSONObject().put("targetWorkerPolls", polls.get())
            .put("workerThreadsAfter", 0).put("sourceUnchanged", true))
    }

    @Test fun faceCutRerenderMatchesSingleWorkerRawBits() = fixture().use { f ->
        val faces = OriginalMask { _, row -> row.fill(1f) }
        val reference = f.render("face-baseline", 1, faces)
        val parallel = f.render("face-parallel", 4, faces)
        assertTrue("synthetic bright face must exercise the second layer", reference.faceCutFactor < 1.0)
        assertEquals(reference.faceCutFactor, parallel.faceCutFactor, 0.0)
        assertEquals(reference.sha256, parallel.sha256)
        f.verifySource()
        status("face-cut", JSONObject().put("factor", reference.faceCutFactor)
            .put("rawFloatSha256", reference.sha256).put("sourceUnchanged", true))
    }

    @Test fun largeDimensionsCancelAndRemovePartialRaster() {
        val context = inst.targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val scratch = File(context.noBackupFilesDir, "orton-24mp-cancel-${UUID.randomUUID()}")
        val work = OriginalRasterWorkspace(scratch)
        val cancelled = AtomicBoolean(false)
        val started = ConcurrentLinkedQueue<Int>()
        val workers = ConcurrentHashMap.newKeySet<Thread>()
        val input = object : OriginalRaster {
            override val width = 6000
            override val height = 4000
            override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
                val row = FloatArray(width * 3) { .42f }
                repeat(height) { y ->
                    originalCheckpoint(stop)
                    consume(y, row)
                }
            }
        }
        try {
            assertThrows(CancellationException::class.java) {
                work.mapParallelRows(input, { cancelled.get() }, 4) { y, row ->
                    workers.add(Thread.currentThread())
                    started.add(y)
                    if (y == 128) cancelled.set(true)
                    originalCheckpoint { cancelled.get() || Thread.currentThread().isInterrupted }
                    row
                }
            }
            val partial = scratch.listFiles().orEmpty().single()
            assertTrue("some ordered rows should already be written", partial.length() > 0)
            assertTrue(partial.length() < 6000L * 4000 * 12)
            assertOwnedWorkersStopped(workers)
            status("24mp-cancel", JSONObject().put("startedRows", started.size)
                .put("partialBytes", partial.length()).put("workerThreadsAfter", 0))
        } finally {
            work.closePending()
            work.disposeConfirmed()
            assertFalse(scratch.exists())
        }
    }

    @Test fun actualAttemptCancellationClosesOwnersAndProtectsDng() {
        val context = inst.targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val root = File(context.noBackupFilesDir, "orton-attempt-cancel-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val input = File(root, "input.dng")
            val bytes = input.outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
            val fingerprint = DngContentVerifier.identify(bytes, { input.inputStream() })
            val source = SharedOriginalReference("content://example.test/orton-cancel", fingerprint, OriginalGrant.USER_SELECTED)
            val output = File(root, "result.jpg")
            val io = object : AutoRenderIo {
                override fun openOriginal() = input.inputStream()
                override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Use immutable snapshot")
                override fun openOutput() = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                override fun openOutputRead() = output.inputStream()
            }
            val workers = ConcurrentHashMap.newKeySet<Thread>()
            val attempt = Original24Attempt(context, Original24Request("synthetic_original", 1, source), io,
                File(root, "scratch"), parallelOrtonWorkers = 4,
                ortonRowProbe = { y ->
                    workers.add(Thread.currentThread())
                    if (y == 2) throw CancellationException("injected Orton cancellation")
                })
            try {
                assertThrows(CancellationException::class.java) { attempt.render { false } }
            } finally { attempt.close() }
            assertFalse(attempt.hasUnreleasedResources)
            assertOwnedWorkersStopped(workers)
            DngContentVerifier.verify(fingerprint, { input.inputStream() })
            assertFalse(output.exists())
            // The attempt deliberately retains failed private scratch for its owner, not publication.
            assertTrue(File(root, "scratch").isDirectory)
            status("attempt-cancel", JSONObject().put("ownersClosed", true)
                .put("originalUnchanged", true).put("outputAbsent", true))
        } finally { assertTrue(root.deleteRecursively()) }
    }

    @Test fun simultaneousPrivateSessionsHaveIdenticalOutputAndNoSharedWorkers() = fixture().use { firstFixture ->
        fixture().use { secondFixture ->
            // A raster file admits one reader at a time. Real attempts decode into private rasters.
            val expected = firstFixture.render("concurrent-baseline", 1).sha256
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<String> { firstFixture.render("concurrent-a", 4).sha256 }
                val second = executor.submit<String> { secondFixture.render("concurrent-b", 4).sha256 }
                assertEquals(expected, first.get(30, TimeUnit.SECONDS))
                assertEquals(expected, second.get(30, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
            firstFixture.verifySource(); secondFixture.verifySource()
            status("concurrent", JSONObject().put("privateSessions", 2).put("rawFloatSha256", expected)
                .put("workerThreadsAfter", 0).put("sourcesUnchanged", true))
        }
    }

    @Test fun synthetic24MpAttemptCancellationAtOrtonClosesResources() {
        val context = inst.targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("original24mpSafety") == "true")
        val root = File(context.noBackupFilesDir, "orton-24mp-attempt-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        try {
            val input = File(root, "input.dng")
            val bytes = input.outputStream().use { writeSyntheticTrialDng(it, 6000, 4000, 1) }
            val fingerprint = DngContentVerifier.identify(bytes, { input.inputStream() })
            val source = SharedOriginalReference("content://example.test/orton-24mp-cancel", fingerprint, OriginalGrant.USER_SELECTED)
            val output = File(root, "result.jpg")
            val io = object : AutoRenderIo {
                override fun openOriginal() = input.inputStream()
                override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Use immutable snapshot")
                override fun openOutput() = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                override fun openOutputRead() = output.inputStream()
            }
            val workers = ConcurrentHashMap.newKeySet<Thread>()
            val attempt = Original24Attempt(context, Original24Request("synthetic_original", 1, source), io,
                File(root, "scratch"), parallelOrtonWorkers = 4,
                ortonRowProbe = { y ->
                    workers.add(Thread.currentThread())
                    if (y == 128) throw CancellationException("injected 24MP Orton cancellation")
                })
            val start = android.os.SystemClock.elapsedRealtime()
            try {
                assertThrows(CancellationException::class.java) { attempt.render { false } }
            } finally { attempt.close() }
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            assertFalse(attempt.hasUnreleasedResources)
            assertOwnedWorkersStopped(workers)
            DngContentVerifier.verify(fingerprint, { input.inputStream() })
            assertFalse(output.exists())
            val fullBytes = 6000L * 4000 * 12
            val partials = File(root, "scratch").listFiles().orEmpty().filter {
                it.name.endsWith(".f32") && it.length() in 1L until fullBytes
            }
            assertTrue("Orton cancellation should leave a private partial raster for owner cleanup", partials.isNotEmpty())
            status("24mp-attempt-cancel", JSONObject().put("elapsedMs", elapsed)
                .put("partialRasterBytes", partials.maxOf { it.length() }).put("ownersClosed", true)
                .put("originalUnchanged", true).put("outputAbsent", true))
        } finally { assertTrue(root.deleteRecursively()) }
    }

    private fun assertOwnedWorkersStopped(workers: Set<Thread>) {
        // Executor TERMINATED can be signalled before a Java worker thread has exited.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        workers.forEach { worker ->
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("Timed out waiting for owned Orton worker ${describe(worker)}")
            worker.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1)
            if (worker.isAlive) fail("Owned Orton worker remains alive: ${describe(worker)}")
        }
    }
    private fun describe(worker: Thread) =
        "id=${worker.id} name=${worker.name} state=${worker.state} stack=${worker.stackTrace.take(8).joinToString()}"
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private data class Output(val sha256: String, val faceCutFactor: Double)

    private inner class Fixture(val root: File) : AutoCloseable {
        val width = 64; val height = 48
        val sourceFile = File(root, "source.f32")
        val source: OriginalRasterFile
        private val sourceHash: String
        init {
            sourceFile.outputStream().use { stream ->
                val row = ByteBuffer.allocate(width * 12).order(ByteOrder.LITTLE_ENDIAN)
                repeat(height) { y ->
                    row.clear()
                    repeat(width) { x ->
                        val bright = x in 16 until 48 && y in 12 until 36
                        row.putFloat(if (bright) 10f else .55f)
                        row.putFloat(if (bright) 8f else .36f)
                        row.putFloat(if (bright) 6f else .25f)
                    }
                    stream.write(row.array())
                }
            }
            sourceHash = sha(sourceFile)
            source = OriginalRasterFile(sourceFile, width, height)
        }
        fun render(name: String, workers: Int, faces: OriginalMask = OriginalMask.NONE,
            stop: () -> Boolean = { false }, rowProbe: ((Int) -> Unit)? = null): Output {
            val scratch = File(root, name)
            val work = OriginalRasterWorkspace(scratch)
            val ownedWorkers = ConcurrentHashMap.newKeySet<Thread>()
            try {
                val result = OriginalStageClear24(work, parallelOrtonWorkers = workers, ortonRowProbe = { y ->
                    if (workers > 1) ownedWorkers.add(Thread.currentThread())
                    rowProbe?.invoke(y)
                })
                    .render(source, 0.0, .5, faces = faces, stop = stop)
                return Output(sha(result.raster.file), result.faceCutFactor)
            } finally {
                try { assertOwnedWorkersStopped(ownedWorkers) }
                finally {
                    work.closePending()
                    work.disposeConfirmed()
                    assertFalse(scratch.exists())
                    verifySource()
                }
            }
        }
        fun verifySource() = assertEquals(sourceHash, sha(sourceFile))
        override fun close() {
            verifySource()
            assertTrue(sourceFile.delete())
            assertTrue(root.delete())
        }
    }
}
