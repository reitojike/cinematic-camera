package dev.cinematic.p0.gpu

import android.os.Bundle

/** Test-only execution bounds; accepting an argument does not grant device-operation approval. */
internal data class GpuTimingOptions(val allowDevice: Boolean, val measuredFrames: Int, val emulator: Boolean) {
    companion object {
        fun parse(args: Bundle, hardware: String): GpuTimingOptions {
            check(args.getString("gpuPerformance") == "true") { "Explicit gpuPerformance=true required" }
            check(args.getString("isolated") == "true") { "Synthetic isolated run required" }
            val allowDevice = when (args.getString("allowDevice")) {
                null, "false" -> false
                "true" -> true
                else -> error("allowDevice must be true or false")
            }
            val rawFrames = args.getString("measuredFrames")
            val frames = if (rawFrames == null) 3 else {
                check(rawFrames.matches(Regex("[1-9][0-9]*"))) { "measuredFrames must be an integer in 1..30" }
                checkNotNull(rawFrames.toIntOrNull()) { "measuredFrames must be an integer in 1..30" }
            }
            check(frames in 1..30) { "measuredFrames must be in 1..30" }
            val emulator = hardware in setOf("ranchu", "goldfish")
            check(emulator || allowDevice) { "Non-emulator execution requires explicit allowDevice=true and separate owner approval" }
            return GpuTimingOptions(allowDevice, frames, emulator)
        }
    }
}
