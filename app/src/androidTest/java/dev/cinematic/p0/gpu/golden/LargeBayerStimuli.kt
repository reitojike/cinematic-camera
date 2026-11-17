package dev.cinematic.p0.gpu.golden

import kotlin.math.roundToInt

/** Direct RAW-coordinate stimuli. No full RGB buffer, resize or camera calibration claim. */
internal object LargeBayerStimuli {
    enum class Kind { TONES, COLOR_PATCHES, MICRO_DETAIL, LIGHTS, NOISE, MIXED }

    fun sample(kind: Kind, x: Int, y: Int, width: Int, height: Int): Int {
        val channel = if (y and 1 == 0) { if (x and 1 == 0) 0 else 1 } else { if (x and 1 == 0) 1 else 2 }
        val value = value(kind, x, y, width, height, channel)
        return (64.0 + value.coerceIn(0.0, 1.0) * 4031.0).roundToInt()
    }

    private fun value(kind: Kind, x: Int, y: Int, width: Int, height: Int, channel: Int): Double = when (kind) {
        Kind.TONES -> {
            // Same normalized ramp and dark region at both resolutions.
            val ramp = x.toDouble() / (width - 1)
            if (y < height / 2) ramp else ramp * 0.025
        }
        Kind.COLOR_PATCHES -> {
            val patch = (x * 6L / width).toInt().coerceAtMost(5)
            if (y < height / 2) {
                val bright = when (patch) {
                    0 -> channel == 0; 1 -> channel == 1; 2 -> channel == 2
                    3 -> channel != 0; 4 -> channel != 1; else -> channel != 2
                }
                if (bright) 0.65 else 0.03
            } else {
                // Warm camera-channel gradient, not calibrated human skin.
                val gradient = 0.4 + 0.6 * x.toDouble() / (width - 1)
                gradient * when (channel) { 0 -> 0.45; 1 -> 0.24; else -> 0.17 }
            }
        }
        Kind.MICRO_DETAIL -> {
            val period = when {
                y < height / 2 && x < width / 2 -> 1
                y < height / 2 -> 2
                x < width / 2 -> 4
                else -> 8
            }
            // RAW-domain 1/2/4/8-pixel bars and a diagonal boundary; not upscaled thumbnails.
            val stripe = (x / period + y / (period * 3)) and 1
            if (stripe == 0) 0.015 else 0.8
        }
        Kind.LIGHTS -> {
            var light = false
            for (i in 0..3) {
                val cx = width * (i + 1) / 5
                val cy = height * (if (i and 1 == 0) 1 else 2) / 3
                val radius = when (i) { 0 -> 4; 1 -> 16; 2 -> 64; else -> 128 }
                val dx = x - cx; val dy = y - cy
                if (dx.toLong() * dx + dy.toLong() * dy <= radius.toLong() * radius) light = true
            }
            if (light) 1.0 else 0.012
        }
        Kind.NOISE -> {
            // Bounded uniform scalar RAW noise. It is not a sensor/ISO noise model.
            var h = x * 73856093 xor (y * 19349663) xor 20261116
            h = (h xor (h ushr 16)) * 0x45d9f3b
            h = (h xor (h ushr 16)) * 0x45d9f3b
            val noise = ((h xor (h ushr 16)) ushr 8).toDouble() / 16777215.0 - 0.5
            if (x < width / 2) 0.01 + noise * 0.01 else 0.2 + noise * 0.06
        }
        Kind.MIXED -> value(when {
            x < width / 2 && y < height / 2 -> Kind.TONES
            y < height / 2 -> Kind.LIGHTS
            x < width / 2 -> Kind.COLOR_PATCHES
            else -> Kind.MICRO_DETAIL
        }, x, y, width, height, channel)
    }
}