package dev.cinematic.p0.raw

/** In-place order statistic on an owned, finite preview buffer; interpolation stays with the caller. */
internal object PreviewFloatSelection {
    fun select(values: FloatArray, rank: Int, checkpoint: () -> Unit = {}): Float {
        require(rank in values.indices)
        var left = 0
        var right = values.lastIndex
        while (left < right) {
            checkpoint()
            // Median-of-three, then three-way partition so equal-valued images finish in one pass.
            val mid = left + (right - left) / 2
            if (values[left].compareTo(values[mid]) > 0) swap(values, left, mid)
            if (values[mid].compareTo(values[right]) > 0) swap(values, mid, right)
            if (values[left].compareTo(values[mid]) > 0) swap(values, left, mid)
            val pivot = values[mid]
            var lower = left
            var current = left
            var upper = right
            var examined = 0
            while (current <= upper) {
                if (examined++ and 1023 == 0) checkpoint()
                // Float.compareTo has the same -0.0 < +0.0 ordering as FloatArray.sort.
                val order = values[current].compareTo(pivot)
                when {
                    order < 0 -> { swap(values, lower++, current++) }
                    order > 0 -> { swap(values, current, upper--) }
                    else -> current++
                }
            }
            when {
                rank < lower -> right = lower - 1
                rank > upper -> left = upper + 1
                else -> { checkpoint(); return values[rank] }
            }
        }
        checkpoint()
        return values[rank]
    }

    private fun swap(values: FloatArray, a: Int, b: Int) {
        val value = values[a]
        values[a] = values[b]
        values[b] = value
    }
}
