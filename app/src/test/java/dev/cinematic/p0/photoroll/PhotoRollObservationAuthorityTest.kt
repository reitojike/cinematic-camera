package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import org.junit.Assert.*
import org.junit.Test

class PhotoRollObservationAuthorityTest {
    private val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true)

    @Test fun oldActivityCannotCancelOrRestoreConditionsAfterReplacement() {
        val authority = PhotoRollObservationAuthority()
        val old = Any(); val newer = Any()
        val received = mutableListOf<AutoDevelopConditions>()
        val publish: (AutoDevelopConditions) -> Unit = { received.add(it) }
        authority.foreground(old, true, publish)
        authority.foreground(newer, true, publish)
        authority.observed(newer, allowed, publish)
        authority.foreground(old, false, publish)
        authority.observed(old, allowed.copy(memoryBudgetConfirmed = false), publish)
        assertEquals(allowed, received.last())
        assertEquals(3, received.size)
    }

    @Test fun pauseRevokesPendingObservationUntilAnotherExplicitForeground() {
        val authority = PhotoRollObservationAuthority()
        val owner = Any()
        val received = mutableListOf<AutoDevelopConditions>()
        val publish: (AutoDevelopConditions) -> Unit = { received.add(it) }
        authority.foreground(owner, true, publish)
        authority.foreground(owner, false, publish)
        authority.observed(owner, allowed, publish)
        assertEquals(listOf(AutoDevelopConditions(foreground = true), AutoDevelopConditions(foreground = false)), received)
        authority.foreground(owner, true, publish)
        authority.observed(owner, allowed, publish)
        assertEquals(allowed, received.last())
    }
}
