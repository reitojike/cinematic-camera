package dev.cinematic.p0.gpu

import dev.cinematic.p0.gpu.analysis.HistogramRanks
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class HistogramRanksTest {
    @Test fun singlePassMatchesIndependentRepeatedScanIncludingDuplicateRanks() {
        val random = Random(412)
        repeat(60) {
            val counts = IntArray(HistogramRanks.BINS * 2)
            val start = HistogramRanks.BINS
            repeat(1000) { counts[start + random.nextInt(HistogramRanks.BINS)]++ }
            counts[0] = -1 // Other/stale workspace regions must never enter the scan.
            val ranks = intArrayOf(0, 499, 500, 919, 919, 920, 999)
            val actual = HistogramRanks.select(counts, start, ranks, 1000)
            ranks.forEachIndexed { index, rank ->
                var before = 0L
                var expected = -1
                for (bin in 0 until HistogramRanks.BINS) {
                    val count = counts[start + bin].toLong() and 0xffffffffL
                    if (rank < before + count) { expected = bin; break }
                    before += count
                }
                assertEquals(expected, actual[index].bin)
                assertEquals(before, actual[index].before)
            }
        }
    }
    @Test fun unsignedCountsAndBoundaryBinsAreNotSignedOrRounded() {
        val counts = IntArray(HistogramRanks.BINS)
        counts[0] = -1
        val selected = HistogramRanks.select(counts, 0, intArrayOf(0, Int.MAX_VALUE), 0xffffffffL)
        assertEquals(listOf(0, 0), selected.map { it.bin })
        assertEquals(0xffffffffL, selected[1].count)
        counts.fill(0); counts[128] = 2; counts[65407] = 3
        assertEquals(listOf(128, 128, 65407, 65407),
            HistogramRanks.select(counts, 0, intArrayOf(0, 1, 2, 4), 5, finite = true).map { it.bin })
    }
    @Test fun invalidRanksCountsAndNonFiniteBinsFailEvenAfterSelectedRanks() {
        val counts = IntArray(HistogramRanks.BINS).apply { this[200] = 2 }
        assertThrows(IllegalStateException::class.java) { HistogramRanks.select(counts, 0, intArrayOf(0), 3) }
        assertThrows(IllegalStateException::class.java) { HistogramRanks.select(counts, 0, intArrayOf(2), 2) }
        assertThrows(IllegalArgumentException::class.java) { HistogramRanks.select(counts, 0, intArrayOf(1, 0), 2) }
        assertThrows(IllegalArgumentException::class.java) { HistogramRanks.select(counts, 1, intArrayOf(0), 2) }
        for (bin in listOf(0, 127, 65408, 65535)) {
            counts[bin] = 1
            assertThrows(IllegalArgumentException::class.java) { HistogramRanks.select(counts, 0, intArrayOf(0), 3, finite = true) }
            counts[bin] = 0
        }
    }
}
