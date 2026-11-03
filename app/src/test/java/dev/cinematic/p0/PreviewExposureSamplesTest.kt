package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class PreviewExposureSamplesTest {
    private val choices = ExposureChoices(listOf(100, 200, 400), listOf(ShutterStop("1/100 s", 10_000_000)))
    private fun frame(number: Long, time: Long, actual: SensorValues = SensorValues(100, 10_000_000),
        intent: CaptureSampleIntent = CaptureSampleIntent.PREVIEW, epoch: Int = 3) =
        CaptureSample(epoch, number, time, SensorValues(), actual, intent = intent)
    private fun shown(state: PreviewExposureSamples, key: CaptureKey, now: Long, epoch: Int = 3) =
        exposureValuePresentation(key, state.sample(key), epoch, now, choices)

    @Test fun normalUpdatesAndInterleavedStillUnknownOrMissingFieldsDoNotBlankFreshAxes() {
        var state = PreviewExposureSamples().observe(frame(1, 100), 3)
        for (intent in listOf(CaptureSampleIntent.STILL_CAPTURE, CaptureSampleIntent.UNKNOWN)) {
            state = state.observe(frame(2, 200, SensorValues(400, 10_000_000), intent), 3)
            assertEquals("約100", shown(state, CaptureKey.ISO, 200).value)
        }
        state = state.observe(frame(3, 600, SensorValues(exposureNs = 10_000_000)), 3)
        assertEquals("約100", shown(state, CaptureKey.ISO, 600).value)
        assertEquals("約1/100", shown(state, CaptureKey.SHUTTER, 600).value)
        assertEquals(100L, state.iso!!.receivedAtMs)
        assertEquals(600L, state.shutter!!.receivedAtMs)
        state = state.observe(frame(4, 1100, SensorValues(200), CaptureSampleIntent.VIDEO_RECORD), 3)
        assertEquals("約200", shown(state, CaptureKey.ISO, 1100).value)
        assertEquals("約1/100", shown(state, CaptureKey.SHUTTER, 1100).value)
        state = state.observe(frame(5, 1600, SensorValues(400, 10_000_000)), 3)
        assertEquals("約400", shown(state, CaptureKey.ISO, 1600).value)
    }

    @Test fun missingAxisCannotBorrowAnotherAxesTimestampAndExpiresAtOriginalBoundary() {
        var state = PreviewExposureSamples().observe(frame(1, 100), 3)
        state = state.observe(frame(2, 1600, SensorValues(0, 10_000_000)), 3)
        assertEquals(ExposureValueFreshness.FRESH, shown(state, CaptureKey.ISO, 2100).freshness)
        assertEquals(ExposureValueFreshness.STALE, shown(state, CaptureKey.ISO, 2101).freshness)
        assertEquals("—", shown(state, CaptureKey.ISO, 2101).value)
        assertEquals(ExposureValueFreshness.FRESH, shown(state, CaptureKey.SHUTTER, 2101).freshness)
        val neverReceived = PreviewExposureSamples().observe(frame(1, 100, SensorValues(exposureNs = 10_000_000)), 3)
        assertEquals(ExposureValueFreshness.MISSING, shown(neverReceived, CaptureKey.ISO, 101).freshness)
    }

    @Test fun newEpochAndOutOfOrderFramesCannotResurrectOldValues() {
        val state = PreviewExposureSamples().observe(frame(10, 100), 3)
        assertEquals(state, state.observe(frame(9, 200, SensorValues(400)), 3))
        assertEquals(state, state.observe(frame(100, 200, SensorValues(400), epoch = 2), 3))
        assertEquals(ExposureValueFreshness.WRONG_EPOCH, shown(state, CaptureKey.ISO, 200, epoch = 4).freshness)
        val reset = state.observe(frame(100, 200, epoch = 3), 4)
        assertNull(reset.iso); assertNull(reset.shutter)
        assertEquals(ExposureValueFreshness.MISSING, shown(reset, CaptureKey.ISO, 200, epoch = 4).freshness)
        val current = reset.observe(frame(1, 300, SensorValues(200, 10_000_000), epoch = 4), 4)
        assertEquals("約200", shown(current, CaptureKey.ISO, 301, epoch = 4).value)
    }
}
