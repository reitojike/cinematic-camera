package dev.cinematic.p0.metrics

import java.util.UUID

internal enum class GpuPreviewTimingStage {
    OPEN, CHECK_ORIGINAL, PREFLIGHT, SNAPSHOT, GUIDE, NATIVE_DECODE, BOX_REDUCE, VERIFY_AFTER, GPU_FIRST_FRAME,
}
internal enum class GpuPreviewTimingOutcome { COMPLETED, INCOMPLETE, NOT_REACHED, PRESENTED, CANCELLED, FAILED }

/** Fixed anonymous schema, stored separately from save timing. No caller-provided text. */
internal data class GpuPreviewTimingRecord(val attempt: String, val version: Int,
    val stage: GpuPreviewTimingStage, val outcome: GpuPreviewTimingOutcome, val pixels: Long?, val nanos: Long?) {
    fun line(): String {
        require(attempt.matches(Regex("[0-9a-f]{32}")) && version > 0)
        require(pixels == null || pixels in 1..24_000_000)
        require(nanos == null || nanos >= 0)
        return "1\t$attempt\t$version\tGPU_OPEN\t${stage.name}\t${outcome.name}\t${pixels ?: "NA"}\t${nanos ?: "NA"}\n"
    }
    companion object {
        fun valid(line: String): Boolean = try {
            val p = line.split('\t')
            p.size == 8 && p[0] == "1" && p[3] == "GPU_OPEN" && GpuPreviewTimingRecord(p[1], p[2].toInt(),
                GpuPreviewTimingStage.valueOf(p[4]), GpuPreviewTimingOutcome.valueOf(p[5]),
                p[6].takeUnless { it == "NA" }?.toLong(), p[7].takeUnless { it == "NA" }?.toLong()).line() == "$line\n"
        } catch (_: Exception) { false }
    }
}

/** One collector per editor open. Reports never participate in readiness or resource ownership. */
internal class GpuPreviewTiming private constructor(private val version: Int,
    private val sink: (List<GpuPreviewTimingRecord>) -> Unit, private val clock: () -> Long) {
    private val id = UUID.randomUUID().toString().replace("-", "")
    private val start = clock()
    private var pixels: Long? = null
    private var firstFrameStart: Long? = null
    private var firstFrameEntered = false
    private var ended = false
    private val records = linkedMapOf<GpuPreviewTimingStage, GpuPreviewTimingRecord>()
    @Synchronized fun pixels(width: Int, height: Int) = timingBestEffort {
        val count = width.toLong() * height
        if (!ended && width > 0 && height > 0 && count in 1..24_000_000) pixels = count
    }
    fun <T> measure(stage: GpuPreviewTimingStage, action: () -> T): T {
        var begin: Long? = null
        timingBestEffort {
            record(stage, GpuPreviewTimingOutcome.INCOMPLETE, null)
            begin = clock()
        }
        var completed = false
        try { return action().also { completed = true } }
        finally { timingBestEffort { record(stage,
            if (completed) GpuPreviewTimingOutcome.COMPLETED else GpuPreviewTimingOutcome.INCOMPLETE,
            elapsed(begin)) } }
    }
    private fun elapsed(begin: Long?): Long? = try {
        begin?.let { (clock() - it).coerceAtLeast(0) }
    } catch (_: Throwable) { null }
    @Synchronized private fun record(stage: GpuPreviewTimingStage, outcome: GpuPreviewTimingOutcome, nanos: Long?) {
        if (!ended) records[stage] = GpuPreviewTimingRecord(id, version, stage, outcome, pixels, nanos)
    }
    @Synchronized fun firstFrameStarted() = timingBestEffort {
        if (!ended && !firstFrameEntered) {
            firstFrameEntered = true
            firstFrameStart = clock()
        }
    }
    @Synchronized fun presented() = timingBestEffort {
        if (!ended && firstFrameEntered) {
            record(GpuPreviewTimingStage.GPU_FIRST_FRAME, GpuPreviewTimingOutcome.COMPLETED,
                elapsed(firstFrameStart))
            finish(GpuPreviewTimingOutcome.PRESENTED)
        }
    }
    /** Classify the original cause, never a signal also used for cleanup. */
    fun failed(cause: Throwable) = finish(if (cause is java.util.concurrent.CancellationException ||
        cause is dev.cinematic.p0.rawcapture.DngVerificationStopped || cause is android.os.OperationCanceledException)
        GpuPreviewTimingOutcome.CANCELLED else GpuPreviewTimingOutcome.FAILED)
    @Synchronized fun finish(outcome: GpuPreviewTimingOutcome) = timingBestEffort {
        if (!ended) {
            for (stage in GpuPreviewTimingStage.entries) if (stage != GpuPreviewTimingStage.OPEN && stage !in records) {
                record(stage, if (stage == GpuPreviewTimingStage.GPU_FIRST_FRAME && firstFrameEntered)
                    GpuPreviewTimingOutcome.INCOMPLETE else GpuPreviewTimingOutcome.NOT_REACHED, null)
            }
            record(GpuPreviewTimingStage.OPEN, outcome, elapsed(start))
            ended = true
            try { sink(records.values.toList()) } finally { records.clear(); firstFrameStart = null }
        }
    }
    companion object {
        fun start(version: Int, sink: (List<GpuPreviewTimingRecord>) -> Unit,
            clock: () -> Long = System::nanoTime): GpuPreviewTiming? = try {
            GpuPreviewTiming(version, sink, clock)
        } catch (_: Throwable) { null }
    }
}

internal fun <T> previewMeasured(timing: GpuPreviewTiming?, stage: GpuPreviewTimingStage, action: () -> T): T =
    if (timing == null) action() else timing.measure(stage, action)
