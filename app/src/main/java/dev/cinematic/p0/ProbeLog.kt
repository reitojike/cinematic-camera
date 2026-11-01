package dev.cinematic.p0

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.time.Instant

class ProbeLog(context: Context) {
    val file = File(context.filesDir, "reports/p0-${System.currentTimeMillis()}.txt")
    var onLine: (String) -> Unit = {}
    init {
        try { file.parentFile?.mkdirs() } catch (_: Exception) { /* write() reports failure */ }
        write("DEVICE manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} build=${Build.DISPLAY} fingerprint=${Build.FINGERPRINT} app=${BuildConfig.VERSION_NAME} CameraX=1.6.2")
    }
    @Synchronized fun write(message: String) {
        val line = "${Instant.now()} $message"
        Log.i("CinematicP0", line)
        try { file.appendText("$line\n") } catch (e: Exception) {
            Log.e("CinematicP0", "Report write failed: ${e.javaClass.simpleName}")
            onLine("ERROR Report write failed: ${e.javaClass.simpleName}\n$line")
            return
        }
        onLine(line)
    }
}
