package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ExposureValuePresentationTest {
    private val choices = ExposureChoices(listOf(100, 200, 400, 800, 1600, 3200),
        listOf(ShutterStop("1/160 s", 6_250_000), ShutterStop("1/125 s", 8_000_000), ShutterStop("1/100 s", 10_000_000)))
    private fun sample(intent: CaptureSampleIntent = CaptureSampleIntent.PREVIEW) = CaptureSample(
        epoch = 3, frame = 20, receivedAtMs = 100,
        request = SensorValues(100, 15_000_000_000, 0), actual = SensorValues(3100, 8_100_000, 0), intent = intent)
    private fun display(key: CaptureKey, value: CaptureSample? = sample(), now: Long = 101,
        options: ExposureChoices = choices) = exposureValuePresentation(key, value, 3, now, options)

    @Test fun actualPreviewValuesAreRoundedToChoicesForDisplayWithoutReplacingInputValues() {
        val sample = sample()
        assertEquals("≈3200", display(CaptureKey.ISO, sample).value)
        assertEquals("≈1/125", display(CaptureKey.SHUTTER, sample).value)
        assertEquals("s", display(CaptureKey.SHUTTER, sample).unit)
        assertEquals(3100, sample.actual.iso); assertEquals(8_100_000L, sample.actual.exposureNs)
        assertEquals(100, sample.request.iso)
        val compensated = exposureValuePresentation(CaptureKey.ISO, sample, 3, 101, choices, true)
        assertTrue(compensated.contentDescription.contains("補償プレビュー"))
        assertTrue(compensated.contentDescription.contains("撮影targetではなく"))
    }

    @Test fun missingWrongEpochNonPreviewAndUnknownIntentNeverInventValues() {
        assertEquals(ExposureValueFreshness.MISSING, display(CaptureKey.ISO, null).freshness)
        assertEquals(ExposureValueFreshness.WRONG_EPOCH, display(CaptureKey.ISO, sample().copy(epoch = 2)).freshness)
        for (intent in listOf(CaptureSampleIntent.STILL_CAPTURE, CaptureSampleIntent.UNKNOWN)) {
            val result = display(CaptureKey.ISO, sample(intent))
            assertEquals("—", result.value); assertEquals(ExposureValueFreshness.NOT_PREVIEW, result.freshness)
        }
        assertEquals(ExposureValueFreshness.FRESH, display(CaptureKey.ISO, sample(CaptureSampleIntent.VIDEO_RECORD)).freshness)
    }

    @Test fun ageBoundaryIsFiniteAndFutureOrStaleTimeIsNotTreatedAsCurrent() {
        assertEquals(ExposureValueFreshness.FRESH, display(CaptureKey.ISO, now = 2100).freshness)
        assertEquals(ExposureValueFreshness.STALE, display(CaptureKey.ISO, now = 2101).freshness)
        assertEquals("—", display(CaptureKey.ISO, now = 2101).value)
        assertEquals(ExposureValueFreshness.INVALID, display(CaptureKey.ISO, now = 99).freshness)
        assertEquals(ExposureValueFreshness.INVALID, display(CaptureKey.ISO, sample().copy(receivedAtMs = -1)).freshness)
    }

    @Test fun eachAxisRequiresOnlyItsOwnValidReportedValueAndChoices() {
        assertEquals(ExposureValueFreshness.INVALID, display(CaptureKey.ISO, sample().copy(actual = SensorValues(0, 8_000_000))).freshness)
        assertEquals(ExposureValueFreshness.INVALID, display(CaptureKey.SHUTTER, sample().copy(actual = SensorValues(100, -1))).freshness)
        assertEquals(ExposureValueFreshness.NO_CHOICES, display(CaptureKey.ISO, options = choices.copy(iso = emptyList())).freshness)
        assertEquals(ExposureValueFreshness.NO_CHOICES, display(CaptureKey.SHUTTER, options = choices.copy(shutter = emptyList())).freshness)
        assertEquals("≈3200", display(CaptureKey.ISO, sample().copy(actual = SensorValues(3100)),
            options = choices.copy(shutter = emptyList())).value)
        assertEquals(ExposureValueFreshness.INVALID, display(CaptureKey.WB).freshness)
    }

    @Test fun perKeyStateUsesLogicalCaptureDifferenceNotCompensatedRuntimePair() {
        val previous = ManualExposure(100, 8_000_000)
        val longerStill = ManualExposure(100, 15_000_000_000)
        assertFalse(logicalExposureChanged(CaptureKey.ISO, longerStill, previous))
        assertTrue(logicalExposureChanged(CaptureKey.SHUTTER, longerStill, previous))
        for (phase in listOf(ControlPhase.PENDING, ControlPhase.UNKNOWN, ControlPhase.REJECTED)) {
            assertFalse(exposureKeyFeedback(CaptureKey.ISO, longerStill, previous, phase).affected)
            assertEquals("", exposureKeyFeedback(CaptureKey.ISO, longerStill, previous, phase).stateTag)
            assertTrue(exposureKeyFeedback(CaptureKey.SHUTTER, longerStill, previous, phase).affected)
        }
        val isoOnly = previous.copy(iso = 800)
        assertTrue(logicalExposureChanged(CaptureKey.ISO, isoOnly, previous))
        assertFalse(logicalExposureChanged(CaptureKey.SHUTTER, isoOnly, previous))
    }

    @Test fun autoManualTransitionAffectsBothFieldsButAcknowledgedEqualityDoesNotStayPending() {
        val manual = ManualExposure(100, 8_000_000)
        for (key in listOf(CaptureKey.ISO, CaptureKey.SHUTTER)) {
            assertTrue(logicalExposureChanged(key, manual, null))
            assertTrue(logicalExposureChanged(key, null, manual))
            assertFalse(logicalExposureChanged(key, null, null))
            assertFalse(exposureKeyFeedback(key, manual, manual, ControlPhase.PENDING).affected)
            assertEquals(SensorControlTone.MUTED, exposureKeyFeedback(key, manual, manual, ControlPhase.ACCEPTED).tone)
        }
    }
}
