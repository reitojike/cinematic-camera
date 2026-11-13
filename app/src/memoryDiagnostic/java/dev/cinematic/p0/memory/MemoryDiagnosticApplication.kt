package dev.cinematic.p0.memory

import android.app.ActivityManager
import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log

/** Only the separate measurement variant installs this Application. Never read exit traces/descriptions. */
class MemoryDiagnosticApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!MemoryAdmissionDiagnostic.enabled) return
        try {
            Log.i("CinematicMemory", "PROCESS_START pid=${Process.myPid()} elapsedMs=${SystemClock.elapsedRealtime()} epochMs=${System.currentTimeMillis()}")
            val manager = getSystemService(ActivityManager::class.java)
            // Own package only. Historical entries may predate this session: correlate timestamp, do not infer causality.
            for (exit in manager.getHistoricalProcessExitReasons(packageName, 0, 8)) {
                Log.i("CinematicMemory", "OWN_PROCESS_EXIT pid=${exit.pid} epochMs=${exit.timestamp} reason=${exit.reason} status=${exit.status} pssKiB=${exit.pss} rssKiB=${exit.rss}")
            }
        } catch (_: Throwable) { /* Telemetry cannot replace application startup. */ }
    }
}
