package dev.cinematic.p0

import kotlin.math.abs
import kotlin.math.ln

data class ShutterStop(val label: String, val exposureNs: Long)
data class ExposureChoices(val iso: List<Int>, val shutter: List<ShutterStop>) {
    fun pair(isoValue: Int, ns: Long): ManualExposure? {
        if (iso.isEmpty() || shutter.isEmpty() || isoValue <= 0 || ns <= 0) return null
        return ManualExposure(iso.minBy { abs(ln(it.toDouble() / isoValue)) },
            shutter.minBy { abs(ln(it.exposureNs.toDouble() / ns)) }.exposureNs)
    }
}

object ExposureStops {
    /** Conventional labels, not a rounded 2^(n/3) series. */
    val shutters: List<ShutterStop> = listOf(32000, 25000, 20000, 16000, 12800, 10000, 8000, 6400,
        5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200,
        160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4).map {
        ShutterStop("1/$it s", 1_000_000_000L / it)
    } + listOf("0.3", "0.4", "0.5", "0.6", "0.8", "1", "1.3", "1.6", "2", "2.5", "3.2",
        "4", "5", "6", "8", "10", "13", "15", "20", "25", "30").map {
        ShutterStop("$it s", java.math.BigDecimal(it).movePointRight(9).longValueExact())
    }
    val sensitivities: List<Int> = listOf(6, 12) + generateSequence(25L) { previous ->
        (previous * 2).takeIf { it <= Int.MAX_VALUE }
    }.map { it.toInt() }.toList()

    fun choices(isoRange: IntRange?, exposureRange: LongRange?, maxFrameNs: Long?, fps: Int?): ExposureChoices {
        val maximum = listOfNotNull(NormalExposurePolicy.MAX_EXPOSURE_NS, exposureRange?.last, maxFrameNs?.takeIf { it > 0 },
            fps?.takeIf { it > 0 }?.let { 1_000_000_000L / it }).minOrNull()
        return ExposureChoices(sensitivities.filter { isoRange?.contains(it) == true }, shutters.filter {
            exposureRange?.contains(it.exposureNs) == true && maximum != null && it.exposureNs <= maximum
        })
    }

    fun label(ns: Long?): String = ns?.let { value ->
        shutters.firstOrNull { it.exposureNs == value }?.label ?: if (value < 250_000_000L && value > 0) {
            "≈1/${kotlin.math.round(1_000_000_000.0 / value).toLong().coerceAtLeast(1)} s"
        } else java.math.BigDecimal.valueOf(value, 9).stripTrailingZeros().toPlainString() + " s"
    } ?: "—"
}
