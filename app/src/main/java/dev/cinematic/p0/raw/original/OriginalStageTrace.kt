package dev.cinematic.p0.raw.original

/** Fixed diagnostic labels only. A successful span is not render/publication completion evidence. */
internal enum class OriginalStage {
    SOURCE_VERIFY_BEFORE, SOURCE_SNAPSHOT, NATIVE_DECODE, DECODE_CLOSE, ANALYSIS_REDUCE, CONTROL_PREPARE,
    R7_PROFILE, R7_TONE_ANALYSIS, R7_TONE_APPLY, R7_FOUNDATION, R7_VIBRANCE, R7_CRISP, R7_SKIN,
    SKIN_FIELD, GLOW, NEAR_WHITE, MIST, ORTON, ORTON_FIELD, ORTON_COMPOSE, ORTON_PIXEL_THREAD_CPU,
    CHARACTER_CAP, FACE_COUNT, FACE_PERCENTILE,
    FACE_SCALE, SHOULDER, BITMAP_PACK, JPEG_ENCODE, JPEG_INSPECT, SOURCE_VERIFY_AFTER,
    RESOURCE_CLOSE, SCRATCH_CLEANUP,
}

internal fun interface OriginalStageTrace {
    fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean)
}

/** No clock/event work when disconnected. Observer faults cannot hide a primary producer failure. */
internal inline fun <T> originalStage(trace: OriginalStageTrace?, stage: OriginalStage, action: () -> T): T {
    if (trace == null) return action()
    val start = System.nanoTime()
    var completed = false
    var primary: Throwable? = null
    try { return action().also { completed = true } }
    catch (failure: Throwable) { primary = failure; throw failure }
    finally {
        try { trace.finished(stage, System.nanoTime() - start, completed) }
        catch (observerFailure: Throwable) {
            val failed = primary
            if (failed == null) throw observerFailure
            if (failed !== observerFailure) failed.addSuppressed(observerFailure)
        }
    }
}
