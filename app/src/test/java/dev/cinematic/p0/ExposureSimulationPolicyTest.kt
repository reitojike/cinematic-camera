package dev.cinematic.p0

import java.math.BigInteger
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class ExposureSimulationPolicyTest {
    private val typicalIso = 100..6_400
    private val typicalTime = 1L..30_000_000_000L

    @Test fun quarterSecondAtIso100MapsExactlyToOneThirtySecondAtIso800() {
        val capture = ManualExposure(100, 250_000_000L)
        val plan = ready(capture, fast = 31_250_000L)
        assertEquals(capture, plan.capture)
        assertEquals(ManualExposure(800, 31_250_000L), plan.preview)
        assertEquals(SimulationStage.COMPENSATED, plan.stage)
        assertEquals(31_250_000L, plan.fastExposureNs)
        assertEquals(0.0, plan.nominalErrorEv, 0.0)
    }

    @Test fun fifteenSecondCaptureUsesLongerPreviewAtAdvertisedIsoCeiling() {
        val plan = ready(ManualExposure(100, 15_000_000_000L))
        assertEquals(SimulationStage.LONGER_PREVIEW, plan.stage)
        assertEquals(ManualExposure(6_400, 234_375_000L), plan.preview)
        assertEquals(0.0, plan.nominalErrorEv, 0.0)
    }

    @Test fun stageBoundaryDependsOnCaptureIsoAndAdvertisedCeiling() {
        val time = 320_000_000L
        assertEquals(SimulationStage.COMPENSATED, ready(ManualExposure(100, time), iso = 100..3_200, fast = 10_000_000L).stage)
        assertEquals(SimulationStage.LONGER_PREVIEW, ready(ManualExposure(101, time), iso = 100..3_200, fast = 10_000_000L).stage)
        assertEquals(SimulationStage.COMPENSATED, ready(ManualExposure(200, time), fast = 10_000_000L).stage)
        assertEquals(SimulationStage.LONGER_PREVIEW, ready(ManualExposure(201, time), fast = 10_000_000L).stage)
    }

    @Test fun shutterDurationAloneDoesNotImposeAOneSecondCutoff() {
        val plan = ready(ManualExposure(100, 2_000_000_000L), iso = 100..10_000)
        assertEquals(SimulationStage.COMPENSATED, plan.stage)
        assertEquals(6_000, plan.preview.iso)
        assertEquals(33_333_333L, plan.preview.exposureNs)
    }

    @Test fun captureShorterThanFastPeriodKeepsItsOriginalExposure() {
        val capture = ManualExposure(250, 1_000_000L)
        val plan = ready(capture)
        assertEquals(capture, plan.preview)
        assertEquals(SimulationStage.COMPENSATED, plan.stage)
        assertEquals(0.0, plan.nominalErrorEv, 0.0)
    }

    @Test fun compensatedIsoRoundsToNearestIntegerWithSignedNominalError() {
        val brighter = ready(ManualExposure(101, 3L), iso = 1..1_000, fast = 2L)
        assertEquals(ManualExposure(152, 2L), brighter.preview)
        assertEquals(ln(304.0 / 303.0) / ln(2.0), brighter.nominalErrorEv, 1e-15)
        val roundedUp = ready(ManualExposure(101, 4L), iso = 1..1_000, fast = 3L)
        assertEquals(ManualExposure(135, 3L), roundedUp.preview)
        assertEquals(ln(405.0 / 404.0) / ln(2.0), roundedUp.nominalErrorEv, 1e-15)
        val roundedDown = ready(ManualExposure(100, 4L), iso = 1..1_000, fast = 3L)
        assertEquals(ManualExposure(133, 3L), roundedDown.preview)
        assertTrue(roundedDown.nominalErrorEv < 0.0)
    }

    @Test fun exceedingIsoCeilingByAFractionEntersSecondStageBeforeIsoRounding() {
        val plan = ready(ManualExposure(100, 11L), iso = 100..366, fast = 3L)
        assertEquals(SimulationStage.LONGER_PREVIEW, plan.stage)
        assertEquals(ManualExposure(366, 4L), plan.preview)
        assertTrue(plan.nominalErrorEv > 0.0)
    }

    @Test fun longerPreviewUsesCeilingNanosecondsAndReportsTinyRangeQuantization() {
        val plan = ready(ManualExposure(2, 5L), iso = 1..3, time = 1L..5L, frame = 5L, fast = 2L)
        assertEquals(SimulationStage.LONGER_PREVIEW, plan.stage)
        assertEquals(ManualExposure(3, 4L), plan.preview)
        assertEquals(ln(1.2) / ln(2.0), plan.nominalErrorEv, 1e-15)
    }

    @Test fun sensorMinimumLongerThanFastPeriodUsesSecondStageWithoutNeedlessGain() {
        val plan = ready(ManualExposure(100, 50L), time = 50L..1_000L, frame = 1_000L, fast = 10L)
        assertEquals(SimulationStage.LONGER_PREVIEW, plan.stage)
        assertEquals(ManualExposure(100, 50L), plan.preview)
        assertEquals(0.0, plan.nominalErrorEv, 0.0)
    }

    @Test fun sensorMinimumUsesNearestIsoAndReportsItsQuantization() {
        val plan = ready(ManualExposure(2, 11L), iso = 2..7, time = 5L..11L, fast = 3L)
        assertEquals(SimulationStage.LONGER_PREVIEW, plan.stage)
        assertEquals(ManualExposure(4, 5L), plan.preview)
        assertEquals(ln(20.0 / 22.0) / ln(2.0), plan.nominalErrorEv, 1e-15)
    }

    @Test fun fixedIsoAndExposureEndpointsAreLegal() {
        val plan = ready(ManualExposure(100, 50L), iso = 100..100, time = 50L..50L, frame = 50L, fast = 10L)
        assertEquals(ManualExposure(100, 50L), plan.preview)
        assertEquals(SimulationStage.LONGER_PREVIEW, plan.stage)
    }

    @Test fun productsAboveLongMaxRemainCorrectAtExtremeIsoAndExposure() {
        val capture = ManualExposure(Int.MAX_VALUE, Long.MAX_VALUE)
        val plan = ready(capture, iso = 1..Int.MAX_VALUE, time = 1L..Long.MAX_VALUE, frame = Long.MAX_VALUE, fast = 1L)
        assertEquals(capture, plan.preview)
        assertEquals(SimulationStage.LONGER_PREVIEW, plan.stage)
        assertEquals(0.0, plan.nominalErrorEv, 0.0)
    }

    @Test fun overflowSafeCeilingAndErrorRetainAUnitProductDifference() {
        val capture = ManualExposure(Int.MAX_VALUE - 1, Long.MAX_VALUE)
        val plan = ready(capture, iso = 1..Int.MAX_VALUE, time = 1L..Long.MAX_VALUE, frame = Long.MAX_VALUE, fast = 1L)
        val target = BigInteger.valueOf(capture.iso.toLong()) * BigInteger.valueOf(capture.exposureNs)
        val preview = BigInteger.valueOf(plan.preview.iso.toLong()) * BigInteger.valueOf(plan.preview.exposureNs)
        assertEquals(1L, (preview - target).toLong())
        assertEquals(9_223_372_032_559_808_509L, plan.preview.exposureNs)
        assertTrue(plan.nominalErrorEv.isFinite())
        assertTrue(plan.nominalErrorEv > 0.0)
    }

    @Test fun eachUnknownLimitBlocksInsteadOfInventingACapability() {
        val capture = ManualExposure(100, 1L)
        assertBlocked(SimulationBlockReason.MISSING_LIMITS, ExposureSimulationPolicy.plan(capture, null, typicalTime, typicalTime.last))
        assertBlocked(SimulationBlockReason.MISSING_LIMITS, ExposureSimulationPolicy.plan(capture, typicalIso, null, typicalTime.last))
        assertBlocked(SimulationBlockReason.MISSING_LIMITS, ExposureSimulationPolicy.plan(capture, typicalIso, typicalTime, null))
    }

    @Test fun invalidLimitsAreBlocked() {
        val capture = ManualExposure(100, 10L)
        listOf(0..100, -100..100, 200..100).forEach { iso ->
            assertBlocked(SimulationBlockReason.INVALID_LIMITS, ExposureSimulationPolicy.plan(capture, iso, 1L..100L, 100L))
        }
        listOf(0L..100L, -1L..100L, 100L..1L).forEach { time ->
            assertBlocked(SimulationBlockReason.INVALID_LIMITS, ExposureSimulationPolicy.plan(capture, typicalIso, time, 100L))
        }
        listOf(0L, -1L, 5L).forEach { frame ->
            assertBlocked(SimulationBlockReason.INVALID_LIMITS, ExposureSimulationPolicy.plan(capture, typicalIso, 10L..100L, frame))
        }
    }

    @Test fun invalidFastTargetAndNonpositiveCaptureAreBlocked() {
        listOf(0L, -1L).forEach { fast ->
            assertBlocked(SimulationBlockReason.INVALID_FAST_EXPOSURE,
                ExposureSimulationPolicy.plan(ManualExposure(100, 1L), typicalIso, typicalTime, typicalTime.last, fast))
        }
        listOf(ManualExposure(0, 1L), ManualExposure(-1, 1L), ManualExposure(100, 0L), ManualExposure(100, -1L)).forEach { capture ->
            assertBlocked(SimulationBlockReason.INVALID_CAPTURE,
                ExposureSimulationPolicy.plan(capture, typicalIso, typicalTime, typicalTime.last))
        }
    }

    @Test fun captureOutsideSensorOrFrameLimitsIsBlockedWithoutClamping() {
        listOf(ManualExposure(99, 50L), ManualExposure(6_401, 50L), ManualExposure(100, 9L), ManualExposure(100, 101L)).forEach { capture ->
            assertBlocked(SimulationBlockReason.CAPTURE_OUT_OF_RANGE,
                ExposureSimulationPolicy.plan(capture, typicalIso, 10L..100L, 100L))
        }
        assertBlocked(SimulationBlockReason.CAPTURE_EXCEEDS_FRAME_DURATION,
            ExposureSimulationPolicy.plan(ManualExposure(100, 100L), typicalIso, 10L..100L, 99L))
    }

    private fun ready(
        capture: ManualExposure,
        iso: IntRange = typicalIso,
        time: LongRange = typicalTime,
        frame: Long = time.last,
        fast: Long = 33_333_333L,
    ): SimulationPlan {
        val result = ExposureSimulationPolicy.plan(capture, iso, time, frame, fast)
        assertTrue("Expected a plan for $capture, got $result", result is SimulationPlanning.Ready)
        return (result as SimulationPlanning.Ready).plan
    }

    private fun assertBlocked(reason: SimulationBlockReason, result: SimulationPlanning) {
        assertEquals(SimulationPlanning.Blocked(reason), result)
    }
}
