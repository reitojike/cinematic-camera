package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class PrivateTrialPrepareGateTest {
    private val fingerprint = OriginalFingerprint(3, "a".repeat(64))
    private val source = SharedOriginalReference("content://example.test/original/1", fingerprint, OriginalGrant.USER_SELECTED)
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone() }
    }
    private class Serial : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() {
            var count = 0
            while (pending.isNotEmpty()) {
                check(count++ < 100) { "Unexpected repeated work in synthetic queue" }
                pending.removeFirst().run()
            }
        }
    }

    private inner class Fixture {
        val io = Serial()
        val selected = Memory()
        val registry = ManualDevelopRequests(source, selected)
        val stores = mutableMapOf<Long, Memory>()
        val rendered = mutableListOf<AutoDevelopRequest>()
        val admission = AutoDevelopCoordinator().apply { updateConditions(allowed) }
        val queue = AutoDevelopQueue(io, admission, { request ->
            AutoDevelopJournal(request.captureId, request.generation, stores.getOrPut(request.generation) { Memory() })
        }, { fixed -> object : AutoDevelopBackend {
            override val hasUnreleasedResources = false
            override fun releaseResources() {}
            override fun supports(request: AutoDevelopRequest) = request.boundedTrial &&
                JpegGenerationRecipe.fromTrialSnapshot(request.recipe) != null
            override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean) {
                assertEquals(source, request.source)
                assertFalse(shouldStop())
            }
            override fun createAttempt(request: AutoDevelopRequest, attemptId: String) = object : AutoRenderAttempt {
                override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                    assertEquals(fixed, request)
                    assertFalse(shouldStop())
                    rendered += request
                    return AutoRenderReceipt(request, JpegOutputDescription(16, 12, 1, "sRGB", fingerprint.bytes),
                        RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC)
                }
                override fun close() {}
            }
            override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean) =
                JpegInspection(JpegOutputDescription(16, 12, 1, "sRGB", fingerprint.bytes), fingerprint)
            override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication =
                error("A private trial must never acquire a publication owner")
        } }, Executor { it.run() })

        fun legacyPrepare(strength: Double): PrivateTrialHandle {
            val future = queue.preparePrivateTrial(registry.captureId) { registry.prepare(JpegGenerationRecipe.trial(strength).snapshot) }
            io.drain()
            return future.join()
        }
        fun status(handle: PrivateTrialHandle): PrivateTrialStatus {
            val future = handle.status()
            io.drain()
            return future.join()
        }
    }

    @Test fun cancelledBeforeIoStartsDoesNotAllocateOrFenceTheAlreadyLatestStartIntent() {
        val fixture = Fixture()
        val latest = fixture.legacyPrepare(.75)
        val before = fixture.selected.bytes!!.clone()
        var stopped = false
        var stopChecks = 0
        var allocations = 0
        val startingLatest = latest.start(allowed)
        val stale = fixture.queue.preparePrivateTrial(fixture.registry.captureId, {
            stopChecks++
            stopped
        }) {
            allocations++
            fixture.registry.prepare(JpegGenerationRecipe.trial(.25).snapshot)
        }
        assertEquals("The gate must be evaluated by the IO owner, not the caller", 0, stopChecks)
        stopped = true
        fixture.io.drain()
        assertThrows(CancellationException::class.java) { stale.join() }
        assertEquals(1, stopChecks)
        assertEquals(0, allocations)
        assertArrayEquals(before, fixture.selected.bytes)
        assertFalse(startingLatest.isCompletedExceptionally)
        assertEquals(PrivateTrialPhase.READY, fixture.status(latest).phase)
        assertEquals(listOf(latest.request.recipe), fixture.rendered.map { it.recipe })
        assertEquals(.75, JpegGenerationRecipe.fromTrialSnapshot(fixture.rendered.single().recipe)!!.strength, 0.0)
        assertEquals(1, fixture.stores.size)
    }

    @Test fun allowedIoGateRetainsVariableStrengthAndTheLegacyEntryStillWorks() {
        val fixture = Fixture()
        val legacy = fixture.legacyPrepare(.5)
        var stopChecks = 0
        var allocations = 0
        val preparing = fixture.queue.preparePrivateTrial(fixture.registry.captureId, {
            stopChecks++
            false
        }) {
            allocations++
            fixture.registry.prepare(JpegGenerationRecipe.trial(.123456789012345).snapshot)
        }
        assertEquals(0, stopChecks)
        assertEquals(0, allocations)
        fixture.io.drain()
        val current = preparing.join()
        assertEquals(1, stopChecks)
        assertEquals(1, allocations)
        assertEquals(legacy.request.generation + 1, current.request.generation)
        assertEquals(.123456789012345, JpegGenerationRecipe.fromTrialSnapshot(current.request.recipe)!!.strength, 0.0)
        current.start(allowed)
        fixture.io.drain()
        assertEquals(PrivateTrialPhase.READY, fixture.status(current).phase)
        assertEquals(listOf(current.request.recipe), fixture.rendered.map { it.recipe })
        assertFalse(fixture.rendered.single().publishable)
    }
}
