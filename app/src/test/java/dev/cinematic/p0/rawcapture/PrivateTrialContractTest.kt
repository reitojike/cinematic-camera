package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class PrivateTrialContractTest {
    private val fp = OriginalFingerprint(3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    private val source = SharedOriginalReference("content://test/original/1", fp, OriginalGrant.USER_SELECTED)
    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        override fun read(maxBytes: Int) = bytes?.clone()
        override fun replace(contents: ByteArray) { bytes = contents.clone() }
    }
    private class Image : Closeable { var closed = false; override fun close() { closed = true } }

    @Test fun trialRetainsUnverifiedStatesAcrossJournalRecoveryAndCannotEnterPublication() {
        val selected = Memory()
        val request = ManualDevelopRequests(source, selected).prepare(JpegGenerationRecipe.trial().snapshot)
        assertEquals(AutoRenderQuality.PRIVATE_TRIAL_1024, request.quality)
        assertTrue(request.boundedTrial)
        assertFalse(request.publishable)
        assertEquals(request, ManualDevelopRequests(source, selected).prepare(JpegGenerationRecipe.trial().snapshot))
        val receipt = AutoRenderReceipt(request, JpegOutputDescription(16, 12, 1, "sRGB", 3),
            RenderedFaceState.NOT_RUN, RenderedExposureState.DIAGNOSTIC)
        assertTrue(request.accepts(receipt))
        assertFalse(request.accepts(receipt.copy(face = RenderedFaceState.VERIFIED_NONE)))
        assertFalse(request.accepts(receipt.copy(exposure = RenderedExposureState.SUPPORTED)))
        val storage = Memory()
        val journal = AutoDevelopJournal(request.captureId, request.generation, storage)
        journal.prepare(request); journal.start(0); journal.rendered(1, fp, receipt)
        val recovered = AutoDevelopJournal(request.captureId, request.generation, storage).recoverAfterProcessDeath()!!
        assertEquals(AutoJobPhase.RENDERED, recovered.phase)
        assertThrows(IllegalStateException::class.java) { recovered.completed() }
        assertThrows(CaptureJournalConflict::class.java) { journal.publishing(recovered.revision) }
        assertEquals(PrivateTrialPhase.READY, PrivateTrialStatus.from(recovered).phase)
        assertFalse(PrivateTrialHandle::class.java.methods.any { it.name in setOf("publish", "confirmPublished", "completed") })
        assertEquals(RenderedFaceState.NOT_RUN, PrivateTrialStatus.from(recovered).face)
    }

    @Test fun unclaimedPreviewIsClosedOnCancellationEvenAfterFutureDeliveryAndCannotBeRevived() {
        val control = ManualDevelopControl(false)
        val ticket = control.begin()
        val image = Image()
        val delivery = PrivateTrialDelivery(image, control, ticket)
        assertTrue(control.hasPendingPreview())
        control.stop(); control.begin()
        assertTrue(image.closed)
        assertNull(delivery.claim())
        assertFalse(control.hasPendingPreview())
    }

    @Test fun claimingTransfersOnlyTheDisplayImageAndDoesNotRecycleItBehindTheUi() {
        val control = ManualDevelopControl(false)
        val image = Image()
        val delivery = PrivateTrialDelivery(image, control, control.begin())
        assertSame(image, delivery.claim())
        assertNull(delivery.claim())
        control.stop(); delivery.close()
        assertFalse(image.closed)
        assertFalse(control.hasPendingPreview())
        image.close()
        assertTrue(image.closed)
    }

    @Test fun failedDisplayCloseRetainsTheUnclaimedOwnerUntilAnAcknowledgedRetry() {
        val control = ManualDevelopControl(false)
        var closes = 0
        val value = Closeable { closes++; if (closes == 1) throw java.io.IOException("close not acknowledged") }
        val delivery = PrivateTrialDelivery(value, control, control.begin())
        assertThrows(java.io.IOException::class.java) { control.stop() }
        assertTrue(control.hasPendingPreview())
        control.stop()
        assertEquals(2, closes)
        assertFalse(control.hasPendingPreview())
        assertNull(delivery.claim())
    }

    @Test fun claimCannotAcquireAnImageWhileItsCancellationCloseIsInFlight() {
        val control = ManualDevelopControl(false)
        val closing = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val value = Closeable { closing.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        val delivery = PrivateTrialDelivery(value, control, control.begin())
        val stopper = Thread { control.stop() }.also { it.start() }
        try {
            assertTrue(closing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(delivery.claim())
            assertTrue(control.hasPendingPreview())
        } finally { release.countDown(); stopper.join(5000) }
        assertFalse(stopper.isAlive)
        assertFalse(control.hasPendingPreview())
    }
}
