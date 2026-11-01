package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class AutoLocksTest {
    @Test fun locksRequireAdvertisedSupportAndActiveAutomaticAlgorithm() {
        val both = AutoLocks(true, true)
        assertEquals(both, both.validate(LockCapabilities(true, true), false, null))
        assertEquals(both, both.validate(LockCapabilities(true, true), false, 1))
        assertThrows(IllegalArgumentException::class.java) { both.validate(LockCapabilities(false, true), false, 1) }
        assertThrows(IllegalArgumentException::class.java) { both.validate(LockCapabilities(true, false), false, 1) }
        assertThrows(IllegalArgumentException::class.java) { both.validate(LockCapabilities(true, true), true, 1) }
        assertThrows(IllegalArgumentException::class.java) { both.validate(LockCapabilities(true, true), false, 5) }
        assertTrue(AutoLocks().validate(LockCapabilities(), true, 5).isEmpty)
    }

    @Test fun manualAndPresetTransitionsReleaseOnlyTheInapplicableLock() {
        val both = AutoLocks(true, true)
        assertEquals(AutoLocks(false, true), both.forControls(true, 1))
        assertEquals(AutoLocks(true, false), both.forControls(false, 5))
        assertTrue(both.forControls(true, 5).isEmpty)
        assertEquals(both, both.forControls(false, null))
        assertTrue(AutoLocks().forControls(false, 1).isEmpty) // No implicit restoration.
    }
}
