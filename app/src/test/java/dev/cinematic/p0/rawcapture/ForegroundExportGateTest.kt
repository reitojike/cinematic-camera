package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class ForegroundExportGateTest {
    @Test fun duplicateDeliveryAndDifferentSavesNeverCreateSecondOwner() {
        val gate = ForegroundExportGate<Any>()
        val owner = Any()
        val first = requireNotNull(gate.reserve(owner, "first"))
        assertNull(gate.reserve(owner, "duplicate"))
        assertNull(gate.reserve(Any(), "other"))
        assertTrue(gate.begin(first))
        assertFalse(gate.begin(first))
        assertSame(first, gate.find("first"))
        assertNull(gate.find("unknown"))
    }
    @Test fun cancelIsNotClosureAndUnconfirmedClosureKeepsOneOwner() {
        val gate = ForegroundExportGate<Any>()
        val first = requireNotNull(gate.reserve(Any(), "first"))
        assertTrue(gate.stop(first))
        assertFalse(gate.begin(first))
        assertFalse(gate.finish(first, false))
        assertNull(gate.reserve(Any(), "other"))
        assertTrue(gate.finish(first, true))
        assertNotNull(gate.reserve(Any(), "other"))
    }
    @Test fun oldAndForeignCompletionsCannotReleaseNewOwner() {
        val gate = ForegroundExportGate<Any>()
        val old = requireNotNull(gate.reserve(Any(), "old"))
        gate.finish(old, true)
        val next = requireNotNull(gate.reserve(Any(), "next"))
        val foreign = requireNotNull(ForegroundExportGate<Any>().reserve(Any(), "next"))
        assertFalse(gate.stop(old))
        assertFalse(gate.finish(old, true))
        assertFalse(gate.finish(foreign, true))
        assertFalse(next.stopRequested)
        assertSame(next, gate.active())
    }
    @Test fun processRestartHasNoAuthorityToRenderFromOldIntentToken() {
        val before = ForegroundExportGate<Any>()
        before.reserve(Any(), "persisted-service-intent")
        val after = ForegroundExportGate<Any>()
        assertNull(after.find("persisted-service-intent"))
        assertNull(after.find(null))
    }
}
