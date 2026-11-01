package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class SensorOverridesTest {
    private val caps = OverrideCapabilities(setOf(1, 5), setOf(0, 1, 3), 10f, "UNCALIBRATED")

    @Test fun independentControlsRetainEachOtherAndResetIsEmpty() {
        val initial = SensorOverrides(5, 2f, 1).validate(caps)
        assertEquals(SensorOverrides(1, 2f, 1), initial.copy(whiteBalance = 1).validate(caps))
        assertEquals(SensorOverrides(5, null, 1), initial.copy(focusDistance = null).validate(caps))
        assertTrue(SensorOverrides().validate(OverrideCapabilities()).isEmpty)
    }

    @Test fun manualFocusRejectsInvalidAndFixedLensButAllowsInfinityEndpoint() {
        assertEquals(0f, SensorOverrides(focusDistance = 0f).validate(caps).focusDistance)
        assertEquals(10f, SensorOverrides(focusDistance = 10f).validate(caps).focusDistance)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 10.01f).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { SensorOverrides(focusDistance = value).validate(caps) }
        }
        assertThrows(IllegalArgumentException::class.java) { SensorOverrides(focusDistance = 0f).validate(caps.copy(manualFocusMaximum = 0f)) }
    }

    @Test fun unsupportedSavedValuesFallbackIndependentlyWithoutChangingOriginalRequest() {
        val original = SensorOverrides(8, Float.NaN, 1)
        val reasons = mutableListOf<String>()
        val fallback = original.supportedFallback(caps, reasons::add)
        assertEquals(SensorOverrides(antiBanding = 1), fallback)
        assertEquals(2, reasons.size)
        assertEquals(8, original.whiteBalance)
        assertTrue(original.focusDistance!!.isNaN())
        assertThrows(IllegalArgumentException::class.java) { SensorOverrides(whiteBalance = 0).validate(caps) }
        assertThrows(IllegalArgumentException::class.java) { SensorOverrides(antiBanding = 2).validate(caps) }
    }

    @Test fun unrelatedEditDoesNotEraseUnsupportedSavedRequest() {
        val saved = mapOf("wb" to "8", "focus" to "15", "antiBanding" to "2")
        val effective = SensorOverrides(8, 15f, 2).supportedFallback(caps) {}
        assertTrue(effective.isEmpty)
        val afterFocusEdit = saved + effective.copy(focusDistance = 3f).persistencePatch(OverrideField.FOCUS)
        assertEquals(mapOf("wb" to "8", "focus" to "3.0", "antiBanding" to "2"), afterFocusEdit)
        val afterExplicitWbReset = afterFocusEdit + effective.persistencePatch(OverrideField.WHITE_BALANCE)
        assertEquals("", afterExplicitWbReset["wb"])
        assertEquals("2", afterExplicitWbReset["antiBanding"])
    }
}
