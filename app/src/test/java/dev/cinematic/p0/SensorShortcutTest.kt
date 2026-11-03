package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class SensorShortcutTest {
    @Test fun autofocusShortcutAllowsAlreadyAutoWithoutRedundantRequest() {
        var manual = false
        var calls = 0
        val action: () -> Unit = { calls++ }
        assertTrue(dispatchFocusAuto(true, { true }, { manual }, action))
        assertEquals(0, calls)
        manual = true
        assertTrue(dispatchFocusAuto(true, { true }, { manual }, action))
        assertEquals(1, calls)
        manual = false
        assertTrue(dispatchFocusAuto(true, { true }, { manual }, action))
        assertEquals(1, calls)
        assertFalse(dispatchFocusAuto(false, { true }, { true }, action))
        assertFalse(dispatchFocusAuto(true, { false }, { true }, action))
        assertEquals(1, calls)
    }
    @Test fun resetAndAutoWordsFitTheRotatedHeaderWithoutEllipsisAtLargeFontScales() {
        for (label in listOf("RESET", "AUTO")) for (fontScale in listOf(1f, 1.3f, 2f)) {
            // Production uses TextMeasurer; this fixture exercises the same fitting policy in pixels.
            fun measuredWidth(size: Float) = kotlin.math.ceil(label.length * size * fontScale * 0.7f).toInt()
            val fitted = controlValueFontSize(12f) { measuredWidth(it) <= 44 }
            assertTrue(fitted > 0f)
            assertTrue(fitted <= 12f)
            assertTrue(measuredWidth(fitted) <= 44)
            if (measuredWidth(12f) <= 44) assertEquals(12f, fitted, 0f)
            else assertTrue(measuredWidth(fitted + 0.01f) > 44)
        }
    }

    @Test fun zeroShortcutAcceptsOnlyAReportedNeutralIndexAndValidStep() {
        assertTrue(evZeroShortcutSupported(-12..12, 1.0 / 3))
        assertTrue(evZeroShortcutSupported(0..0, 1.0))
        assertFalse(evZeroShortcutSupported(1..12, 1.0 / 3))
        assertFalse(evZeroShortcutSupported(null, 1.0))
        for (step in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertFalse(evZeroShortcutSupported(-12..12, step))
    }

    @Test fun farEvValuesReturnToZeroWithOneRequestAndPreserveNewerIntentAcrossOldAck() {
        for (start in listOf(-12, 12)) {
            val queue = LatestLiveControlQueue()
            queue.offer(2, ControlSnapshot(evIndex = start, overrides = SensorOverrides(6, 2f)))
            val old = queue.take()!!
            var requests = 0
            assertTrue(dispatchSensorShortcut(true, { true }) {
                requests++
                queue.offer(2, queue.intent!!.copy(evIndex = 0))
            })
            assertEquals(1, requests)
            queue.complete(old)
            assertEquals(0, queue.intent?.evIndex)
            assertEquals(SensorOverrides(6, 2f), queue.take()?.controls?.overrides)
        }
    }

    @Test fun autofocusShortcutReleasesNearOrInfinityMfWithoutChangingOtherControls() {
        for (focus in listOf(0f, 7f)) {
            val queue = LatestLiveControlQueue()
            val target = ControlSnapshot(ManualExposure(800, 8_000_000), 2, SensorOverrides(6, focus, 2))
            queue.offer(3, target)
            val old = queue.take()!!
            var requests = 0
            assertTrue(dispatchSensorShortcut(true, { true }) {
                requests++
                queue.offer(3, queue.intent!!.copy(overrides = queue.intent!!.overrides.copy(focusDistance = null)))
            })
            assertEquals(1, requests)
            queue.complete(old)
            assertEquals(target.copy(overrides = target.overrides.copy(focusDistance = null)), queue.intent)
        }
    }

    @Test fun disabledOrNewlyBlockedSessionNeverDispatchesShortcut() {
        var permitted = true
        var requests = 0
        val currentGuard = { permitted }
        assertFalse(dispatchSensorShortcut(false, currentGuard) { requests++ })
        // The button was composed enabled, then recording/error/epoch ownership changed.
        permitted = false
        assertFalse(dispatchSensorShortcut(true, currentGuard) { requests++ })
        assertEquals(0, requests)
        permitted = true
        assertTrue(dispatchSensorShortcut(true, currentGuard) { requests++ })
        assertEquals(1, requests)
    }
}
