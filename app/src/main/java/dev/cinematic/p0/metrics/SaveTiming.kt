package dev.cinematic.p0.metrics

import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace
import java.util.UUID

/** Reporting is never save authority, including when allocation or a sink fails. */
internal inline fun timingBestEffort(action: () -> Unit) { try { action() } catch (_: Throwable) { } }

internal enum class SaveTimingStage {
    ATTEMPT, NATIVE_DECODE, JPEG_ENCODE, RESOURCE_CLOSE,
    SOURCE_VERIFY_BEFORE, SOURCE_SNAPSHOT, DECODE_CLOSE, ANALYSIS_REDUCE, CONTROL_PREPARE,
    BITMAP_ALLOC, JPEG_INSPECT, SOURCE_VERIFY_AFTER, SCRATCH_CLEANUP,
    ENVIRONMENT_START, ENVIRONMENT_END,
    B1_INPUT_WAIT, B1_INPUT_READ_CONVERT, B1_UPLOAD, B1_FENCE_WAIT,
    B2_INPUT_WAIT, B2_INPUT_READ_CONVERT, B2_UPLOAD, B2_FENCE_WAIT,
    B3_INPUT_WAIT, B3_INPUT_READ_CONVERT, B3_UPLOAD, B3_FENCE_WAIT,
    B4_INPUT_WAIT, B4_INPUT_READ_CONVERT, B4_UPLOAD, B4_FENCE_WAIT,
    B5_INPUT_WAIT, B5_INPUT_READ_CONVERT, B5_UPLOAD, B5_FENCE_WAIT,
    FINAL_INPUT_WAIT, FINAL_INPUT_READ_CONVERT, FINAL_UPLOAD, FINAL_FENCE_WAIT,
    B4_READBACK, B1_QUANTILES_READBACK, B1_QUANTILES_FENCE_WAIT, FINAL_READBACK, FINAL_ROW_PROCESS,
    B1, B1_QUANTILES, B2, B3, B4, B5, FINAL, GPU_RENDER, PUBLICATION, RECONCILIATION,
    DECODE_PREAD, DECODE_OPEN_PARSE, DECODE_UNPACK, DECODE_RAW2IMAGE,
    DECODE_SCALE_COLORS, DECODE_PRE_INTERP, DECODE_AHD, DECODE_CONVERT_RGB,
    DECODE_MAKE_IMAGE, DECODE_WRITE, ANALYSIS_REDUCE_PARITY,
}
internal enum class SaveTimingOutcome {
    STARTED, COMPLETED, INCOMPLETE, SKIPPED, NOT_REACHED, UNAVAILABLE,
    PUBLISHED, CANCELLED, HOLD, GPU_FAILED, UNCONFIRMED, FAILED,
}
internal enum class SaveTimingKind { SAVE, RECHECK }

/** Fixed schema: no caller-provided text, source identifiers, dates, URIs or exception details. */
internal data class SaveTimingRecord(val attempt: String, val version: Int, val kind: SaveTimingKind,
    val stage: SaveTimingStage, val outcome: SaveTimingOutcome, val pixels: Long?, val nanos: Long?,
    val environment: SaveTimingEnvironment? = null) {
    fun line(): String {
        require(attempt.matches(Regex("[0-9a-f]{32}")) && version > 0)
        require(pixels == null || pixels in 1..24_000_000)
        require(nanos == null || nanos >= 0)
        require((stage == SaveTimingStage.ENVIRONMENT_START || stage == SaveTimingStage.ENVIRONMENT_END) == (environment != null))
        val suffix = environment?.let {
            require(outcome == SaveTimingOutcome.COMPLETED && nanos == null)
            require(it.thermal == null || it.thermal in 0..6)
            "\t${it.visibility}\t${it.thermal ?: "NA"}"
        } ?: ""
        return "${if (environment == null) 1 else 2}\t$attempt\t$version\t${kind.name}\t${stage.name}\t${outcome.name}\t${pixels ?: "NA"}\t${nanos ?: "NA"}$suffix\n"
    }
    companion object {
        fun valid(line: String): Boolean = try {
            val p = line.split('\t')
            val env = when {
                p.size == 8 && p[0] == "1" -> null
                p.size == 10 && p[0] == "2" -> SaveTimingEnvironment(SaveVisibility.valueOf(p[8]), p[9].takeUnless { it == "NA" }?.toInt())
                else -> throw IllegalArgumentException("Unknown timing schema")
            }
            SaveTimingRecord(p[1], p[2].toInt(), SaveTimingKind.valueOf(p[3]),
                SaveTimingStage.valueOf(p[4]), SaveTimingOutcome.valueOf(p[5]),
                p[6].takeUnless { it == "NA" }?.toLong(), p[7].takeUnless { it == "NA" }?.toLong(), env).line() == "$line\n"
        } catch (_: Exception) { false }
    }
}

