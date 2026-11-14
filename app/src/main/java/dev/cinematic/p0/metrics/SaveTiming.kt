package dev.cinematic.p0.metrics

import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace
import java.util.UUID

/** Reporting is never save authority, including when allocation or a sink fails. */
internal inline fun timingBestEffort(action: () -> Unit) { try { action() } catch (_: Throwable) { } }

internal enum class SaveTimingStage {
    ATTEMPT, NATIVE_DECODE, JPEG_ENCODE, RESOURCE_CLOSE,
    B1, B1_QUANTILES, B2, B3, B4, B5, FINAL, GPU_RENDER, PUBLICATION, RECONCILIATION,
}
internal enum class SaveTimingOutcome {
    STARTED, COMPLETED, INCOMPLETE, SKIPPED, NOT_REACHED, UNAVAILABLE,
    PUBLISHED, CANCELLED, HOLD, GPU_FAILED, UNCONFIRMED, FAILED,
}
internal enum class SaveTimingKind { SAVE, RECHECK }

/** Fixed schema: no caller-provided text, source identifiers, dates, URIs or exception details. */
internal data class SaveTimingRecord(val attempt: String, val version: Int, val kind: SaveTimingKind,
    val stage: SaveTimingStage, val outcome: SaveTimingOutcome, val pixels: Long?, val nanos: Long?) {
    fun line(): String {
        require(attempt.matches(Regex("[0-9a-f]{32}")) && version > 0)
        require(pixels == null || pixels in 1..24_000_000)
        require(nanos == null || nanos >= 0)
        return "1\t$attempt\t$version\t${kind.name}\t${stage.name}\t${outcome.name}\t${pixels ?: "NA"}\t${nanos ?: "NA"}\n"
    }
    companion object {
        fun valid(line: String): Boolean = try {
            val p = line.split('\t')
            p.size == 8 && p[0] == "1" && SaveTimingRecord(p[1], p[2].toInt(), SaveTimingKind.valueOf(p[3]),
                SaveTimingStage.valueOf(p[4]), SaveTimingOutcome.valueOf(p[5]),
                p[6].takeUnless { it == "NA" }?.toLong(), p[7].takeUnless { it == "NA" }?.toLong()).line() == "$line\n"
        } catch (_: Exception) { false }
    }
}

/** One collector per admitted attempt. Original trace calls are on queue IO, never singleton state. */
internal class SaveTiming private constructor(private val version: Int, private val kind: SaveTimingKind,
    private val sink: (SaveTimingRecord) -> Unit, private val clock: () -> Long) : OriginalStageTrace {
    private val id = UUID.randomUUID().toString().replace("-", "")
    private var pixels: Long? = null
    private val entered = mutableSetOf<SaveTimingStage>()
    private val starts = mutableMapOf<SaveTimingStage, Long>()
    private var ended = false
    fun pixels(width: Int, height: Int) = timingBestEffort {
        val count = width.toLong() * height
        if (width > 0 && height > 0 && count in 1..24_000_000) {
            pixels = count
        }
    }
    fun event(stage: SaveTimingStage, outcome: SaveTimingOutcome, nanos: Long? = null) = timingBestEffort {
        entered += stage
        sink(SaveTimingRecord(id, version, kind, stage, outcome, pixels, nanos))
    }
    override fun started(stage: OriginalStage) = timingBestEffort {
        mapped(stage)?.let {
            starts.remove(it)
            event(it, SaveTimingOutcome.STARTED)
            // originalStage's outer clock includes observer work; use our own action interval.
            starts[it] = clock()
        }
    }
    override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) = timingBestEffort {
        mapped(stage)?.let {
            val end = clock()
            val duration = starts.remove(it)?.let { start -> (end - start).coerceAtLeast(0) }
            event(it, if (completed) SaveTimingOutcome.COMPLETED else SaveTimingOutcome.INCOMPLETE, duration)
        }
    }
    fun <T> measure(stage: SaveTimingStage, action: () -> T): T {
        var start: Long? = null
        event(stage, SaveTimingOutcome.STARTED)
        timingBestEffort { start = clock() }
        var completed = false
        try { return action().also { completed = true } }
        finally { timingBestEffort {
            event(stage, if (completed) SaveTimingOutcome.COMPLETED else SaveTimingOutcome.INCOMPLETE,
                start?.let { (clock() - it).coerceAtLeast(0) })
        } }
    }
    fun finish(outcome: SaveTimingOutcome) = timingBestEffort {
        if (!ended) {
            ended = true
            for (stage in listOf(SaveTimingStage.NATIVE_DECODE, SaveTimingStage.JPEG_ENCODE,
                SaveTimingStage.GPU_RENDER, SaveTimingStage.PUBLICATION, SaveTimingStage.RECONCILIATION)) {
                if (stage !in entered) event(stage, SaveTimingOutcome.NOT_REACHED)
            }
            event(SaveTimingStage.ATTEMPT, outcome)
        }
    }
    private fun mapped(stage: OriginalStage) = when (stage) {
        OriginalStage.NATIVE_DECODE -> SaveTimingStage.NATIVE_DECODE
        OriginalStage.JPEG_ENCODE -> SaveTimingStage.JPEG_ENCODE
        OriginalStage.RESOURCE_CLOSE -> SaveTimingStage.RESOURCE_CLOSE
        else -> null
    }
    companion object {
        fun start(version: Int, kind: SaveTimingKind, sink: (SaveTimingRecord) -> Unit,
            clock: () -> Long = System::nanoTime): SaveTiming? = try {
            SaveTiming(version, kind, sink, clock).also { it.event(SaveTimingStage.ATTEMPT, SaveTimingOutcome.STARTED) }
        } catch (_: Throwable) { null }
    }
}

internal fun <T> measured(timing: SaveTiming?, stage: SaveTimingStage, action: () -> T): T =
    if (timing == null) action() else timing.measure(stage) { action() }
