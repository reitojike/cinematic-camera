package dev.cinematic.p0.previewcompare

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
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import dev.cinematic.p0.raw.original.OriginalFields
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.rawcapture.DngContentVerifier
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

internal object NativeCompareDecoder {
    init { System.loadLibrary("cinematic_dng") }
    external fun decodePpg(sourceFd: Int, outputFd: Int, signal: CancellationSignal): String
}

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
    private val openRead: (Uri) -> java.io.InputStream = { checkNotNull(context.contentResolver.openInputStream(it)) },
    private val onGpuStage: (GpuStageTap.Point) -> Unit = {},
) {
    class Pair(val ahd: Bitmap, val ppg: Bitmap) : AutoCloseable {
        override fun close() { ahd.recycle(); ppg.recycle() }
    }

    fun compare(uri: Uri, stop: ComparisonStop, exposureEv: Double, progress: (String) -> Unit): Pair {
        require(exposureEv == 0.0 || exposureEv == -1.0 || exposureEv == -2.0)
        val memory = ActivityManager.MemoryInfo().also {
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
        }
        check(!memory.lowMemory) { "COMPARE_MEMORY" }
        check(context.cacheDir.usableSpace >= 512L * 1024 * 1024) { "COMPARE_STORAGE" }
        check(!cleanupBlocked) { "COMPARE_CLEANUP" }
        val privateCache = context.cacheDir.canonicalFile
        clearAbandoned(privateCache)
        val work = File(privateCache, "private-compare-${UUID.randomUUID()}")
        check(work.mkdir())
        val source = File(work, "source.dng")
        val rasterFile = File(work, "raster.rgb16")
        var failure: Throwable? = null
        var owner: OriginalRasterFile? = null
        var ahd: Bitmap? = null
        var ppg: Bitmap? = null
        try {
            progress("原本を読み取っています。原本は変更しません。")
            stop.check()
            check(source.createNewFile())
            val input = openRead(uri)
            input.use { from -> source.outputStream().buffered().use { to ->
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
            val fingerprint = DngContentVerifier.identify(source.length(), { source.inputStream() }, stop::isStopped)
            val geometry = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use {
                Original24Preflight.inspect(it.fd, stop.signal)
            }
            fun decode(ppg: Boolean): LinearSrgbImage {
                stop.check()
                check(rasterFile.createNewFile())
                run {
                    ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { from ->
                        ParcelFileDescriptor.open(rasterFile, ParcelFileDescriptor.MODE_READ_WRITE).use { to ->
                            val report = JSONObject(if (ppg) NativeCompareDecoder.decodePpg(from.fd, to.fd, stop.signal)
                                else NativeOriginalRaster.decode24(from.fd, to.fd, stop.signal))
                            check(report.getInt("halfSize") == 0 && report.getInt("width") == geometry.outputWidth &&
                                report.getInt("height") == geometry.outputHeight && report.getInt("bits") == 16 &&
                                report.getInt("channels") == 3 && report.getString("color") == "linear-sRGB-D65" &&
                                rasterFile.length() == geometry.outputWidth.toLong() * geometry.outputHeight * 6)
                        }
                    }
                    stop.check()
                    val raster = OriginalRasterFile(rasterFile, geometry.outputWidth, geometry.outputHeight, 2).also { owner = it }
                    val reduced = try {
                        val size = GpuPreviewInputSize.fit(raster.width, raster.height)
                        OriginalFields.reduce(raster, size.width, size.height, 3, stop::isStopped) { rgb, target ->
                            rgb.copyInto(target)
                        }.reducedRgb()
                    } catch (error: Throwable) {
                        try { raster.closePending(); owner = null } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                        throw error
                    }
                    raster.closePending(); owner = null
                    check(rasterFile.delete()) { "COMPARE_CLEANUP" }
                    return reduced
                }
            }
            progress("AHD を準備しています。")
            ahd = render(decode(false), exposureEv, stop)
            progress("PPG を準備しています。")
            ppg = render(decode(true), exposureEv, stop)
            stop.check()
            // The private snapshot and provider must still match the same selected original.
            DngContentVerifier.verify(fingerprint, { source.inputStream() }, stop::isStopped)
            DngContentVerifier.verify(fingerprint, { openRead(uri) }, stop::isStopped)
            stop.check()
            check(!source.exists() || source.delete()) { "COMPARE_CLEANUP" }
            check(work.delete()) { "COMPARE_CLEANUP" }
            return Pair(checkNotNull(ahd), checkNotNull(ppg)).also { ahd = null; ppg = null }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            ahd?.recycle(); ppg?.recycle()
            try {
                owner?.closePending(); owner = null
                cleanOwned(work)
            } catch (cleanup: Throwable) {
                cleanupBlocked = true
                failure?.addSuppressed(cleanup) ?: throw cleanup
            }
        }
    }


    companion object {
        @Volatile var cleanupBlocked = false; private set
        /** Called only on the process-wide serial comparison executor, never while another attempt owns files. */
        private fun clearAbandoned(cache: File) {
            cache.listFiles()?.filter { it.name.matches(Regex("private-compare-[0-9a-f-]{36}")) }
                ?.forEach(::cleanOwned)
        }
        private fun cleanOwned(work: File) {
            if (!work.exists()) return
            check(work.isDirectory && work.canonicalFile == work.absoluteFile) { "COMPARE_CLEANUP" }
            for (name in listOf("raster.rgb16", "source.dng")) {
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
