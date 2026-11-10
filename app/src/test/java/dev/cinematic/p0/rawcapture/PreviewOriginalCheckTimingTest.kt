package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test

class PreviewOriginalCheckTimingTest {
    @Test fun routesAreAnonymousAndMeasureOnlyTheCheck() {
        for (route in PreviewOriginalCheckTiming.Route.entries) {
            var now = 10L
            var calls = 0
            val lines = mutableListOf<String>()
            val value = PreviewOriginalCheckTiming.measure(route, true, { now }, lines::add) {
                calls++; now += 7; 42
            }
            assertEquals(42, value)
            assertEquals(1, calls)
            val elapsed = if (route == PreviewOriginalCheckTiming.Route.SKIPPED_DRAFT) 0 else 7
            assertEquals(listOf("route=$route outcome=SUCCESS checkNs=$elapsed"), lines)
        }
    }

    @Test fun disabledTimingDoesNotReadClockOrSink() {
        assertEquals(7, PreviewOriginalCheckTiming.measure(PreviewOriginalCheckTiming.Route.METADATA,
            false, { error("clock") }, { error("sink") }) { 7 })
    }

    @Test fun failedCheckIsRethrownAndLoggedWithoutChangingItsIdentity() {
        val failure = IllegalStateException("synthetic")
        val lines = mutableListOf<String>()
        val actual = assertThrows(IllegalStateException::class.java) {
            PreviewOriginalCheckTiming.measure(PreviewOriginalCheckTiming.Route.SHA_SESSION,
                true, { 10L }, lines::add) { throw failure }
        }
        assertSame(failure, actual)
        assertEquals(listOf("route=SHA_SESSION outcome=FAILED checkNs=0"), lines)
    }

    @Test fun diagnosticFailuresNeverMaskResultOrVerificationFailure() {
        for (clockFails in listOf(false, true)) {
            assertEquals(3, PreviewOriginalCheckTiming.measure(PreviewOriginalCheckTiming.Route.METADATA,
                true, { if (clockFails) throw OutOfMemoryError("clock") else 0L },
                { throw OutOfMemoryError("sink") }) { 3 })
            val failure = IllegalStateException("check")
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                PreviewOriginalCheckTiming.measure(PreviewOriginalCheckTiming.Route.METADATA,
                    true, { if (clockFails) throw OutOfMemoryError("clock") else 0L },
                    { throw OutOfMemoryError("sink") }) { throw failure }
            })
        }
    }
}
