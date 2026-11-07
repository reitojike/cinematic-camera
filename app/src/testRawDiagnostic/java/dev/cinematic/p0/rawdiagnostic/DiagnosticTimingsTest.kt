package dev.cinematic.p0.rawdiagnostic

import dev.cinematic.p0.raw.original.OriginalStage
import org.junit.Assert.*
import org.junit.Test

class DiagnosticTimingsTest {
    @Test fun comparisonArmsKeepExplicitSerialBaselinesIndependentOfProductionSave() {
        assertEquals(1, DiagnosticComparison.FOUNDATION.foundationWorkers(1))
        assertEquals(2, DiagnosticComparison.FOUNDATION.foundationWorkers(2))
        for (arm in 1..2) {
            assertEquals(1, DiagnosticComparison.FOUNDATION.glowWorkers(arm))
            assertEquals(1, DiagnosticComparison.GLOW_TWO.foundationWorkers(arm))
            assertEquals(1, DiagnosticComparison.GLOW_FOUR.foundationWorkers(arm))
        }
        assertEquals(1, DiagnosticComparison.GLOW_TWO.glowWorkers(1))
        assertEquals(2, DiagnosticComparison.GLOW_TWO.glowWorkers(2))
        assertEquals(1, DiagnosticComparison.GLOW_FOUR.glowWorkers(1))
        assertEquals(4, DiagnosticComparison.GLOW_FOUR.glowWorkers(2))
    }

    @Test fun excludesChildrenAndCpuButRetainsRepeatedTopLevelSpans() {
        val timing = DiagnosticTimings()
        timing.finished(OriginalStage.ORTON, 100, true)
        timing.finished(OriginalStage.ORTON_FIELD, 40, true)
        timing.finished(OriginalStage.ORTON_COMPOSE, 50, true)
        timing.finished(OriginalStage.ORTON_PIXEL_THREAD_CPU, 80, true)
        timing.finished(OriginalStage.RESOURCE_CLOSE, 2, true)
        timing.finished(OriginalStage.RESOURCE_CLOSE, 3, true)
        assertEquals(105L, timing.accountedNanos())
        assertEquals(15L, timing.residualNanos(120))
        assertEquals(6, timing.spans.size)
    }
    @Test fun warmupsCannotCountTowardThreeSuccessfulMeasuredAttemptsPerArm() {
        for (reverse in listOf(false, true)) {
            val plan = DiagnosticPlan.workers(reverse)
            assertEquals(8, plan.size)
            assertEquals(setOf(1, 2), plan.take(2).toSet())
            for (arm in 1..2) assertEquals(3, plan.withIndex().count { !DiagnosticPlan.excluded(it.index) && it.value == arm })
        }
    }
}
