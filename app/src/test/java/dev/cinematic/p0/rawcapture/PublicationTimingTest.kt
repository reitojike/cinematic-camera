package dev.cinematic.p0.rawcapture

import dev.cinematic.p0.metrics.SaveTiming
import dev.cinematic.p0.metrics.SaveTimingKind
import dev.cinematic.p0.metrics.SaveTimingOutcome
import dev.cinematic.p0.metrics.SaveTimingRecord
import dev.cinematic.p0.metrics.SaveTimingStage
import dev.cinematic.p0.rawcapture.PublicationInterval.*
import org.junit.Assert.*
import org.junit.Test

class PublicationTimingTest {
    @Test fun subtractsNestedIntervalsAndSumsRepeatedCalls() {
        var now = 0L
        val collector = PublicationTiming { now }
        collector.scoped {
            PublicationTiming.measure(COPY) {
                now += 3
                PublicationTiming.measure(MEDIASTORE, subtractFromParent = true) { now += 5 }
                now += 7
            }
            PublicationTiming.measure(MEDIASTORE) { now += 2 }
            PublicationTiming.measure(JOURNAL) {
                now += 8
                PublicationTiming.measure(AUTHORITY, subtractFromParent = true) { now += 4 }
            }
            PublicationTiming.measure(AUTHORITY) { now += 6 }
        }
        PublicationTiming.measure(MEDIASTORE) { now += 999 } // Outside the advance scope.
        val rows = records(collector)
        for ((interval, expected) in mapOf(COPY to 10L, MEDIASTORE to 7L, JOURNAL to 8L, AUTHORITY to 10L)) {
            assertEquals(SaveTimingOutcome.COMPLETED, rows.getValue(interval.stage).outcome)
            assertEquals(expected, rows.getValue(interval.stage).nanos)
        }
    }

    @Test fun subtractsMediaStoreTimeFromPendingAndPublicRowVerification() {
        var now = 0L
        val collector = PublicationTiming { now }
        collector.scoped {
            PublicationTiming.measure(ROW_PENDING) {
                now += 3
                PublicationTiming.measure(MEDIASTORE, subtractFromParent = true) { now += 5 }
            }
            PublicationTiming.measure(ROW_PUBLIC) {
                PublicationTiming.measure(MEDIASTORE, subtractFromParent = true) { now += 11 }
                now += 7
            }
        }
        val rows = records(collector)
        for ((interval, expected) in mapOf(ROW_PENDING to 3L, ROW_PUBLIC to 7L, MEDIASTORE to 16L)) {
            val row = rows.getValue(interval.stage)
            assertEquals(SaveTimingOutcome.COMPLETED, row.outcome)
            assertEquals(expected, row.nanos)
        }
    }

    @Test fun missingChildClockMakesNormallyCompletedParentAndChildUnavailable() {
        var now = 0L
        var clockBroken = false
        val collector = PublicationTiming {
            check(!clockBroken) { "synthetic clock failure" }
            now
        }
        val returned = collector.scoped {
            PublicationTiming.measure(ROW_PUBLIC) {
                clockBroken = true
                PublicationTiming.measure(MEDIASTORE, subtractFromParent = true) {
                    clockBroken = false
                    now += 5
                    "verified"
                }
            }
        }
        assertEquals("verified", returned)
        val rows = records(collector)
        for (interval in listOf(ROW_PUBLIC, MEDIASTORE)) {
            val row = rows.getValue(interval.stage)
            assertEquals(SaveTimingOutcome.UNAVAILABLE, row.outcome)
            assertNull(row.nanos)
        }
    }

    @Test fun propagatesOriginalErrorAndInvalidatesParentWhenChildClockIsMissing() {
        var now = 0L
        var clockBroken = false
        val collector = PublicationTiming { if (clockBroken) error("clock"); now }
        val failure = OutOfMemoryError("synthetic")
        val actual = assertThrows(OutOfMemoryError::class.java) {
            collector.scoped {
                PublicationTiming.measure(ROW_PENDING) {
                    clockBroken = true
                    PublicationTiming.measure(MEDIASTORE, subtractFromParent = true) {
                        clockBroken = false
                        now += 7
                        throw failure
                    }
                }
            }
        }
        assertSame(failure, actual)
        collector.scoped {
            PublicationTiming.measure(MEDIASTORE) { now += 2 }
            PublicationTiming.measure(ORIGINAL) { now-- }
            PublicationTiming.measure(PRIVATE_JPEG) { null }
        }
        val rows = records(collector)
        for (interval in listOf(ROW_PENDING, MEDIASTORE)) {
            assertEquals(SaveTimingOutcome.INCOMPLETE, rows.getValue(interval.stage).outcome)
            assertNull(rows.getValue(interval.stage).nanos)
        }
        assertEquals(SaveTimingOutcome.UNAVAILABLE, rows.getValue(ORIGINAL.stage).outcome)
        assertNull(rows.getValue(ORIGINAL.stage).nanos)
        assertEquals(SaveTimingOutcome.COMPLETED, rows.getValue(PRIVATE_JPEG.stage).outcome)
        assertEquals(0L, rows.getValue(PRIVATE_JPEG.stage).nanos)
    }

    @Test fun caughtFailureContributesItsElapsedTime() {
        var now = 0L
        val collector = PublicationTiming { now }
        collector.scoped {
            PublicationTiming.measure(ROW_PUBLIC) { now += 3 }
            assertThrows(AssertionError::class.java) {
                PublicationTiming.measure(ROW_PUBLIC) {
                    now += 5
                    throw AssertionError("synthetic")
                }
            }
        }
        val row = records(collector).getValue(ROW_PUBLIC.stage)
        assertEquals(SaveTimingOutcome.INCOMPLETE, row.outcome)
        assertEquals(8L, row.nanos)
    }

    @Test fun durationOverflowDiscardsTheWholeKey() {
        var now = 0L
        val collector = PublicationTiming { now }
        collector.scoped {
            PublicationTiming.measure(JOURNAL) { now = Long.MAX_VALUE }
            now = 0L
            PublicationTiming.measure(JOURNAL) { now++ }
        }
        val row = records(collector).getValue(JOURNAL.stage)
        assertEquals(SaveTimingOutcome.UNAVAILABLE, row.outcome)
        assertNull(row.nanos)
    }

    private fun records(collector: PublicationTiming): Map<SaveTimingStage, SaveTimingRecord> {
        val records = mutableListOf<SaveTimingRecord>()
        val timing = SaveTiming.start(10, SaveTimingKind.SAVE, records::addAll)!!
        collector.emit(timing, published = false)
        assertTrue(records.isEmpty())
        timing.finish(SaveTimingOutcome.HOLD)
        val details = records.filter { it.stage.name.startsWith("PUB_") }
        assertEquals(8, details.size)
        return details.associateBy { it.stage }
    }
}
