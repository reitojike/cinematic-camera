package dev.cinematic.p0.halfcompare

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.io.InputStream
import java.util.UUID

/** One immutable private copy. Provider access is outside OPEN, and never logged. */
internal class PerformanceInput private constructor(
    val file: File, val reference: SharedOriginalReference,
    private val verifyProvider: () -> Unit,
) : AutoCloseable {
    fun verifyOriginal() = verifyProvider()
    override fun close() {
        PerformanceCompareActivity.observe("INPUT_SOURCE_EXISTS_" + file.exists())
        check(!file.exists() || file.delete()) { "COMPARE_CLEANUP" }
        PerformanceCompareActivity.observe("INPUT_CHILD_COUNT_" + (file.parentFile!!.list()?.size ?: -1))
        check(!file.parentFile!!.exists() || file.parentFile!!.delete()) { "COMPARE_CLEANUP" }
    }
    companion object {
        /** Allowlisting is not proof of locality; the user must select a known device-local DNG. */
        fun admits(uri: Uri): Boolean = uri.scheme == "content" && uri.authority in setOf(
            "com.android.externalstorage.documents", "com.android.providers.media.documents",
            "com.android.providers.downloads.documents")

        /** Called under the process lease, so only dead-process leftovers are eligible. */
        private fun cleanAbandoned(cache: File) {
            cache.listFiles().orEmpty().filter { it.name.matches(Regex("half-performance-[0-9a-f-]{36}")) }.forEach { root ->
                try {
                    check(root.isDirectory && root.canonicalFile == root.absoluteFile)
                    val session = File(root, "session")
                    if (session.exists()) {
                        check(session.isDirectory && session.canonicalFile == session.absoluteFile)
                        for (name in listOf("source-snapshot.dng", "source-linear.rgb16")) {
                            val file = File(session, name)
                            check(!file.exists() || file.delete())
                        }
                        check(session.delete())
                    }
                    val file = File(root, "source.dng")
                    check(!file.exists() || file.delete())
                    check(root.delete())
                } catch (e: Throwable) { ComparisonLease.retain(root); throw e }
            }
        }

        fun prepare(context: Context, uri: Uri, stop: ComparisonStop,
            syntheticRead: (() -> InputStream)? = null): PerformanceInput {
            check(syntheticRead != null || admits(uri)) { "COMPARE_LOCAL_FILE_REQUIRED" }
            stop.check()
            cleanAbandoned(context.cacheDir.canonicalFile)
            check(context.cacheDir.usableSpace >= 2L * 1024 * 1024 * 1024) { "COMPARE_STORAGE" }
            val io = DngIoResources()
            val root = File(context.cacheDir.canonicalFile, "half-performance-${UUID.randomUUID()}")
            check(root.mkdir())
            val copy = File(root, "source.dng")
            fun open(): InputStream {
                stop.check()
                syntheticRead?.let { return io.input(it()) }
                val fd = checkNotNull(context.contentResolver.openFileDescriptor(uri, "r", stop.signal))
                val stream = io.input(ParcelFileDescriptor.AutoCloseInputStream(fd))
                check(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) { "COMPARE_LOCAL_FILE_REQUIRED" }
                stop.check()
                return stream
            }
            var transferred = false
            try {
                val fingerprint = DngContentVerifier.identifyBounded(64L * 1024 * 1024, ::open, stop::isStopped)
                check(copy.createNewFile())
                DngContentVerifier.copy(fingerprint, ::open, { io.output(copy.outputStream()) }, stop::isStopped)
                io.releaseAll()?.let { throw it }
                check(!io.hasOpenResources)
                return PerformanceInput(copy, SharedOriginalReference(
                    "content://dev.cinematic.p0.halfcompare/performance-snapshot", fingerprint,
                    OriginalGrant.USER_SELECTED)) {
                    try { DngContentVerifier.verify(fingerprint, ::open, stop::isStopped) }
                    finally {
                        try { io.releaseAll()?.let { throw it }; check(!io.hasOpenResources) }
                        catch (e: Throwable) { ComparisonLease.retain(io); throw e }
                    }
                }.also { transferred = true }
            } finally {
                try { io.releaseAll()?.let { throw it }; check(!io.hasOpenResources) }
                catch (e: Throwable) { ComparisonLease.retain(io); throw e }
                if (!transferred) try {
                    check(!copy.exists() || copy.delete()) { "COMPARE_CLEANUP" }
                    check(root.delete()) { "COMPARE_CLEANUP" }
                } catch (e: Throwable) { ComparisonLease.retain(listOf(io, root)); throw e }
            }
        }
    }
}