package dev.cinematic.p0.halfcompare

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.opengl.GLES32
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.jpegrender.original.PreviewRasterMode
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.DngIoResources
import dev.cinematic.p0.rawcapture.DngContentVerifier
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean


internal class ComparisonStop {
    private val cancelled = AtomicBoolean(false)
    val signal = CancellationSignal()
    fun cancel() { cancelled.set(true); signal.cancel() }
    fun isStopped(): Boolean = cancelled.get()
    fun check() { if (isStopped()) throw CancellationException() }
}

/** Read-only, single selected photo; never calls publication, export, EXIF writes or product state. */
internal class PrivatePhotoComparison(
    private val context: Context,
    // Stream override is test-only; real input must be a regular descriptor, not a pipe.
    // A regular proxy FD can still stall inside read; no bounded cancellation guarantee is claimed.
    private val openRead: ((Uri) -> java.io.InputStream)? = null,
    private val openDescriptor: (Uri, CancellationSignal) -> ParcelFileDescriptor = { uri, signal ->
        checkNotNull(context.contentResolver.openFileDescriptor(uri, "r", signal))
    },
    private val onGpuStage: (GpuStageTap.Point) -> Unit = {},
) {
    class Pair(val full: Bitmap, val half: Bitmap) : AutoCloseable {
        override fun close() { full.recycle(); half.recycle() }
    }

    fun compare(uri: Uri, stop: ComparisonStop, exposureEv: Double, progress: (String) -> Unit): Pair = ComparisonLease.acquire().use { compareOwned(uri, stop, exposureEv, progress) }

    private fun compareOwned(uri: Uri, stop: ComparisonStop, exposureEv: Double, progress: (String) -> Unit): Pair {
        require(exposureEv == 0.0 || exposureEv == -1.0 || exposureEv == -2.0)
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        check(!memory.lowMemory) { "COMPARE_MEMORY" }
        check(context.cacheDir.usableSpace >= 2L * 1024 * 1024 * 1024) { "COMPARE_STORAGE" }
        check(!cleanupBlocked) { "COMPARE_CLEANUP" }
        val privateCache = context.cacheDir.canonicalFile
        clearAbandoned(privateCache)
        val work = File(privateCache, "private-half-compare-${UUID.randomUUID()}")
        check(work.mkdir())
        val source = File(work, "source.dng")
        val io = DngIoResources()
        fun providerInput(): java.io.InputStream {
            stop.check()
            openRead?.let { return io.input(it(uri)) }
            val descriptor = openDescriptor(uri, stop.signal)
            val input = io.input(ParcelFileDescriptor.AutoCloseInputStream(descriptor))
            check(android.system.OsConstants.S_ISREG(android.system.Os.fstat(descriptor.fileDescriptor).st_mode)) {
                "COMPARE_LOCAL_FILE_REQUIRED"
            }
            stop.check()
            return input
        }
        var sessionOwner: Original24PreviewSession? = null
        var failure: Throwable? = null
        var full: Bitmap? = null
        var half: Bitmap? = null
        try {
            progress("原本を読み取っています。原本は変更しません。")
            stop.check()
            check(source.createNewFile())
            val input = providerInput()
            input.use { from -> io.output(source.outputStream().buffered()).use { to ->
                val bytes = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    stop.check()
                    val n = from.read(bytes)
                    if (n < 0) break
                    check(n > 0) { "COMPARE_READ" }
                    total += n
                    check(total <= 64L * 1024 * 1024) { "COMPARE_LIMIT" }
                    to.write(bytes, 0, n)
                }
            } }
            val fingerprint = DngContentVerifier.identify(source.length(), { io.input(source.inputStream()) }, stop::isStopped)
            val geometry = io.useOwned(ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)) {
                Original24Preflight.inspect(it.fd, stop.signal)
            }
            // Do not upsample a small half raster or compare different display dimensions.
            val fullSize = GpuPreviewInputSize.fit(geometry.outputWidth, geometry.outputHeight)
            val halfSize = GpuPreviewInputSize.fit((geometry.outputWidth + 1) / 2, (geometry.outputHeight + 1) / 2)
            check(fullSize == halfSize) { "COMPARE_SIZE" }
            // Session reads only the private snapshot; never retains the provider URI.
            val reference = SharedOriginalReference("content://dev.cinematic.p0.halfcompare/private-snapshot",
                fingerprint, OriginalGrant.USER_SELECTED)
            fun decode(half: Boolean): LinearSrgbImage {
                stop.check()
                val session = Original24PreviewSession(reference, File(work, "session"), {
                    val current = ActivityManager.MemoryInfo().also {
                        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
                    }
                    check(!current.lowMemory) { "COMPARE_MEMORY" }
                }, geometry, rasterMode = if (half) PreviewRasterMode.HALF_CANDIDATE else PreviewRasterMode.FULL) {
                    source.inputStream()
                }
                sessionOwner = session
                stop.signal.setOnCancelListener { session.requestStop() }
                try {
                    return session.use { it.loadGpuAsShotInput(stop::isStopped) }.also {
                        check(!session.hasUnreleasedResources)
                        sessionOwner = null
                    }
                } finally {
                    stop.signal.setOnCancelListener(null)
                }
            }
            progress("FULL を準備しています。")
            full = render(decode(false), exposureEv, stop)
            progress("HALF を準備しています。")
            half = render(decode(true), exposureEv, stop)
            check(full.width == half.width && full.height == half.height)
            stop.check()
            // The private snapshot and provider must still match the same selected original.
            DngContentVerifier.verify(fingerprint, { io.input(source.inputStream()) }, stop::isStopped)
            DngContentVerifier.verify(fingerprint, { providerInput() }, stop::isStopped)
            stop.check()
            check(!source.exists() || source.delete()) { "COMPARE_CLEANUP" }
            check(work.delete()) { "COMPARE_CLEANUP" }
            return Pair(checkNotNull(full), checkNotNull(half)).also { full = null; half = null }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            full?.recycle(); half?.recycle()
            try {
                io.releaseAll()?.let { throw it }
                check(!io.hasOpenResources) { "COMPARE_CLEANUP" }
                sessionOwner?.close()
                check(sessionOwner?.hasUnreleasedResources != true) { "COMPARE_CLEANUP" }
                sessionOwner = null
                cleanOwned(work)
            } catch (cleanup: Throwable) {
                cleanupBlocked = true
                ComparisonLease.retain(listOfNotNull(io, sessionOwner, work))
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }


    companion object {
        @Volatile var cleanupBlocked = false; private set
        /** Called only on the process-wide serial comparison executor, never while another attempt owns files. */
        private fun clearAbandoned(cache: File) {
            cache.listFiles()?.filter { it.name.matches(Regex("private-half-compare-[0-9a-f-]{36}")) }
                ?.forEach(::cleanOwned)
        }
        private fun cleanOwned(work: File) {
            if (!work.exists()) return
            check(work.isDirectory && work.canonicalFile == work.absoluteFile) { "COMPARE_CLEANUP" }
            val session = File(work, "session")
            if (session.exists()) {
                check(session.isDirectory && session.canonicalFile == session.absoluteFile) { "COMPARE_CLEANUP" }
                for (name in listOf("source-snapshot.dng", "source-linear.rgb16")) {
                    val file = File(session, name)
                    check(!file.exists() || file.delete()) { "COMPARE_CLEANUP" }
                }
                check(session.delete()) { "COMPARE_CLEANUP" }
            }
            for (name in listOf("source.dng")) {
                val file = File(work, name)
                check(!file.exists() || file.delete()) { "COMPARE_CLEANUP" }
            }
            check(work.delete()) { "COMPARE_CLEANUP" }
        }
    }

    private fun render(input: LinearSrgbImage, exposureEv: Double, stop: ComparisonStop): Bitmap = GlThread().use { thread ->
        thread.call {
            stop.check()
            EglContexts(thread, context.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                val ledger = GpuMemoryLedger(GpuMemoryLedger.PREVIEW_BUDGET)
                var output: IntArray? = null
                DefaultStageClearGpu(egl, ledger) { texture, analysis ->
                    val connected = ConnectedStageClearGpuPipeline(texture, analysis, GpuFrameMode.FULL_RECOMPUTE)
                    object : StageClearGpuPipeline {
                        override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
                            consume: (GpuTexture, GpuDecisions) -> Unit) = connected.render(params, { point, image ->
                            stop.check()
                            onGpuStage(point)
                            stop.check()
                            if (point == GpuStageTap.Point.DISPLAY8) output = read(egl, image, stop)
                            tap?.invoke(point, image)
                        }, consume)
                        override fun close() = connected.close()
                    }
                }.use { gpu -> gpu.openPhoto(input, stop::isStopped).use { photo ->
                    gpu.renderForTest(photo, GpuRenderParams(.5, PhotoAdjustments(0.0, exposureEv)), GpuStageTap.Point.SHOULDER)
                } }
                check(ledger.textureCount == 0 && ledger.allocatedBytes == 0L)
                stop.check()
                Bitmap.createBitmap(checkNotNull(output), input.width, input.height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    private fun read(egl: EglContexts, image: GpuTexture, stop: ComparisonStop): IntArray {
        check(image.format == GpuTexture.Format.RGBA8)
        GpuFence(egl).use { it.await(stop::isStopped) }
        val bytes = ByteBuffer.allocateDirect(image.width * image.height * 4)
        GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, image.framebufferId)
        GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
        GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
        GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
        GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
        GLES32.glReadPixels(0, 0, image.width, image.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, bytes)
        egl.checkGl("Private preview comparison")
        return IntArray(image.width * image.height) { p ->
            (255 shl 24) or ((bytes.get(p * 4).toInt() and 255) shl 16) or
                ((bytes.get(p * 4 + 1).toInt() and 255) shl 8) or (bytes.get(p * 4 + 2).toInt() and 255)
        }
    }
}
