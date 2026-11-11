package dev.cinematic.p0.gpu

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure evidence tests: no Activity, injection, renderer, DNG or device operation. */
class Gate1MoveArrivalTest {
    private val downMs = 1000L
    private val endpointMs = 1800L
    private fun packet(time: Long = endpointMs, x: Float = 50f, rawX: Float = x,
        history: List<Gate1MoveArrival.Sample> = emptyList()) = Gate1MoveArrival.Packet(
        1, MotionEvent.ACTION_MOVE, downMs, Gate1MoveArrival.Sample(time, x, 60f), rawX, 60f, history, 200L)
    private fun match(vararg packets: Gate1MoveArrival.Packet) =
        Gate1MoveArrival.match(packets.toList(), downMs, endpointMs, 50f)

    @Test fun currentUsesRawXDirectly() {
        // Local NaN would corrupt a raw-local-local round trip but must not affect current identity.
        val result = match(packet(x = Float.NaN, rawX = 50f))
        assertTrue(result.accepted)
        assertEquals(null, result.match!!.historyIndex)
        assertEquals(50f, result.match!!.screenX, 0f)
    }

    @Test fun historyOnlyUsesContainingPacketOffset() {
        val current = packet(time = 1801, x = 155f, rawX = 55f,
            history = listOf(Gate1MoveArrival.Sample(endpointMs, 150f, 80f)))
        val result = match(current)
        assertTrue(result.accepted)
        assertEquals(0, result.match!!.historyIndex)
        assertEquals(50f, result.match!!.screenX, 0f)
        assertEquals(155f, result.match!!.packet.current.x, 0f)
        assertEquals(55f, result.match!!.packet.rawX, 0f)
        assertEquals(1801L, result.match!!.packet.current.eventMs)
    }

    @Test fun uniqueCurrentIgnoresUnrelatedPacketsAndY() {
        val valid = packet().copy(current = packet().current.copy(y = Float.NaN), rawY = Float.POSITIVE_INFINITY)
        val unrelated = listOf(packet().copy(action = MotionEvent.ACTION_CANCEL),
            packet().copy(action = MotionEvent.ACTION_UP), packet().copy(downMs = 999),
            packet(time = 1799, x = Float.NaN))
        assertTrue(match(*(unrelated + valid).toTypedArray()).accepted)
        assertTrue(match(packet(history = listOf(Gate1MoveArrival.Sample(1799, Float.NaN, Float.NaN)))).accepted)
    }

    @Test fun wrongDownTimeTimeAndXDoNotMatch() {
        assertFalse(match(packet().copy(downMs = 999)).accepted)
        assertFalse(match(packet(time = 1799)).accepted)
        assertFalse(match(packet(rawX = 52f)).accepted)
        assertTrue(match(packet(rawX = 51f)).accepted) // unchanged inclusive 1px identity
        assertFalse(match(packet(rawX = Float.NaN)).accepted)
        assertFalse(match(packet(time = 1801,
            history = listOf(Gate1MoveArrival.Sample(1799, 50f, 60f)))).accepted)
        assertFalse(match(packet(time = 1801,
            history = listOf(Gate1MoveArrival.Sample(endpointMs, 52f, 60f)))).accepted)
    }

    @Test fun missingAndDuplicateSamplesRemainUnproven() {
        assertEquals("missing_endpoint", match().status)
        val current = packet()
        val both = match(current.copy(history = listOf(current.current)))
        assertEquals("ambiguous_endpoint", both.status)
        assertEquals(2, both.candidates.size)
        assertEquals(null, both.match)
        val history = packet(time = 1801, history = listOf(current.current))
        val repeated = match(history, history.copy(sequence = 2, receivedNs = 300))
        assertEquals("ambiguous_endpoint", repeated.status)
        assertEquals(null, repeated.match)
        assertFalse(Gate1MoveArrival.renderAfterArrival(repeated, 1000))
    }

    @Test fun originalFortyTwoEventTraceHasOneFinalMove() {
        val trace = listOf(packet(time = downMs).copy(action = MotionEvent.ACTION_DOWN)) +
            (1..40).map { index -> packet(time = downMs + index * 20).copy(sequence = index) } +
            packet().copy(sequence = 41, action = MotionEvent.ACTION_UP)
        assertEquals(42, trace.size)
        assertEquals(800L, trace.last().current.eventMs - trace.first().current.eventMs)
        val result = match(*trace.toTypedArray())
        assertTrue(result.accepted)
        assertEquals(40, result.match!!.packet.sequence)
    }

    @Test fun arrivalDoesNotProveRenderTimingOrReturnValue() {
        val result = match(packet(time = 1801, history = listOf(Gate1MoveArrival.Sample(endpointMs, 50f, 60f))))
        assertTrue(result.accepted)
        assertFalse(Gate1MoveArrival.renderAfterArrival(result, 199))
        assertTrue(Gate1MoveArrival.renderAfterArrival(result, 200)) // containing packet receipt
        val actualRenderedReturn = 0.56
        val baseline = 0.50
        val frozenTolerance = 0.01
        assertFalse(kotlin.math.abs(actualRenderedReturn - baseline) <= frozenTolerance)
        assertTrue(result.accepted)
    }
}
