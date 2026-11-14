package dev.cinematic.p0.jpegrender.original

import org.junit.Assert.*
import org.junit.Test

/** Pure synthetic failure injection: no app context, files, GPU or service access. */
class SafetyCleanupAndroidTest {
    @Test fun primaryFailureKeepsIdentityAndAllCleanupFailures() {
        val primary = AssertionError("primary")
        val close = IllegalStateException("close")
        val restore = IllegalArgumentException("restore")
        val visited = mutableListOf<Int>()
        preserveSafetyCleanup(primary,
            { visited += 1; throw close },
            { visited += 2; throw primary },
            { visited += 3; throw restore },
            { visited += 4 })
        assertEquals(listOf(1, 2, 3, 4), visited)
        assertArrayEquals(arrayOf<Throwable>(close, restore), primary.suppressed)
    }
    @Test fun cleanupFailureAfterSuccessThrowsFirstAndAttemptsRest() {
        val first = IllegalStateException("close")
        val second = AssertionError("restore")
        var lastRan = false
        val thrown = assertThrows(IllegalStateException::class.java) {
            preserveSafetyCleanup(null, { throw first }, { throw second }, { lastRan = true })
        }
        assertSame(first, thrown)
        assertArrayEquals(arrayOf<Throwable>(second), thrown.suppressed)
        assertTrue(lastRan)
    }
    @Test fun successfulCleanupRunsEveryAction() {
        var count = 0
        preserveSafetyCleanup(null, { count++ }, { count++ })
        assertEquals(2, count)
    }
    @Test fun cancellationRequiresExactOwnedIdentity() {
        data class Identity(val key: Int)
        val owned = Identity(1)
        requireOwnedSafetyIdentity(owned, owned)
        assertThrows(IllegalStateException::class.java) { requireOwnedSafetyIdentity(Identity(1), owned) }
        assertThrows(IllegalStateException::class.java) { requireOwnedSafetyIdentity(owned, null) }
    }
}