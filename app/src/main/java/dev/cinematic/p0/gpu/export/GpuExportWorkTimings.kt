package dev.cinematic.p0.gpu.export

/** CPU wall intervals, not GPU timer queries. Reader intervals overlap the GL caller. */
internal enum class GpuExportDetail {
    INPUT_WAIT, INPUT_READ_CONVERT, INPUT_PACK, UPLOAD, FENCE_WAIT,
    READBACK, ROW_CONVERT, ROW_CONSUME,
    // Reader-local intermediates; never serialized.
    READER_TOTAL, INPUT_CALLBACK, INPUT_FREE_WAIT,
}

/** Thread-confined counters. A reader is merged only after the existing join has drained it. */
internal class GpuExportWorkTimings(private val clock: () -> Long = System::nanoTime) {
    internal data class Sample(val nanos: Long?, val completed: Boolean)
    private val sums = LongArray(GpuExportDetail.entries.size)
    private val seen = BooleanArray(sums.size)
    private val valid = BooleanArray(sums.size) { true }
    private val complete = BooleanArray(sums.size) { true }

    private fun now(): Long? = try { clock() } catch (_: Throwable) { null }
    fun <T> measure(part: GpuExportDetail, action: () -> T): T {
        val start = now()
        var completed = false
        try { return action().also { completed = true } }
        finally {
            try {
                val end = now()
                add(part, if (start != null && end != null && end >= start) end - start else null, completed)
            } catch (_: Throwable) { }
        }
    }
    private fun add(part: GpuExportDetail, nanos: Long?, completed: Boolean) {
        // Reporting must never replace an action result or its primary failure.
        try {
            val index = part.ordinal
            seen[index] = true
            complete[index] = complete[index] && completed
            if (nanos == null || nanos < 0 || Long.MAX_VALUE - sums[index] < nanos) valid[index] = false
            else sums[index] += nanos
        } catch (_: Throwable) { }
    }
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
