package dev.cinematic.p0.rawcapture

import android.content.Context
import android.util.Log
import java.io.IOException

/** Device-local aggregate only, deliberately separate from any photo/request identity. */
internal object AndroidGpuSaveFailures {
    @Synchronized fun record(context: Context, reason: GpuSaveFailureReason) {
        val preferences = context.applicationContext.getSharedPreferences("gpu-export-failures", Context.MODE_PRIVATE)
        val total = preferences.getLong("count", 0).coerceAtLeast(0)
        val count = if (total == Long.MAX_VALUE) total else total + 1
        if (!preferences.edit().putLong("count", count).putString("lastReason", reason.code).commit())
            throw IOException("GPU export failure record unconfirmed")
        Log.w("GpuExport", "failure reason=${reason.code} count=$count")
    }
}
