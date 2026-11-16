package dev.cinematic.p0.gpu.export

/** CPU wall intervals, not GPU timer queries. Reader intervals overlap the GL caller. */
internal enum class GpuExportDetail {
    INPUT_WAIT, INPUT_READ_CONVERT, INPUT_PACK, UPLOAD, FENCE_WAIT,
    READBACK, ROW_CONVERT, ROW_CONSUME,
    // Reader-local intermediates; never serialized.
    READER_TOTAL, INPUT_CALLBACK, INPUT_FREE_WAIT,
}

/** Thread-confined counters. A reader is merged only after the existing join has drained it. */
internal class GpuExportWorkTimings(private val clock: (() -> Long)? = null) {
    internal data class Sample(val nanos: Long?, val completed: Boolean)
    private val sums = LongArray(GpuExportDetail.entries.size)
    private val seen = BooleanArray(sums.size)
    private val valid = BooleanArray(sums.size) { true }
    private val complete = BooleanArray(sums.size) { true }

    // Snapshot immediately after each read: nested intervals must not overwrite validity.
    @PublishedApi internal var lastReadValid = true
        private set

    @PublishedApi internal fun now(): Long = try {
        val value = if (clock == null) System.nanoTime() else clock.invoke()
        lastReadValid = true
        value
    } catch (_: Throwable) {
        lastReadValid = false
        0L // Not a sentinel: lastReadValid carries availability independently.
    }

    inline fun <T> measure(part: GpuExportDetail, action: () -> T): T {
        val start = now()
        val startValid = lastReadValid
        var completed = false
        try {
            val result = action()
            completed = true
            return result
        } finally {
            try {
                val end = now()
                val elapsed = end - start
                addPrimitive(part, elapsed,
                    startValid && lastReadValid && end >= start && elapsed >= 0, completed)
            } catch (_: Throwable) { }
        }
    }

    @PublishedApi internal fun addPrimitive(part: GpuExportDetail, nanos: Long,
        available: Boolean, completed: Boolean) {
        // Reporting must never replace an action result or its primary failure.
        try {
            val index = part.ordinal
            seen[index] = true
            complete[index] = complete[index] && completed
            if (!available || nanos < 0 || Long.MAX_VALUE - sums[index] < nanos) valid[index] = false
            else sums[index] += nanos
        } catch (_: Throwable) { }
    }

    // Nullable values are only used during the once-per-scan merge, never the row hot path.
    private fun add(part: GpuExportDetail, nanos: Long?, completed: Boolean) =
        addPrimitive(part, nanos ?: 0L, nanos != null, completed)
    fun sample(part: GpuExportDetail): Sample? = if (!seen[part.ordinal]) null else
        Sample(sums[part.ordinal].takeIf { valid[part.ordinal] }, complete[part.ordinal])

    fun fork(): GpuExportWorkTimings? = try { GpuExportWorkTimings(clock) } catch (_: Throwable) { null }

    /** rows() total excludes initial buffer borrowing; callbacks include later borrowing. */
    fun mergeInput(reader: GpuExportWorkTimings?) {
        try {
            if (reader == null) {
                add(GpuExportDetail.INPUT_READ_CONVERT, null, true)
                add(GpuExportDetail.INPUT_PACK, null, true)
                return
            }
            fun difference(outer: Sample, inner: Sample?): Long? {
                val total = outer.nanos ?: return null
                val nested = if (inner == null) 0L else inner.nanos ?: return null
                return (total - nested).takeIf { it >= 0 }
            }
            val total = reader.sample(GpuExportDetail.READER_TOTAL)
            val callback = reader.sample(GpuExportDetail.INPUT_CALLBACK)
            val waiting = reader.sample(GpuExportDetail.INPUT_FREE_WAIT)
            if (total != null) add(GpuExportDetail.INPUT_READ_CONVERT,
                difference(total, callback), total.completed)
            if (callback != null) add(GpuExportDetail.INPUT_PACK,
                difference(callback, waiting), callback.completed)
        } catch (_: Throwable) {
            add(GpuExportDetail.INPUT_READ_CONVERT, null, true)
            add(GpuExportDetail.INPUT_PACK, null, true)
        }
    }
}

internal inline fun <T> gpuWork(timing: GpuExportWorkTimings?, part: GpuExportDetail, crossinline action: () -> T): T =
    if (timing == null) action() else timing.measure(part) { action() }
