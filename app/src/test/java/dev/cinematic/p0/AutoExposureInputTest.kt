package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class AutoExposureInputTest {
    private val choices = ExposureChoices(listOf(100, 200, 400, 800, 1600), listOf(
        ShutterStop("1/250 s", 4_000_000), ShutterStop("1/125 s", 8_000_000), ShutterStop("1/60 s", 16_666_666)))
    private val auto = ControlSnapshot(evIndex = 2, overrides = SensorOverrides(6, 2f), locks = AutoLocks(exposure = true))

    private fun frame(number: Long, time: Long, iso: Int = 410, ns: Long = 7_000_000,
        epoch: Int = 3, intent: CaptureSampleIntent = CaptureSampleIntent.PREVIEW) =
        CaptureSample(epoch, number, time, SensorValues(), SensorValues(iso, ns), intent = intent)

    @Test fun changingAutoStreamAcceptsManualInputThenCannotOverwriteItsPendingPair() {
        val queue = LatestLiveControlQueue()
        var samples = PreviewExposureSamples(epoch = 3)
        for (number in 1L..60L) {
            val now = number * 16
            samples = samples.observe(frame(number, now, iso = 350 + number.toInt()), 3)
            // Every newly observed frame is a usable first-manual source at the callback's clock.
            assertNotNull(liveExposureSelection(CaptureKey.ISO, 3, choices, auto, samples, 3, now))
        }
        val first = liveExposureSelection(CaptureKey.ISO, 3, choices, auto, samples, 3, 960)!!
        assertEquals(ManualExposure(800, 8_000_000), first.exposure)
        assertEquals(auto.overrides, first.overrides)
        assertEquals(2, first.evIndex)
        assertFalse(first.locks.exposure)
        queue.offer(3, first)
        val active = queue.take()!!
        for (number in 61L..120L) samples = samples.observe(frame(number, number * 16,
            iso = 1600, ns = 16_666_666), 3)
        val latest = liveExposureSelection(CaptureKey.SHUTTER, 0, choices, queue.intent!!, samples, 3, 1920)!!
        assertEquals(ManualExposure(800, 4_000_000), latest.exposure)
        queue.offer(3, latest)
        queue.complete(active)
        assertEquals(latest, queue.intent)
        assertEquals(latest, queue.take()?.controls)
    }

    @Test fun explicitlySelectingTheObservedIndexStillEntersManualExposure() {
        val samples = PreviewExposureSamples(epoch = 3).observe(frame(1, 100, iso = 400, ns = 8_000_000), 3)
        val manual = liveExposureSelection(CaptureKey.ISO, 2, choices, auto, samples, 3, 100)!!
        assertNull(auto.exposure)
        assertEquals(ManualExposure(400, 8_000_000), manual.exposure)
        assertNotEquals(auto, manual)
    }

    @Test fun firstManualInputRejectsOldFutureWrongEpochAndNonPreviewObservations() {
        val samples = PreviewExposureSamples(epoch = 3).observe(frame(1, 100), 3)
        assertNotNull(liveExposureSelection(CaptureKey.ISO, 3, choices, auto, samples, 3, 2100))
        assertNull(liveExposureSelection(CaptureKey.ISO, 3, choices, auto, samples, 3, 2101))
        assertNull(liveExposureSelection(CaptureKey.ISO, 3, choices, auto, samples, 3, 99))
        assertNull(liveExposureSelection(CaptureKey.ISO, 3, choices, auto, samples, 4, 100))
        val still = PreviewExposureSamples(epoch = 3).observe(frame(2, 200, intent = CaptureSampleIntent.STILL_CAPTURE), 3)
        assertNull(liveExposureSelection(CaptureKey.ISO, 3, choices, auto, still, 3, 200))
    }

    @Test fun establishedLogicalPairNeedsNoFreshAutoSeedAndKeepsIndependentIntent() {
        val manual = auto.copy(exposure = ManualExposure(800, 8_000_000))
        val empty = PreviewExposureSamples(epoch = 3)
        val changed = liveExposureSelection(CaptureKey.SHUTTER, 2, choices, manual, empty, 3, 9000)!!
        assertEquals(ManualExposure(800, 16_666_666), changed.exposure)
        assertEquals(manual.overrides, changed.overrides)
        assertEquals(manual.evIndex, changed.evIndex)
        assertNull(liveExposureSelection(CaptureKey.ISO, -1, choices, manual, empty, 3, 9000))
        assertNull(liveExposureSelection(CaptureKey.WB, 0, choices, manual, empty, 3, 9000))
    }
}
