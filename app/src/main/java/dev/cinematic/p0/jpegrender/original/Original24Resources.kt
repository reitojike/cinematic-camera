package dev.cinematic.p0.jpegrender.original

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.IOException

/** Read current OS pressure at allocation boundaries. No reservation or guarantee of completion.
 * Full decode/save retain their conservative allowance; reduced preview work has a separate gate. */
internal object Original24Resources {
    fun requireAvailable(context: Context, stage: Original24MemoryStage = Original24MemoryStage.ORIGINAL_SAVE) {
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val thermal = if (Build.VERSION.SDK_INT >= 29)
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else 0
        if (!Original24MemoryPolicy.permits(stage, memory.lowMemory, memory.availMem))
            throw Original24MemoryUnavailable()
        if (thermal !in 0..1) throw IOException("Original rendering thermal budget unavailable")
    }
}
