package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.gpu.GpuPreviewInputSize
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.original.NativeOriginalRaster
import dev.cinematic.p0.raw.original.OriginalFields
import dev.cinematic.p0.raw.original.OriginalRasterFile
import dev.cinematic.p0.raw.original.originalCheckpoint
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.DngIoResources
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import org.json.JSONObject

/** One caller-admitted IO session, never a queue/slot, saved image or face-protected completion.
 * The first render decodes an immutable, fingerprint-bound private snapshot to row-readable RGB16,
 * reduces it, closes all owners and deletes both files before retaining <=1024 AS_SHOT input.
 * Returns one immutable bounded AS_SHOT image; CPU look/Bitmap/draft retention is retired.
 * Source/capture/lifecycle invalidation and resource/memory admission belong to the existing factory.
 * A failed or stopped session never silently acquires a fresh decode attempt. */
internal class Original24PreviewSession(
    val source: SharedOriginalReference,
    private val scratchDirectory: File,
    private val resourceAdmission: (Original24MemoryStage) -> Unit,
    private val expectedGeometry: Original24PreflightGeometry,
    /** Optional provider-metadata fence supplied by the checked photo selection. Saves never use it. */
    private val checkPreviewSource: ((DngIoResources, () -> Boolean) -> Unit)? = null,
    private val openOriginal: () -> InputStream,
) : AutoCloseable {
    private val active = AtomicBoolean(false)
    private val signal = CancellationSignal()
    @Volatile private var stopped = false
    @Volatile private var closed = false
    private var failed = false
    private var decodeStarted = false
    private var resources: DngIoResources? = null
    private var sourceFd: ParcelFileDescriptor? = null
    private var rasterFd: ParcelFileDescriptor? = null
    private var raster: OriginalRasterFile? = null
    private var ownsDirectory = false
    private var ownsSnapshot = false
    private var ownsRaster = false
    private val snapshotFile get() = File(scratchDirectory, "source-snapshot.dng")
    private val rasterFile get() = File(scratchDirectory, "source-linear.rgb16")

    val hasUnreleasedResources get() = active.get() || sourceFd != null || rasterFd != null ||
        resources?.hasOpenResources == true || raster?.hasPendingOwners == true ||
        ownsDirectory || ownsSnapshot || ownsRaster

    init { require(source.fingerprint.bytes <= 64L * 1024 * 1024) }

    /** Terminal cancellation; a replacement session is a separate factory admission decision. */
    fun requestStop() { stopped = true; signal.cancel() }

    /**
     * GPU input only. Reuse the exact snapshot/decode/RGB16/BOX path below; do not
     * calculate a CPU look or allocate a Bitmap. The caller owns this one-shot session and must
     * close it even after failure, so failed descriptor/file closes retain their existing owner.
     * The immutable returned image has no file/descriptor ownership and survives session.close().
     * Select both GPU limits before the sole BOX reduction; no CPU preview entry remains.
     */
    internal fun loadGpuAsShotInput(shouldStop: () -> Boolean = { false }): LinearSrgbImage = exclusive {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Original preview requires existing IO owner" }
        check(!closed && !failed && !stopped) { "Original preview session is unavailable" }
        val stop = { stopped || signal.isCanceled || shouldStop() }
        var failure: Throwable? = null
        try {
            loadInput(stop, GpuPreviewInputSize::fit)
        } catch (error: Throwable) {
            failed = true
            failure = error
            if (error is OutOfMemoryError) throw Original24MemoryUnavailable(error)
            throw error
        } finally {
            try { releaseTransient() }
            catch (cleanup: Throwable) {
                failed = true
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private fun loadInput(stop: () -> Boolean,
        selectSize: (Int, Int) -> GpuPreviewInputSize.Size = { width, height ->
            val scale = min(1.0, 1024.0 / max(width, height))
            GpuPreviewInputSize.Size(floor(width * scale).toInt(), floor(height * scale).toInt())
        }): LinearSrgbImage {
        if (decodeStarted) throw IOException("Original preview decode cannot be restarted")
        decodeStarted = true
        originalCheckpoint(stop)
        if (scratchDirectory.exists() || !scratchDirectory.mkdir()) throw IOException("Original preview workspace unavailable")
        ownsDirectory = true
        // Existing original-trial scratch reserve; no full-size Float RGB or JPEG is produced.
        if (scratchDirectory.usableSpace < 2L * 1024 * 1024 * 1024) throw IOException("Insufficient original preview workspace")
        DngContentVerifier.copy(source.fingerprint, { io().input(openOriginal()) }, {
            io().output(Files.newOutputStream(snapshotFile.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).also { ownsSnapshot = true })
        }, stop)
        if (!rasterFile.createNewFile()) throw IOException("Original preview raster already exists")
        ownsRaster = true
        rasterFd = ParcelFileDescriptor.open(rasterFile, ParcelFileDescriptor.MODE_READ_WRITE)
        sourceFd = ParcelFileDescriptor.open(snapshotFile, ParcelFileDescriptor.MODE_READ_ONLY)
        resourceAdmission(Original24MemoryStage.ORIGINAL_DECODE)
        val metadata = JSONObject(originalNativeMemoryCall { NativeOriginalRaster.decode24(requireNotNull(sourceFd).fd,
            requireNotNull(rasterFd).fd, signal) })
        closeDescriptors()
        originalCheckpoint(stop)
        val geometry = Original24PreflightGeometry(metadata.getInt("rawWidth"), metadata.getInt("rawHeight"),
            metadata.getInt("sourceWidth"), metadata.getInt("sourceHeight"), metadata.getInt("orientation"),
            metadata.getInt("width"), metadata.getInt("height"), metadata.getInt("cropLeft"), metadata.getInt("cropTop"))
        if (geometry != expectedGeometry) throw IOException("Original preview geometry differs from admitted preflight")
        val width = geometry.outputWidth; val height = geometry.outputHeight
        if (metadata.getInt("bits") != 16 || metadata.getInt("channels") != 3 || metadata.getInt("halfSize") != 0 ||
            metadata.getLong("pixelsWritten") != width.toLong() * height ||
            metadata.getString("color") != "linear-sRGB-D65" ||
            metadata.getString("scope") != "PRELIMINARY_ORIGINAL_NO_FACE_NO_VERIFIED_EV" ||
            rasterFile.length() != width.toLong() * height * 6)
            throw IOException("Original preview raster observations differ")
        val rows = OriginalRasterFile(rasterFile, width, height, 2).also { raster = it }
        val (reducedWidth, reducedHeight) = selectSize(width, height)
        if (reducedWidth < 4 || reducedHeight < 4) throw IOException("Original preview aspect is unsupported")
        resourceAdmission(Original24MemoryStage.BOUNDED_PREVIEW)
        val reduced = OriginalFields.reduce(rows, reducedWidth, reducedHeight, 3, stop) { rgb, output ->
            rgb.copyInto(output)
        }.reducedRgb()
        // Bind the private native input and the current provider contents to the requested original.
        DngContentVerifier.verify(source.fingerprint, { io().input(snapshotFile.inputStream()) }, stop)
        verifyOriginal(stop)
        releaseTransient()
        originalCheckpoint(stop)
        return reduced
    }

    private fun io() = resources ?: DngIoResources().also { resources = it }
    private fun verifyOriginal(stop: () -> Boolean) = DngContentVerifier.verify(source.fingerprint,
        { io().input(openOriginal()) }, stop)

    private fun closeDescriptors() {
        var failure: Throwable? = null
        try { sourceFd?.close(); sourceFd = null } catch (error: Throwable) { failure = error }
        try { rasterFd?.close(); rasterFd = null } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    /** Failed closes keep their exact owners. Delete only our known files after every close confirms. */
    private fun releaseTransient() {
        var failure: Throwable? = null
        fun release(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        } }
        release { closeDescriptors() }
        release { resources?.releaseAll()?.let { throw it } }
        if (resources?.hasOpenResources == false) resources = null
        release { raster?.closePending() }
        if (sourceFd == null && rasterFd == null && resources?.hasOpenResources != true && raster?.hasPendingOwners != true) {
            raster = null
            release { if (ownsRaster) { Files.deleteIfExists(rasterFile.toPath()); ownsRaster = false } }
            release { if (ownsSnapshot) { Files.deleteIfExists(snapshotFile.toPath()); ownsSnapshot = false } }
            release { if (ownsDirectory && !ownsRaster && !ownsSnapshot) {
                if (!scratchDirectory.delete()) throw IOException("Original preview workspace cleanup unconfirmed")
                ownsDirectory = false
            } }
        }
        failure?.let { throw it }
    }

    override fun close() {
        requestStop()
        exclusive {
            closed = true
            releaseTransient()
        }
    }
    private inline fun <T> exclusive(action: () -> T): T {
        if (!active.compareAndSet(false, true)) throw IOException("Original preview session already active")
        try { return action() } finally { active.set(false) }
    }
}
