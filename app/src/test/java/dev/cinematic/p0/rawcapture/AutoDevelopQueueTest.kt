package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AutoDevelopQueueTest {
    private fun request(id: String = "capture", generation: Long = 1) = AutoDevelopRequest(id, generation,
        SharedOriginalReference("content://test/original/1", OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), OriginalGrant.APP_OWNED),
        RenderRecipeSnapshot("test-v1", "fixed"), "test-final-v1", 16, 12, AutoRenderQuality.FINAL_ORIGINAL,
        RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED, AutoInputProfile.SUPPORTED)
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Test fun metadataSubmissionIsAsynchronousAndJobsRunOneAtATimeAfterAdmission() {
        val f = Fixture()
        val accepted = f.queue.submit(request())
        assertFalse(accepted.isDone)
        f.io.drain()
        assertTrue(accepted.isDone)
        assertEquals(0, f.starts.get())
        val capture = f.queue.reserveCapture()
        f.queue.updateConditions(allowed)
        f.io.drain()
        assertEquals(0, f.starts.get())
        f.queue.releaseCapture(capture)
        f.io.drain()
        assertEquals(1, f.starts.get())
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request()).read()!!.phase)
        f.queue.wake(); f.io.drain()
        assertEquals(1, f.starts.get())
    }

    @Test fun newGenerationSuppressesQueuedOldGenerationAndOldRetryIsRejected() {
        val f = Fixture()
        f.queue.submit(request(generation = 1))
        f.queue.submit(request(generation = 2))
        f.io.drain()
        f.queue.updateConditions(allowed)
        f.io.drain()
        assertEquals(AutoJobPhase.QUEUED, f.journal(request(generation = 1)).read()!!.phase)
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request(generation = 2)).read()!!.phase)
        assertEquals(1, f.starts.get())
        val retry = f.queue.retry("capture", 1)
        f.io.drain()
        assertTrue(retry.isCompletedExceptionally)
    }

    @Test fun renderFailureDoesNotCreateAHotRetryLoopAndRetryGetsNewAttempt() {
        val f = Fixture()
        f.failRender = true
        f.queue.submit(request())
        f.queue.updateConditions(allowed)
        f.io.drain()
        val failed = f.journal(request()).read()!!
        assertEquals(AutoJobPhase.RETRYABLE, failed.phase)
        repeat(3) { f.queue.wake(); f.io.drain() }
        assertEquals(1, f.starts.get())
        f.failRender = false
        val retried = f.queue.retry("capture", 1)
        f.io.drain()
        assertFalse(retried.isCompletedExceptionally)
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request()).read()!!.phase)
        assertNotEquals(failed.attemptId, f.journal(request()).read()!!.attemptId)
        assertEquals(2, f.starts.get())
    }

    @Test fun duplicateDeliveryAndAnotherCaptureDoNotCancelTheActiveJob() {
        val f = Fixture()
        f.onRender = { current ->
            if (current.captureId == "first") {
                f.queue.submit(current)
                f.queue.submit(request("second"))
            }
        }
        f.queue.submit(request("first")); f.queue.updateConditions(allowed); f.io.drain()
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request("first")).read()!!.phase)
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request("second")).read()!!.phase)
        assertEquals(2, f.starts.get())
    }

    @Test fun aNewGenerationDuringRenderingStopsOnlyItsOldDefinition() {
        val f = Fixture()
        f.onRender = { current -> if (current.generation == 1L) f.queue.submit(current.copy(generation = 2)) }
        f.queue.submit(request()); f.queue.updateConditions(allowed); f.io.drain()
        assertEquals(AutoJobPhase.RETRYABLE, f.journal(request()).read()!!.phase)
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request(generation = 2)).read()!!.phase)
        assertEquals(2, f.starts.get())
    }

    @Test fun retainedCloseOwnerCannotBeBypassedBySubmittingAnotherJob() {
        val f = Fixture()
        f.failClose = true
        f.queue.submit(request("first")); f.queue.submit(request("second"))
        f.queue.updateConditions(allowed)
        f.io.drain()
        assertEquals(1, f.starts.get())
        assertEquals(AutoJobPhase.RENDERING, f.journal(request("first")).read()!!.phase)
        assertEquals(AutoJobPhase.QUEUED, f.journal(request("second")).read()!!.phase)
        f.failClose = false
        f.queue.wake(); f.io.drain()
        assertEquals(2, f.starts.get())
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request("first")).read()!!.phase)
        assertEquals(AutoJobPhase.PUBLISHED, f.journal(request("second")).read()!!.phase)
    }

    @Test fun processRestoreChangesOnlyUnacknowledgedRenderingToExplicitRetry() {
        val f = Fixture()
        val request = request()
        f.journal(request).prepare(request)
        val old = f.journal(request).start(0)
        val restored = f.queue.restore(listOf(request))
        f.queue.updateConditions(allowed)
        f.io.drain()
        assertFalse(restored.isCompletedExceptionally)
        assertEquals(AutoJobPhase.RETRYABLE, f.journal(request).read()!!.phase)
        assertEquals(old.attemptId, f.journal(request).read()!!.attemptId)
        assertEquals(0, f.starts.get())
        val duplicateRestore = f.queue.restore(listOf(request))
        f.io.drain()
        assertTrue(duplicateRestore.isCompletedExceptionally)
    }

    @Test fun failedRestoreCannotStartAnOldGenerationFromAPartialInventory() {
        val f = Fixture()
        val old = request(generation = 1)
        val current = request(generation = 2)
        f.journal(old).prepare(old); f.journal(current).prepare(current)
        f.corrupt(current)
        val restored = f.queue.restore(listOf(old, current))
        f.queue.updateConditions(allowed)
        f.io.drain()
        assertTrue(restored.isCompletedExceptionally)
        assertEquals(0, f.starts.get())
        assertEquals(AutoJobPhase.QUEUED, f.journal(old).read()!!.phase)
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() {
            var count = 0
            while (tasks.isNotEmpty()) { check(++count < 100) { "Unexpected automatic retry loop" }; tasks.removeFirst().run() }
        }
    }
    private class MemoryStorage : AtomicJournalStorage {
        var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone() }
    }
    private inner class Fixture {
        val io = ManualExecutor()
        val starts = AtomicInteger()
        var failRender = false
        var failClose = false
        var onRender: (AutoDevelopRequest) -> Unit = {}
        private val storage = mutableMapOf<Pair<String, Long>, MemoryStorage>()
        fun corrupt(request: AutoDevelopRequest) {
            val bytes = storage.getValue(request.captureId to request.generation).bytes!!
            bytes[8] = (bytes[8].toInt() xor 1).toByte()
        }
        fun journal(request: AutoDevelopRequest) = AutoDevelopJournal(request.captureId, request.generation,
            storage.getOrPut(request.captureId to request.generation) { MemoryStorage() })
        val queue = AutoDevelopQueue(io, AutoDevelopCoordinator(), ::journal, { request -> object : AutoDevelopBackend {
            override val hasUnreleasedResources get() = false
            override fun releaseResources() {}
            override fun supports(request: AutoDevelopRequest) = true
            override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean) {}
            override fun createAttempt(request: AutoDevelopRequest, attemptId: String) = object : AutoRenderAttempt {
                override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                    starts.incrementAndGet()
                    onRender(request)
                    if (shouldStop()) throw java.util.concurrent.CancellationException("superseded")
                    if (failRender) throw IOException("synthetic render failure")
                    return AutoRenderReceipt(request, JpegOutputDescription(16, 12, 1, "sRGB", 3))
                }
                override fun close() { if (failClose) throw IOException("synthetic close failure") }
            }
            override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean) =
                JpegInspection(JpegOutputDescription(16, 12, 1, "sRGB", 3), request.source.fingerprint)
            override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean) = object : AutoDevelopPublication {
                override val hasUnreleasedResources = false
                override fun releaseResources() {}
                override fun advance(shouldStop: () -> Boolean) = JpegPublicationPhase.PUBLISHED
            }
        } }, cancellationIo = Executor { it.run() }) // No native work in this deterministic queue fixture.
    }
}
