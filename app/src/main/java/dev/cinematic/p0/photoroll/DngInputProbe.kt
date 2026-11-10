package dev.cinematic.p0.photoroll

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log

/** Opt-in investigation only. Never logs a URI, document ID, display name or exception message. */
internal object DngInputProbe {
    private const val TAG = "DngInputProbe"
    private fun enabled(): Boolean = try { Log.isLoggable(TAG, Log.DEBUG) } catch (_: Throwable) { false }

    private inline fun log(line: () -> String) {
        if (!enabled()) return
        try { Log.d(TAG, line()) } catch (_: Throwable) { /* Observation must not change the outcome. */ }
    }

    fun grant(context: Context, uri: Uri, phase: String) = log { grantLine(context, uri, phase) }
    fun grant(context: Context, uri: String, phase: String) = log { grantLine(context, Uri.parse(uri), phase) }

    private fun grantLine(context: Context, uri: Uri, phase: String): String {
        val granted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        val authority = when (val value = uri.authority) {
            "com.android.externalstorage.documents", "com.android.providers.downloads.documents",
            "com.android.providers.media.documents", "media" -> value
            null -> "unknown"
            else -> "other"
        }
        return "phase=$phase authority=$authority uriReadGrant=$granted"
    }

    // Called with the result of the existing getType call, not an additional provider request.
    fun mime(value: String?) = log { "phase=repository.getType mime=${mimeToken(value)}" }

    fun failure(phase: String, error: Throwable) = log { "phase=$phase ${failureSummary(error)}" }

    /** Extra read-only observational open, not the source reader. Call only on an existing IO thread,
     * after admission (or after its failure is determined), never before the admission decision.
     * No bytes are consumed; SEEK_CUR with zero offset does not reposition the descriptor.
     */
    fun readOnly(context: Context, uri: Uri, phase: String, signal: CancellationSignal,
        shouldStop: () -> Boolean, separateMimeProbe: Boolean = false) {
        if (!enabled()) return
        try {
            if (signal.isCanceled || shouldStop()) return
            grant(context, uri, phase)
            if (separateMimeProbe) {
                try {
                    val mime = context.contentResolver.getType(uri)
                    log { "phase=$phase separateProbeGetType=${mimeToken(mime)}" }
                } catch (error: Throwable) { failure("$phase.probeGetType", error) }
            }
            if (signal.isCanceled || shouldStop()) return
            context.contentResolver.openAssetFileDescriptor(uri, "r", signal).use { descriptor ->
                if (descriptor == null) { log { "phase=$phase separateProbeAfd=null" }; return }
                if (signal.isCanceled || shouldStop()) return
                val seekable = try {
                    Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                    "true"
                } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.ESPIPE) "false" else "unknown_errno_${error.errno}"
                }
                log { "phase=$phase separateProbeAfd=true length=${descriptor.length}" +
                    " declaredLength=${descriptor.declaredLength} startOffset=${descriptor.startOffset}" +
                    " seekable=$seekable" }
            }
        } catch (error: Throwable) { failure("$phase.observation", error) }
    }

    internal fun failureSummary(error: Throwable): String = try {
        val causes = mutableListOf<String>()
        var current: Throwable? = error
        repeat(4) {
            val cause = current ?: return@repeat
            val frame = cause.stackTrace.take(64).firstOrNull { it.className.startsWith("dev.cinematic.p0.") }
            causes += "class=${token(cause.javaClass.name)} source=${sourceToken(frame?.fileName)}:${frame?.lineNumber ?: -1}"
            current = cause.cause?.takeUnless { it === cause }
        }
        causes.joinToString(" cause ")
    } catch (_: Throwable) { "class=unavailable source=unknown:-1" }

    private fun token(value: String?): String = when {
        value == null -> "unknown"
        value.length in 1..160 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._$-" } -> value
        else -> "redacted"
    }

    private fun sourceToken(value: String?): String = when {
        value == null -> "unknown"
        value.length <= 100 && value.matches(Regex("[A-Za-z_][A-Za-z0-9_$-]*\\.(kt|java)")) -> value
        else -> "redacted"
    }

    private fun mimeToken(value: String?): String = when (value) {
        null -> "unknown"
        "image/x-adobe-dng", "image/dng", "image/x-dng", "image/tiff", "application/octet-stream" -> value
        else -> "other"
    }
}
