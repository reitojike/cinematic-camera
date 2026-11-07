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
    const val ATTEMPTS = 10
    const val MEASURED_PER_ARM = 4
    // One warm-up per arm, then two ABBA blocks (or inverse BAAB blocks).
    fun workers(reverse: Boolean, memory: Boolean = false) = (if (memory) listOf(1, 2) else
        listOf(1, 2) + listOf(1, 2, 2, 1, 1, 2, 2, 1))
        .map { if (reverse) 3 - it else it }
    fun excluded(index: Int, memory: Boolean = false) = !memory && index < 2
    fun order(reverse: Boolean, memory: Boolean = false) = if (memory) { if (reverse) "BA" else "AB" }
        else if (reverse) "BA-BAABBAAB" else "AB-ABBAABBA"
}

internal data class DiagnosticProfile(val foundation: Int = 2, val glow: Int = 1,
    val tone: Int = 1, val mist: Int = 1, val fuseColor: Boolean = false)

internal enum class DiagnosticComparison(val label: String) {
    RAW4("RAW4: all off vs all on (Foundation 2)"),
    TONE_ONLY("Follow-up only: R7 tone 1 vs 2"),
    COLOR_FUSION_ONLY("Follow-up only: R7 separate vs fused"),
    FOUNDATION("Legacy: R7 foundation 1 vs 2"),
    GLOW_TWO("Legacy: Glow 1 vs 2 (Foundation 1)"),
    GLOW_FOUR("Legacy: Glow 1 vs 4 (Foundation 1)");

    fun profile(arm: Int): DiagnosticProfile {
        require(arm in 1..2)
        val on = arm == 2
        return when (this) {
            RAW4 -> if (on) DiagnosticProfile(glow = 2, tone = 2, mist = 2, fuseColor = true) else DiagnosticProfile()
            TONE_ONLY -> DiagnosticProfile(tone = if (on) 2 else 1)
            COLOR_FUSION_ONLY -> DiagnosticProfile(fuseColor = on)
            FOUNDATION -> DiagnosticProfile(foundation = if (on) 2 else 1)
            GLOW_TWO -> DiagnosticProfile(foundation = 1, glow = if (on) 2 else 1)
            GLOW_FOUR -> DiagnosticProfile(foundation = 1, glow = if (on) 4 else 1)
        }
    }
    fun foundationWorkers(arm: Int) = profile(arm).foundation
    fun glowWorkers(arm: Int) = profile(arm).glow
}