package dev.cinematic.p0.raw

/** In-place order statistic on an owned, finite preview buffer; interpolation stays with the caller. */
internal object PreviewFloatSelection {
    fun select(values: FloatArray, rank: Int, checkpoint: () -> Unit = {}): Float {
        require(rank in values.indices)
        var left = 0
        var right = values.lastIndex
        while (left < right) {
            checkpoint()
            // Median-of-three with inward scans preserves the useful order of smooth gradients.
            val mid = left + (right - left) / 2
            if (values[left].compareTo(values[mid]) > 0) swap(values, left, mid)
            if (values[mid].compareTo(values[right]) > 0) swap(values, mid, right)
            if (values[left].compareTo(values[mid]) > 0) swap(values, left, mid)
            val pivot = values[mid]
            var lower = left
            var upper = right
            var examined = 0
            while (lower <= upper) {
                if (examined++ and 1023 == 0) checkpoint()
                // Float.compareTo has the same -0.0 < +0.0 ordering as FloatArray.sort.
                while (lower <= upper && values[lower].compareTo(pivot) < 0) {
                    lower++
                    if (examined++ and 1023 == 0) checkpoint()
                }
                while (lower <= upper && values[upper].compareTo(pivot) > 0) {
                    upper--
                    if (examined++ and 1023 == 0) checkpoint()
                }
                if (lower <= upper) swap(values, lower++, upper--)
            }
            when {
                rank <= upper -> right = upper
                rank >= lower -> left = lower
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
