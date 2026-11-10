package dev.cinematic.p0.gpu

import org.junit.Assert.*
import org.junit.Test

class GpuMemoryLedgerTest {
    @Test fun previewBudgetIs128MiBAndExportBudgetStays192MiB() {
        assertEquals(128L * 1024 * 1024, GpuMemoryLedger.PREVIEW_BUDGET)
        assertEquals(192L * 1024 * 1024, GpuMemoryLedger.EXPORT_BUDGET)
        assertEquals(GpuMemoryLedger.PREVIEW_BUDGET, GpuMemoryLedger().budgetBytes)
    }

    @Test fun peakRecordsSuccessfulReservationsAndSurvivesRelease() {
        val ledger = GpuMemoryLedger(100)
        assertEquals(0L, ledger.peakAllocatedBytes)
        ledger.reserve(60)
        ledger.reserve(40)
        assertEquals(100L, ledger.peakAllocatedBytes)
        ledger.release(60)
        assertEquals(40L, ledger.allocatedBytes)
        assertEquals(100L, ledger.peakAllocatedBytes)
        ledger.reserve(10)
        ledger.release(10)
        ledger.release(40)
        assertEquals(0L, ledger.allocatedBytes)
        assertEquals(0, ledger.textureCount)
        assertEquals(100L, ledger.peakAllocatedBytes)
    }

    @Test fun rejectedReservationsDoNotChangeThePeakOrCurrentAllocation() {
        val ledger = GpuMemoryLedger(100)
        ledger.reserve(40)
        assertThrows(GpuMemoryBudgetException::class.java) { ledger.reserve(61) }
        assertThrows(IllegalArgumentException::class.java) { ledger.reserve(0) }
        assertThrows(IllegalArgumentException::class.java) { ledger.reserve(-1) }
        assertEquals(40L, ledger.allocatedBytes)
        assertEquals(40L, ledger.peakAllocatedBytes)
        assertEquals(1, ledger.textureCount)
        ledger.release(40)
        assertThrows(GpuMemoryBudgetException::class.java) { ledger.reserve(Long.MAX_VALUE) }
        assertEquals(0L, ledger.allocatedBytes)
        assertEquals(0, ledger.textureCount)
        assertEquals(40L, ledger.peakAllocatedBytes)
    }
}
