package dev.cinematic.p0.jpegrender.original

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.IOException

/** Shared fixed admission for full original native decode and its preview/save allocations.
 * Recheck at allocation boundaries; admission does not reserve memory or prove completion. */
internal object Original24Resources {
    fun requireAvailable(context: Context) {
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0
        if (memory.lowMemory || memory.availMem < 768L * 1024 * 1024 || thermal >= 2)
            throw IOException("Original rendering resource budget unavailable")
    }
}
