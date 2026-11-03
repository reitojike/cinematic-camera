package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class SensorShortcutTest {
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
