package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class AutoDevelopPolicyTest {
    @Test fun allUnknownConditionsBlockEveryGate() {
        assertEquals(AutoDevelopBlocker.entries.toSet(), AutoDevelopPolicy.blockers(AutoDevelopConditions()))
    }

    @Test fun everyCombinationRequiresAllEightConditionsToBeConfirmed() {
        val values = listOf<Boolean?>(null, false, true)
        var allowed = 0
        repeat(6561) { encoded -> // 3^8, including every unknown and unsafe combination.
            var remaining = encoded
            val flags = List(8) { values[remaining.mod(3)].also { remaining /= 3 } }
            val conditions = AutoDevelopConditions(
                foreground = flags[0], cameraSessionInUse = flags[1], recordingOrFinalizing = flags[2],
                rebindInProgress = flags[3], thermalAllowed = flags[4], storageAllowed = flags[5],
                rendererAvailable = flags[6], memoryBudgetConfirmed = flags[7],
            )
            val expected = flags[0] == true && flags[1] == false && flags[2] == false &&
                flags[3] == false && flags[4] == true && flags[5] == true &&
                flags[6] == true && flags[7] == true
            val canStart = AutoDevelopPolicy.blockers(conditions).isEmpty()
            assertEquals("combination $encoded", expected, canStart)
            if (canStart) allowed++
        }
        assertEquals(1, allowed)
    }

    @Test fun eachUnsafeOrUnknownGateReportsItsOwnReason() {
        val good = permittedConditions()
        val cases = listOf(
            AutoDevelopBlocker.FOREGROUND to good.copy(foreground = false),
            AutoDevelopBlocker.CAMERA_SESSION to good.copy(cameraSessionInUse = true),
            AutoDevelopBlocker.RECORDING_OR_FINALIZING to good.copy(recordingOrFinalizing = true),
            AutoDevelopBlocker.REBIND to good.copy(rebindInProgress = true),
            AutoDevelopBlocker.THERMAL to good.copy(thermalAllowed = null),
            AutoDevelopBlocker.STORAGE to good.copy(storageAllowed = false),
            AutoDevelopBlocker.RENDERER to good.copy(rendererAvailable = null),
            AutoDevelopBlocker.MEMORY to good.copy(memoryBudgetConfirmed = false),
        )
        cases.forEach { (reason, conditions) ->
            assertEquals(setOf(reason), AutoDevelopPolicy.blockers(conditions))
        }
    }
}

internal fun permittedConditions() = AutoDevelopConditions(
    foreground = true, cameraSessionInUse = false, recordingOrFinalizing = false,
    rebindInProgress = false, thermalAllowed = true, storageAllowed = true,
    rendererAvailable = true, memoryBudgetConfirmed = true,
)
