package dev.cinematic.p0.rawcapture

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RawHandoffAdmissionTest {
    @Test fun cameraAndWriterReservationsStopAdmissionAndLatchAnExistingAttempt() {
        val gate = RawHandoffAdmission()
        val camera = gate.newBlocker()
        assertNull(gate.tryStart())
        gate.block(camera, false)
        val attempt = gate.tryStart()!!
        assertFalse(gate.shouldStop(attempt))
        val writer = gate.newBlocker()
        assertTrue(gate.shouldStop(attempt))
        gate.remove(writer)
        assertTrue(gate.shouldStop(attempt))
        assertNull(gate.tryStart())
        gate.completeAfterResourcesClosed(attempt)
        assertNotNull(gate.tryStart())
    }

    @Test fun staleOrForeignOwnersCannotReleaseTheCurrentAttempt() {
        val gate = RawHandoffAdmission()
        val first = gate.tryStart()!!
        val other = RawHandoffAdmission().tryStart()!!
        assertThrows(IllegalArgumentException::class.java) { gate.completeAfterResourcesClosed(other) }
        gate.completeAfterResourcesClosed(first)
        val next = gate.tryStart()!!
        assertThrows(IllegalArgumentException::class.java) { gate.completeAfterResourcesClosed(first) }
        assertTrue(gate.shouldStop(first))
        assertFalse(gate.shouldStop(next))
    }

    @Test fun admissionAndCaptureReservationAreSerializedUnderConcurrentDispatch() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val gate = RawHandoffAdmission()
                val start = pool.submit(Callable { gate.tryStart() })
                val reserve = pool.submit(Callable { gate.newBlocker() })
                val attempt = start.get(5, TimeUnit.SECONDS)
                val blocker = reserve.get(5, TimeUnit.SECONDS)
                if (attempt != null) assertTrue(gate.shouldStop(attempt))
                assertNull(gate.tryStart())
                gate.remove(blocker)
                if (attempt != null) gate.completeAfterResourcesClosed(attempt)
                assertNotNull(gate.tryStart())
            }
        } finally { pool.shutdownNow() }
    }
}