/**
 * One bounded in-memory collector per admitted attempt. No disk I/O until finish, after all measured
 * work and resource release. Original callbacks and GPU timing drain run on queue IO.
 * STARTED is retained only in the schema reader for records written by earlier versions.
 */
internal class SaveTiming private constructor(private val version: Int, private val kind: SaveTimingKind,
    private val sink: (List<SaveTimingRecord>) -> Unit, private val clock: () -> Long,
    private val environment: (() -> SaveTimingEnvironment)? = null) : OriginalStageTrace {
    private val id = UUID.randomUUID().toString().replace("-", "")
    private var pixels: Long? = null
    private val entered = mutableSetOf<SaveTimingStage>()
    private val starts = mutableMapOf<SaveTimingStage, Long>()
    private val records = ArrayDeque<SaveTimingRecord>()
    private val attemptStart = try { clock() } catch (_: Throwable) { null }
    private var ended = false
    init { captureEnvironment(SaveTimingStage.ENVIRONMENT_START) }
    private fun captureEnvironment(stage: SaveTimingStage) = timingBestEffort {
        environment?.let { sample ->
            val snapshot = try { sample() } catch (_: Throwable) { SaveTimingEnvironment() }
            if (records.size == SaveTimingStore.MAX_RECORDS) records.removeFirst()
            records.addLast(SaveTimingRecord(id, version, kind, stage, SaveTimingOutcome.COMPLETED, pixels, null, snapshot))
        }
    }
    @Synchronized fun pixels(width: Int, height: Int) = timingBestEffort {
        val count = width.toLong() * height
        if (width > 0 && height > 0 && count in 1..24_000_000) {
            pixels = count
        }
    }
    @Synchronized fun event(stage: SaveTimingStage, outcome: SaveTimingOutcome, nanos: Long? = null) = timingBestEffort {
        if (!ended && outcome != SaveTimingOutcome.STARTED) {
            entered += stage
            // Even an unexpectedly repeated callback cannot create an unbounded diagnostic buffer.
            if (records.size == SaveTimingStore.MAX_RECORDS) records.removeFirst()
            records.addLast(SaveTimingRecord(id, version, kind, stage, outcome, pixels, nanos))
        }
    }
    @Synchronized override fun started(stage: OriginalStage) = timingBestEffort {
        mapped(stage)?.let {
            starts.remove(it)
            // originalStage's outer clock includes observer work; use our own action interval.
            starts[it] = clock()
        }
    }
    @Synchronized override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) = timingBestEffort {
        mapped(stage)?.let {
            val end = clock()
            val duration = starts.remove(it)?.let { start -> (end - start).coerceAtLeast(0) }
            event(it, if (completed) SaveTimingOutcome.COMPLETED else SaveTimingOutcome.INCOMPLETE, duration)
        }
    }
    fun <T> measure(stage: SaveTimingStage, action: () -> T): T {
        var start: Long? = null
        timingBestEffort { start = clock() }
        var completed = false
        try { return action().also { completed = true } }
        finally { timingBestEffort {
            event(stage, if (completed) SaveTimingOutcome.COMPLETED else SaveTimingOutcome.INCOMPLETE,
                start?.let { (clock() - it).coerceAtLeast(0) })
        } }
    }
    @Synchronized fun finish(outcome: SaveTimingOutcome) = timingBestEffort {
        if (!ended) {
            val duration = try { attemptStart?.let { (clock() - it).coerceAtLeast(0) } }
                catch (_: Throwable) { null }
            captureEnvironment(SaveTimingStage.ENVIRONMENT_END)
            for (stage in listOf(SaveTimingStage.NATIVE_DECODE, SaveTimingStage.JPEG_ENCODE,
                SaveTimingStage.GPU_RENDER, SaveTimingStage.PUBLICATION, SaveTimingStage.RECONCILIATION,
                SaveTimingStage.SOURCE_VERIFY_BEFORE, SaveTimingStage.SOURCE_SNAPSHOT, SaveTimingStage.DECODE_CLOSE,
                SaveTimingStage.ANALYSIS_REDUCE, SaveTimingStage.CONTROL_PREPARE, SaveTimingStage.BITMAP_ALLOC,
                SaveTimingStage.JPEG_INSPECT, SaveTimingStage.SOURCE_VERIFY_AFTER, SaveTimingStage.SCRATCH_CLEANUP)) {
                if (stage !in entered) event(stage, SaveTimingOutcome.NOT_REACHED)
            }
            event(SaveTimingStage.ATTEMPT, outcome, duration)
            ended = true
            try { sink(records.toList()) } finally { records.clear(); starts.clear() }
        }
    }
    private fun mapped(stage: OriginalStage) = when (stage) {
        OriginalStage.NATIVE_DECODE -> SaveTimingStage.NATIVE_DECODE
        OriginalStage.JPEG_ENCODE -> SaveTimingStage.JPEG_ENCODE
        OriginalStage.RESOURCE_CLOSE -> SaveTimingStage.RESOURCE_CLOSE
        OriginalStage.SOURCE_VERIFY_BEFORE -> SaveTimingStage.SOURCE_VERIFY_BEFORE
        OriginalStage.SOURCE_SNAPSHOT -> SaveTimingStage.SOURCE_SNAPSHOT
        OriginalStage.DECODE_CLOSE -> SaveTimingStage.DECODE_CLOSE
        OriginalStage.ANALYSIS_REDUCE -> SaveTimingStage.ANALYSIS_REDUCE
        OriginalStage.CONTROL_PREPARE -> SaveTimingStage.CONTROL_PREPARE
        OriginalStage.BITMAP_ALLOC -> SaveTimingStage.BITMAP_ALLOC
        OriginalStage.JPEG_INSPECT -> SaveTimingStage.JPEG_INSPECT
        OriginalStage.SOURCE_VERIFY_AFTER -> SaveTimingStage.SOURCE_VERIFY_AFTER
        OriginalStage.SCRATCH_CLEANUP -> SaveTimingStage.SCRATCH_CLEANUP
        else -> null
    }
    companion object {
        fun startObserved(version: Int, kind: SaveTimingKind, sink: (List<SaveTimingRecord>) -> Unit,
            environment: () -> SaveTimingEnvironment, clock: () -> Long = System::nanoTime): SaveTiming? = try {
            SaveTiming(version, kind, sink, clock, environment)
        } catch (_: Throwable) { null }
        fun start(version: Int, kind: SaveTimingKind, sink: (List<SaveTimingRecord>) -> Unit,
            clock: () -> Long = System::nanoTime): SaveTiming? = try {
            SaveTiming(version, kind, sink, clock)
        } catch (_: Throwable) { null }
    }
}

internal fun <T> measured(timing: SaveTiming?, stage: SaveTimingStage, action: () -> T): T =
    if (timing == null) action() else timing.measure(stage) { action() }
