package dev.cinematic.p0.rawcapture

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class JpegOperationOwnersTest {
    @Test fun manualAndAutoCannotOwnSameLogicalPublicationEvenAfterResourcesClose() {
        val registry = JpegOperationOwnerRegistry()
        val manual = Any()
        val auto = Any()
        registry.claim("synthetic-root", "capture", 1, manual)
        registry.claim("synthetic-root", "capture", 1, manual)
        assertThrows(CaptureJournalConflict::class.java) { registry.claim("synthetic-root", "capture", 1, auto) }
        registry.claim("synthetic-root", "capture", 2, auto)
        registry.claim("synthetic-root", "different-edit", 1, auto)
    }
    @Test fun competingOwnersCannotBothAcquireTheSameOperation() {
        val registry = JpegOperationOwnerRegistry()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map { executor.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                try { registry.claim("synthetic-root", "capture", 1, Any()); true }
                catch (_: CaptureJournalConflict) { false }
            } }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally { start.countDown(); executor.shutdownNow() }
    }
}
