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
class SaveTimingProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "text/tab-separated-values"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireNotNull(context)
        app.enforceCallingOrSelfPermission("android.permission.DUMP", "Shell diagnostics only")
        if (Binder.getCallingUid() != 2000) throw SecurityException("Shell diagnostics only")
        if (mode != "r" || uri.toString() != "content://${app.packageName}.save-timing/records")
            throw FileNotFoundException("Unknown read-only timing snapshot")
        val bytes = timingStore(app).snapshot()
        return openPipeHelper(uri, getType(uri), null, bytes) { output, _, _, _, snapshot ->
            try { ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(snapshot) } }
            catch (_: Exception) { /* Disconnected reader does not affect saves. */ }
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}

internal fun timingStore(context: Context) = SaveTimingStore(File(context.noBackupFilesDir, "save-timing"))
internal fun startSaveTiming(context: Context, kind: SaveTimingKind): SaveTiming? = try {
    val store = timingStore(context.applicationContext)
    SaveTiming.start(BuildConfig.VERSION_CODE, kind, store::appendAll)
} catch (_: Throwable) { null }
