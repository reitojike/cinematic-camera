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
            val ranks = intArrayOf(919, 0, 999, 500, 919, 920, 499)
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
        assertThrows(IllegalArgumentException::class.java) { HistogramRanks.select(counts, 0, intArrayOf(-1, 0), 2) }
        assertThrows(IllegalArgumentException::class.java) { HistogramRanks.select(counts, 1, intArrayOf(0), 2) }
        for (bin in listOf(0, 127, 65408, 65535)) {
            counts[bin] = 1
            assertThrows(IllegalArgumentException::class.java) { HistogramRanks.select(counts, 0, intArrayOf(0), 3, finite = true) }
            counts[bin] = 0
        }
    }

    @Test fun twoPixelHighAndLowSelectionsPreserveQuantilePairsAndSignedZero() {
        val cases = listOf(
            floatArrayOf(-0f, +0f), floatArrayOf(+0f, -0f),
            floatArrayOf(1f, Float.fromBits(1f.toRawBits() + 1)),
            floatArrayOf(-2f, 3f), floatArrayOf(1f, 1f), floatArrayOf(-0f, -0f)
        )
        for (values in cases) {
            // Both 2x1 and 1x2 have the same flattened histogram and rank sequence.
            val orders = intArrayOf(0, 1, 0, 1)
            val keys = values.map { value ->
                val bits = value.toRawBits()
                if (bits < 0) bits.inv() else bits xor Int.MIN_VALUE
            }
            val highCounts = IntArray(HistogramRanks.BINS)
            keys.forEach { highCounts[it ushr 16]++ }
            val high = HistogramRanks.select(highCounts, 0, orders, 2, finite = true)
            val reconstructed = IntArray(4)
            for (bin in high.map { it.bin }.distinct()) {
                val indices = orders.indices.filter { high[it].bin == bin }
                val lowCounts = IntArray(HistogramRanks.BINS)
                keys.filter { it ushr 16 == bin }.forEach { lowCounts[it and 65535]++ }
                val lowRanks = indices.map { orders[it] - high[it].before.toInt() }.toIntArray()
                val low = HistogramRanks.select(lowCounts, 0, lowRanks, high[indices.first()].count)
                indices.forEachIndexed { index, original ->
                    reconstructed[original] = (bin shl 16) or low[index].bin
                }
            }
            val sorted = keys.sortedWith { a, b -> Integer.compareUnsigned(a, b) }
            assertArrayEquals(orders.map { sorted[it] }.toIntArray(), reconstructed)
            fun decode(key: Int): Double = Float.fromBits(
                if (key < 0) key xor Int.MIN_VALUE else key.inv()
            ).toDouble()
            for ((index, fraction) in doubleArrayOf(0.5, 0.92).withIndex()) {
                val lower = decode(reconstructed[index * 2])
                val upper = decode(reconstructed[index * 2 + 1])
                val expectedLower = decode(sorted[0])
                val expectedUpper = decode(sorted[1])
                assertEquals((expectedLower + (expectedUpper - expectedLower) * fraction).toRawBits(),
                    (lower + (upper - lower) * fraction).toRawBits())
            }
        }
    }
}
