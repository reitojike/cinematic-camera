package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class ControlScaleTest {
    @Test fun middleAndEdgesKeepSelectionInCentre() {
        assertEquals(listOf(0, 1, 2, 3, 4), controlScaleWindow(5, 2).slots)
        assertEquals(listOf(null, null, 0, 1, 2), controlScaleWindow(5, 0).slots)
        assertEquals(listOf(2, 3, 4, null, null), controlScaleWindow(5, 4).slots)
    }
    @Test fun emptyAndInvalidStatesCannotGenerateRequests() {
        listOf(0 to 0, -1 to 0, 5 to -1, 5 to 5).forEach { (count, index) ->
            assertEquals(listOf(null, null, null, null, null), controlScaleWindow(count, index).slots)
            assertNull(controlScaleMove(count, index, 1))
        }
    }
    @Test fun singleChoiceHasNoInventedNeighbours() {
        assertEquals(listOf(null, null, 0, null, null), controlScaleWindow(1, 0).slots)
        assertEquals(0, controlScaleMove(1, 0, 100))
        assertEquals(0, controlScaleMove(1, 0, -100))
    }
    @Test fun dragClampsToRangeWithoutIntegerOverflow() {
        assertEquals(4, controlScaleMove(5, 3, Int.MAX_VALUE))
        assertEquals(0, controlScaleMove(5, 3, Int.MIN_VALUE))
        assertEquals(3, controlScaleMove(5, 2, 1))
    }
}
