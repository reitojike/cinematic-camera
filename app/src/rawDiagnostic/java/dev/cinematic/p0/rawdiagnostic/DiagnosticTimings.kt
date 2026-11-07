package dev.cinematic.p0.rawdiagnostic

import dev.cinematic.p0.raw.original.OriginalStage
import dev.cinematic.p0.raw.original.OriginalStageTrace

/** Fixed enum fields only: never accepts source metadata or exception text. */
internal class DiagnosticTimings : OriginalStageTrace {
    data class Span(val stage: OriginalStage, val nanos: Long, val completed: Boolean)
    val spans = mutableListOf<Span>()
    override fun finished(stage: OriginalStage, elapsedNanos: Long, completed: Boolean) {
        spans.add(Span(stage, elapsedNanos, completed))
    }
    // ORTON includes FIELD and COMPOSE; CPU counters are not wall-clock spans.
    fun accountedNanos() = spans.filter { it.stage !in setOf(OriginalStage.ORTON_FIELD,
        OriginalStage.ORTON_COMPOSE, OriginalStage.ORTON_PIXEL_THREAD_CPU) }.sumOf { it.nanos }
    fun residualNanos(total: Long) = total - accountedNanos()
}

internal object DiagnosticPlan {
    // Warm-up one of each; measured ABBAAB (or its inverse), three successes per arm.
    fun workers(reverse: Boolean) = (listOf(1, 2) + listOf(1, 2, 2, 1, 1, 2))
        .map { if (reverse) 3 - it else it }
    fun excluded(index: Int) = index < 2
}

internal enum class DiagnosticComparison(val label: String, val candidate: Int) {
    FOUNDATION("R7 foundation: 1 vs 2", 2), GLOW_TWO("Glow: 1 vs 2", 2), GLOW_FOUR("Glow: 1 vs 4", 4);
    fun foundationWorkers(arm: Int) = if (this == FOUNDATION && arm == 2) 2 else 1
    fun glowWorkers(arm: Int) = if (this != FOUNDATION && arm == 2) candidate else 1
}