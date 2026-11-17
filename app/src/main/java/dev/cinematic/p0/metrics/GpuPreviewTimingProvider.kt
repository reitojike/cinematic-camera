package dev.cinematic.p0.metrics

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.BuildConfig
import java.io.File
import java.io.FileNotFoundException

/** USB-authorized adb shell can read one fixed, sanitized snapshot in non-debuggable builds. */
class GpuPreviewTimingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "text/tab-separated-values"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireNotNull(context)
        app.enforceCallingOrSelfPermission("android.permission.DUMP", "Shell diagnostics only")
        if (Binder.getCallingUid() != 2000) throw SecurityException("Shell diagnostics only")
        if (mode != "r" || uri.toString() != "content://${app.packageName}.gpu-preview-timing/records")
            throw FileNotFoundException("Unknown read-only timing snapshot")
        val bytes = gpuPreviewTimingStore(app).snapshot()
        return openPipeHelper(uri, getType(uri), null, bytes) { output, _, _, _, snapshot ->
            try { ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(snapshot) } }
            catch (_: Exception) { /* Disconnected reader does not affect preview. */ }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}

internal fun gpuPreviewTimingStore(context: Context) = GpuPreviewTimingStore(File(context.noBackupFilesDir, "gpu-preview-timing"))
/** At most one active write and four pending batches; dropping diagnostics cannot stall preview. */
private object PreviewTimingWriter {
    private val executor = java.util.concurrent.ThreadPoolExecutor(1, 1, 15, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(4),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "preview-timing-writer").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()).apply { allowCoreThreadTimeOut(true) }
    fun append(context: Context, records: List<GpuPreviewTimingRecord>) = timingBestEffort {
        executor.execute { timingBestEffort { gpuPreviewTimingStore(context).appendAll(records) } }
    }
}
internal fun startGpuPreviewTiming(context: Context): GpuPreviewTiming? = try {
    val app = context.applicationContext
    GpuPreviewTiming.start(BuildConfig.VERSION_CODE, { records -> PreviewTimingWriter.append(app, records) })
} catch (_: Throwable) { null }
