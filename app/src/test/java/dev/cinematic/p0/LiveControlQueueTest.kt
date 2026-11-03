package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class LiveControlQueueTest {
    private val initial = ControlSnapshot(evIndex = 0)

    @Test fun evZeroToOneBurstKeepsLatestIntentWhileOldAckArrives() {
        val queue = LatestLiveControlQueue()
        queue.offer(1, initial.copy(evIndex = 1)); val first = queue.take()!!
        for (index in 2..6) queue.offer(1, queue.intent!!.copy(evIndex = index))
        assertEquals(6, queue.intent?.evIndex)
        assertEquals(first, queue.active)
        assertNull(queue.take())
        assertTrue(queue.complete(first))
        assertEquals(6, queue.intent?.evIndex)
        val final = queue.take()!!
        assertEquals(6, final.controls.evIndex)
        assertNull(queue.queued)
        assertFalse(queue.complete(first))
        assertEquals(final, queue.active)
        assertTrue(queue.complete(final))
        assertFalse(queue.hasWork)
        assertNull(queue.intent)
    }

    @Test fun whiteBalanceBurstNeverErasesLatestIsoShutterOrEv() {
        val queue = LatestLiveControlQueue()
        val iso = ManualExposure(800, 8_000_000)
        queue.offer(3, initial.copy(exposure = iso, evIndex = 3)); val active = queue.take()!!
        for (wb in listOf(5, 6, 2, 8, 1)) queue.offer(3,
            queue.intent!!.copy(overrides = queue.intent!!.overrides.copy(whiteBalance = wb)))
        queue.offer(3, queue.intent!!.copy(exposure = queue.intent!!.exposure!!.copy(exposureNs = 4_000_000)))
        queue.complete(active)
        val newest = queue.take()!!.controls
        assertEquals(ManualExposure(800, 4_000_000), newest.exposure)
        assertEquals(3, newest.evIndex)
        assertEquals(1, newest.overrides.whiteBalance)
    }

    @Test fun manualFocusBackAndForthAndAutofocusKeepIndependentControls() {
        val queue = LatestLiveControlQueue()
        val base = initial.copy(exposure = ManualExposure(400, 8_000_000),
            overrides = SensorOverrides(whiteBalance = 5, antiBanding = 2))
        queue.offer(4, base.copy(overrides = base.overrides.copy(focusDistance = 0f)))
        val infinity = queue.take()!!
        for (focus in listOf(1f, 3f, 2f, 0f, 4f)) queue.offer(4,
            queue.intent!!.copy(overrides = queue.intent!!.overrides.copy(focusDistance = focus)))
        queue.complete(infinity)
        val manual = queue.take()!!
        assertEquals(4f, manual.controls.overrides.focusDistance)
        queue.offer(4, queue.intent!!.copy(overrides = queue.intent!!.overrides.copy(focusDistance = null)))
        queue.complete(manual)
        val af = queue.take()!!.controls
        assertEquals(base, af)
        assertNull(af.overrides.focusDistance)
    }

    @Test fun repeatedInputIsBoundedAndCannotReplaceInflightCameraOperation() {
        val queue = LatestLiveControlQueue()
        queue.offer(7, initial); val first = queue.take()!!
        repeat(10_000) { queue.offer(7, initial.copy(evIndex = it)) }
        assertEquals(first, queue.active)
        assertEquals(9999, queue.queued?.controls?.evIndex)
        assertNull(queue.take())
        queue.complete(first)
        assertEquals(9999, queue.take()?.controls?.evIndex)
    }

    @Test fun timeoutRetainsLatestAggregateWithoutRetryAndRejectsLateAck() {
        val queue = LatestLiveControlQueue()
        val gate = ControlOperation()
        queue.offer(2, initial); val old = queue.take()!!
        val token = gate.begin(2, 0, 3000)
        val latest = initial.copy(evIndex = 3, overrides = SensorOverrides(6, 2f))
        queue.offer(2, latest)
        assertTrue(gate.expire(token, 3000))
        queue.cancel(keepIntent = true)
        assertEquals(latest, queue.intent)
        assertNull(queue.take())
        assertFalse(queue.hasWork)
        assertFalse(queue.complete(old))
        assertFalse(gate.current(token))
    }

    @Test fun backgroundInFirstCoalescingWindowRetainsWholeUnsentSnapshot() {
        val queue = LatestLiveControlQueue()
        val target = initial.copy(exposure = ManualExposure(1600, 2_000_000_000), overrides = SensorOverrides(5, 0f))
        queue.offer(2, target)
        assertNull(queue.active)
        queue.cancel(keepIntent = true)
        assertEquals(target, queue.intent)
        assertNull(queue.take())
        val resume = StillResumeIntent()
        resume.remember("wide/STILL", null, null, queue.intent, initial)
        assertEquals(target, resume.peek("wide/STILL")?.controls)
    }

    @Test fun newCameraEpochCannotBeCompletedByAnOldCameraAck() {
        val queue = LatestLiveControlQueue()
        queue.offer(2, initial); val old = queue.take()!!
        queue.cancel()
        assertNull(queue.intent)
        queue.offer(3, initial.copy(evIndex = 2)); val current = queue.take()!!
        assertFalse(queue.complete(old))
        assertEquals(current, queue.active)
        assertEquals(2, queue.intent?.evIndex)
    }

    @Test fun exposureAutoSupersedesPendingExposureAndKeepsLatestWbFocus() {
        val desired = initial.copy(exposure = ManualExposure(800, 8_000_000),
            overrides = SensorOverrides(6, 3f, 2), locks = AutoLocks(whiteBalance = false))
        assertEquals(desired.copy(exposure = null), autoControlSnapshot(desired, 0, reset = false))
        assertEquals(initial, autoControlSnapshot(desired, 0, reset = true))
    }

    @Test fun failedExposureAutoRetryKeepsAutoAndLatestIndependentControlsOverSavedManual() {
        val savedManual = initial.copy(exposure = ManualExposure(800, 8_000_000),
            overrides = SensorOverrides(5, 1f, 2))
        val latest = savedManual.copy(overrides = SensorOverrides(6, 3f, 2))
        val failedAuto = exposureOperationIntent(null, latest, savedEv = 2)
        assertNull(failedAuto.exposure)
        assertEquals(latest.overrides, failedAuto.overrides)
        assertEquals(2, failedAuto.evIndex)
        // Explicit same-scope rebind supplies the retained logical intent before saved preferences.
        val retried = exposureOperationIntent(failedAuto.exposure, savedManual, savedEv = -1,
            restored = failedAuto.copy(locks = AutoLocks()))
        assertEquals(failedAuto, retried)
    }

    @Test fun failedFullResetRetryCannotResurrectOldManualOrIndependentControls() {
        val previous = initial.copy(exposure = ManualExposure(1600, 500_000_000),
            evIndex = 3, overrides = SensorOverrides(6, 4f, 2), locks = AutoLocks(whiteBalance = true))
        val failedReset = exposureOperationIntent(null, previous, savedEv = 3, reset = true)
        assertEquals(initial, failedReset)
        val retried = exposureOperationIntent(failedReset.exposure, previous, savedEv = 3, restored = failedReset)
        assertEquals(initial, retried)
    }

    @Test fun ordinarySavedRestoreUsesNewScopeControlsAndClearsTransientLocks() {
        val previousScope = initial.copy(exposure = ManualExposure(800, 8_000_000),
            overrides = SensorOverrides(6, 3f), locks = AutoLocks(whiteBalance = true))
        val currentScopeSaved = SensorOverrides(5, null, 2)
        // bind's existing key guard supplies no restored snapshot after a camera/mode change.
        val restored = exposureOperationIntent(null, previousScope, savedEv = -2, savedOverrides = currentScopeSaved)
        assertEquals(ControlSnapshot(evIndex = -2, overrides = currentScopeSaved), restored)
    }

    @Test fun logicalManualRestoreUsesCaptureExposureAndRetainedEvBeforePreviewConversion() {
        val capture = ManualExposure(100, 2_000_000_000)
        val retained = ControlSnapshot(capture, 3, SensorOverrides(5, 0f))
        val logical = exposureOperationIntent(capture, initial, savedEv = -1, restored = retained)
        assertEquals(retained, logical)
        assertNotEquals(ManualExposure(6400, 31_250_000), logical.exposure)
    }

    @Test fun previewCompensationNeverBecomesTheNextLogicalCapturePair() {
        val capture = ManualExposure(100, 2_000_000_000)
        val queue = LatestLiveControlQueue()
        queue.offer(1, initial.copy(exposure = capture)); val active = queue.take()!!
        val runtimeReceipt = ControlReceipt(epoch = 1, phase = ControlPhase.ACCEPTED,
            accepted = active.controls.copy(exposure = ManualExposure(6400, 31_250_000)), acceptedAtMs = 100)
        queue.offer(1, queue.intent!!.copy(overrides = SensorOverrides(whiteBalance = 6)))
        queue.complete(active)
        assertEquals(capture, queue.take()?.controls?.exposure)
        assertNotEquals(capture, runtimeReceipt.accepted?.exposure)
    }

    private fun frame(number: Long, time: Long, iso: Int? = null, ns: Long? = null, epoch: Int = 1,
        intent: CaptureSampleIntent = CaptureSampleIntent.PREVIEW) = CaptureSample(epoch, number, time,
        SensorValues(), SensorValues(iso = iso, exposureNs = ns), intent = intent)

    @Test fun mailboxKeepsValidAxesAcrossMissingFramesBeforeUiDrain() {
        val mailbox = PreviewExposureMailbox()
        assertTrue(mailbox.offer(frame(1, 100, iso = 777)))
        assertFalse(mailbox.offer(frame(2, 120, ns = 12_345_678)))
        assertFalse(mailbox.offer(frame(3, 140, iso = -1, ns = 0)))
        val result = mailbox.drain()!!
        assertEquals(100L, result.iso?.receivedAtMs)
        assertEquals(120L, result.shutter?.receivedAtMs)
        assertEquals(ManualExposure(777, 12_345_678), freshPreviewExposurePair(result, 1, 200))
        assertNull(freshPreviewExposurePair(result, 1, 2101))
        assertTrue(mailbox.offer(frame(4, 160, iso = 888)))
    }

    @Test fun mailboxAndManualSeedRejectWrongEpochStillAndFutureTimestamps() {
        val mailbox = PreviewExposureMailbox()
        mailbox.offer(frame(1, 100, iso = 800, ns = 8_000_000, epoch = 2))
        mailbox.offer(frame(9, 900, iso = 1600, ns = 4_000_000, epoch = 1))
        val result = mailbox.drain()!!
        assertEquals(2, result.epoch)
        assertNull(freshPreviewExposurePair(result, 1, 150))
        assertNull(freshPreviewExposurePair(result, 2, 99))
        val still = frame(1, 100, iso = 800, ns = 8_000_000, intent = CaptureSampleIntent.STILL_CAPTURE)
        assertNull(freshPreviewExposurePair(PreviewExposureSamples(iso = still, shutter = still), 1, 150))
    }
}
