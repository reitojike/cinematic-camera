package dev.cinematic.p0.gpu.analysis

/** Multiple sorted ranks in a single traversal. Counts are unsigned GL uint values. */
internal object HistogramRanks {
    const val BINS = 65536
    data class Selection(val bin: Int, val before: Long, val count: Long)

    fun select(counts: IntArray, start: Int, ranks: IntArray, expectedCount: Long,
        finite: Boolean = false): List<Selection> {
        require(start >= 0 && start <= counts.size - BINS)
        require(ranks.isNotEmpty() && ranks.all { it >= 0 } && (1 until ranks.size).all { ranks[it - 1] <= ranks[it] })
        val result = ArrayList<Selection>(ranks.size)
        var before = 0L
        for (bin in 0 until BINS) {
            val size = counts[start + bin].toLong() and 0xffffffffL
            if (finite && (bin < 128 || bin >= 65408)) require(size == 0L) { "Quantiles require finite Float32 values" }
            while (result.size < ranks.size && ranks[result.size] < before + size) {
                result += Selection(bin, before, size)
            }
            before += size
        }
        check(before == expectedCount) { "Histogram sample count differs" }
        check(result.size == ranks.size) { "Histogram rank is absent" }
        return result
    }
}
