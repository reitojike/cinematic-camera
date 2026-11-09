package dev.cinematic.p0.rawcapture

import android.util.Log
import dev.cinematic.p0.BuildConfig

/** Anonymous source-check intervals, never physical input-to-display latency or a fingerprint. */
internal object PreviewOriginalCheckTiming {
    enum class Route { SKIPPED_DRAFT, METADATA, SHA_NO_OBSERVATION, SHA_NO_MODIFIED, SHA_SESSION }

    fun <T> measure(route: Route,
        enabled: Boolean = enabled(),
        clock: () -> Long = System::nanoTime,
        sink: (String) -> Unit = ::log,
        check: () -> T): T {
        // Diagnostic clock/sink failures must not change verification, output or cleanup.
        val start = if (enabled) runCatching(clock).getOrNull() else null
        var success = false
        try { return check().also { success = true } }
        finally {
            if (start != null) try {
                val elapsed = if (route == Route.SKIPPED_DRAFT) 0L else clock() - start
                sink("route=$route outcome=${if (success) "SUCCESS" else "FAILED"} checkNs=$elapsed")
            } catch (_: Throwable) { /* Observation is optional. */ }
        }
    }

    private fun enabled(): Boolean = try {
        BuildConfig.EDITOR_PREVIEW_DIAGNOSTIC || Log.isLoggable("PreviewTiming", Log.DEBUG)
    } catch (_: Throwable) { false }

    private fun log(line: String) {
        if (BuildConfig.EDITOR_PREVIEW_DIAGNOSTIC) Log.i("PreviewOriginalCheck", line)
        else Log.d("PreviewOriginalCheck", line)
    }
}
