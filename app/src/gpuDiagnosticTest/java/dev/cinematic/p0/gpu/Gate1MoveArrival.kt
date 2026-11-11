package dev.cinematic.p0.gpu

import android.view.MotionEvent
import kotlin.math.abs

/** Test-only arrival identity, not evidence of a Slider current value/onValueChange.
 * MotionEvent history is local: https://developer.android.com/reference/android/view/MotionEvent#getHistoricalX(int)
 * Historical X uses the containing packet's rawX - localX translation. This diagnostic assumes
 * a fixed, unscaled/unrotated Window during the gesture; this is not a general affine conversion.
 * Current samples use rawX directly, without a subtract/add round trip.
 */
internal object Gate1MoveArrival {
    data class Sample(val eventMs: Long, val x: Float, val y: Float)
    data class Packet(
        val sequence: Int, val action: Int, val downMs: Long, val current: Sample,
        val rawX: Float, val rawY: Float, val history: List<Sample>, val receivedNs: Long,
    )
    data class Match(val packet: Packet, val historyIndex: Int?, val sample: Sample, val screenX: Float)
    data class Result(val candidates: List<Match>) {
        val accepted get() = candidates.size == 1
        val status get() = when (candidates.size) { 0 -> "missing_endpoint"; 1 -> "arrived"; else -> "ambiguous_endpoint" }
        val match get() = candidates.singleOrNull()
    }

    fun match(packets: List<Packet>, downMs: Long, endpointMs: Long, x: Float): Result = Result(
        packets.filter { it.action == MotionEvent.ACTION_MOVE && it.downMs == downMs }.flatMap { packet ->
            fun candidate(sample: Sample, historyIndex: Int?, screenX: Float): Match? =
                if (sample.eventMs == endpointMs && abs(screenX - x) <= 1f) {
                    Match(packet, historyIndex, sample, screenX)
                } else null
            listOfNotNull(candidate(packet.current, null, packet.rawX)) +
                packet.history.mapIndexedNotNull { index, sample ->
                    candidate(sample, index, sample.x + (packet.rawX - packet.current.x))
                }
        }
    )

    fun renderAfterArrival(result: Result, renderBeginNs: Long): Boolean =
        result.match?.let { renderBeginNs >= it.packet.receivedNs } == true
}
