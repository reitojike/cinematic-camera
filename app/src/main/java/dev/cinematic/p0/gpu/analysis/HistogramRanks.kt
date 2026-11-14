package dev.cinematic.p0.gpu.analysis

/** Selects ranks in one traversal, returning selections in caller order. Counts are GL uint. */
internal object HistogramRanks {
    const val BINS = 65536
    data class Selection(val bin: Int, val before: Long, val count: Long)

    fun select(counts: IntArray, start: Int, ranks: IntArray, expectedCount: Long,
        finite: Boolean = false): List<Selection> {
        require(start >= 0 && start <= counts.size - BINS)
        require(ranks.isNotEmpty() && ranks.all { it >= 0 })
        // Two samples produce p50/p92 orders [0, 1, 0, 1]. Preserve their interpolation pairs.
        val order = ranks.indices.sortedBy { ranks[it] }
        val result = arrayOfNulls<Selection>(ranks.size)
        var selected = 0
        var before = 0L
        for (bin in 0 until BINS) {
            val size = counts[start + bin].toLong() and 0xffffffffL
            if (finite && (bin < 128 || bin >= 65408)) require(size == 0L) { "Quantiles require finite Float32 values" }
            while (selected < ranks.size && ranks[order[selected]] < before + size) {
                result[order[selected++]] = Selection(bin, before, size)
            }
            before += size
        }
        check(before == expectedCount) { "Histogram sample count differs" }
        check(selected == ranks.size) { "Histogram rank is absent" }
        return result.map { checkNotNull(it) }
    }
}
