package dev.cinematic.p0.rawcapture

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ManualDevelopContractTest {
    private val source = SharedOriginalReference("content://test/original/1",
        OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), OriginalGrant.USER_SELECTED)
    private val recipe = RenderRecipeSnapshot("stage-clear-bounded-jpeg-v1", "frozen-policy")
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)
    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var failAfter = false
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone(); if (failAfter) throw IOException("commit ack lost") }
    }

    @Test fun manualIdentitySurvivesRedeliveryRecreationAndCommitAcknowledgementLoss() {
        val storage = Memory()
        val registry = ManualDevelopRequests(source, storage)
        val first = registry.prepare(recipe)
        assertEquals(first, registry.prepare(recipe))
        assertEquals(first, ManualDevelopRequests(source, storage).prepare(recipe))
        storage.failAfter = true
        val changed = recipe.copy(payload = "changed")
        assertThrows(IOException::class.java) { registry.prepare(changed) }
        storage.failAfter = false
        val next = ManualDevelopRequests(source, storage).prepare(changed)
        assertEquals(first.captureId, next.captureId)
        assertEquals(first.generation + 1, next.generation)
        assertEquals(OriginalGrant.USER_SELECTED, next.source.grant)
        assertEquals(AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY, next.quality)
        assertFalse(next.publishable)
        storage.bytes!![12] = (storage.bytes!![12].toInt() xor 1).toByte()
        val corrupt = storage.bytes!!.clone()
        assertThrows(IOException::class.java) { registry.prepare(recipe) }
        assertArrayEquals(corrupt, storage.bytes)
    }

    @Test fun boundedReceiptPersistsActualDimensionsAndVerifiedObservationsWithoutPromotingRequest() {
        val request = ManualDevelopRequests(source, Memory()).prepare(recipe)
        val storage = Memory()
        val journal = AutoDevelopJournal(request.captureId, request.generation, storage)
        journal.prepare(request); journal.start(0)
        val observed = AutoRenderReceipt(request, JpegOutputDescription(768, 1024, 1, "sRGB", 3),
            RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        journal.rendered(1, source.fingerprint, observed)
        val restored = AutoDevelopJournal(request.captureId, request.generation, storage).read()!!
        assertEquals(768, restored.completed().request.output.width)
        assertEquals(RenderedFaceState.NOT_RUN, restored.request.face)
        assertEquals(RenderedFaceState.VERIFIED_NONE, restored.completed().face)
        assertEquals(AutoInputProfile.UNKNOWN, restored.request.profile)
        assertFalse(restored.request.publishable)
        assertFalse(request.accepts(observed.copy(output = observed.output.copy(width = 1025))))
        assertFalse(request.accepts(observed.copy(face = RenderedFaceState.NOT_RUN)))
        assertFalse(request.accepts(observed.copy(exposure = RenderedExposureState.DIAGNOSTIC)))
        assertFalse(request.copy(quality = AutoRenderQuality.FINAL_ORIGINAL).accepts(observed))
    }

    @Test fun interactiveCapabilityIsIndependentButCaptureAndActualCloseAreShared() {
        val coordinator = AutoDevelopCoordinator()
        coordinator.updateConditions(allowed.copy(rendererAvailable = false))
        val preview = coordinator.tryStartObserved(allowed)!!
        assertNull(coordinator.tryStartObserved(allowed))
        assertNull(coordinator.tryStart())
        coordinator.updateConditions(allowed.copy(rendererAvailable = false))
        assertFalse(coordinator.shouldStop(preview))
        val capture = coordinator.reserveCapture()
        assertTrue(coordinator.shouldStop(preview))
        coordinator.releaseCapture(capture)
        assertNull(coordinator.tryStartObserved(allowed))
        assertTrue(coordinator.completeAfterResourcesClosed(preview))
        assertNull(coordinator.tryStartObserved(allowed.copy(memoryBudgetConfirmed = null)))
        val manual = coordinator.tryStartObserved(allowed)!!
        coordinator.requestStop(preview)
        assertFalse(coordinator.shouldStop(manual))
    }

    @Test fun latestCommonUnsafeObservationCannotBeOverriddenByAnOlderInteractiveSnapshot() {
        for (unsafe in listOf(allowed.copy(thermalAllowed = false), allowed.copy(cameraSessionInUse = true),
                allowed.copy(memoryBudgetConfirmed = null), allowed.copy(foreground = false))) {
            val coordinator = AutoDevelopCoordinator()
            coordinator.updateConditions(unsafe)
            assertNull(coordinator.tryStartObserved(allowed))
            coordinator.updateConditions(allowed.copy(rendererAvailable = false))
            assertNotNull(coordinator.tryStartObserved(allowed))
        }
    }
}
