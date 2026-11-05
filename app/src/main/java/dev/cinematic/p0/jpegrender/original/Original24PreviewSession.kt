package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.PreparedStageClearGlow
import dev.cinematic.p0.raw.R7Base
import dev.cinematic.p0.raw.R7PreviewInputs
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
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONObject

/** One caller-admitted IO session, never a queue/slot, saved image or face-protected completion.
 * The first render decodes an immutable, fingerprint-bound private snapshot to row-readable RGB16,
 * reduces it, closes all owners and deletes both files before retaining <=1024 AS_SHOT input.
 * Only that input, one R7 base and its reduced Glow fields survive calls. Returned software sRGB Bitmaps belong to callers.
 * Source/capture/lifecycle invalidation and resource/memory admission belong to the existing factory.
 * A failed or stopped session never silently acquires a fresh decode attempt. */
internal class Original24PreviewSession(
    val source: SharedOriginalReference,
    private val scratchDirectory: File,
    private val resourceAdmission: () -> Unit,
    private val expectedGeometry: Original24PreflightGeometry,
    private val openOriginal: () -> InputStream,
) : AutoCloseable {
    private val active = AtomicBoolean(false)
    private val signal = CancellationSignal()
    @Volatile private var stopped = false
    @Volatile private var closed = false
    private var failed = false
    private var decodeStarted = false
    private var asShot: LinearSrgbImage? = null
    private var base: LinearSrgbImage? = null
    private var baseAdjustments: PhotoAdjustments? = null
    private var previewGlow: PreparedStageClearGlow? = null
    internal val previewFieldBytes get() = previewGlow?.retainedFieldBytes ?: 0
    internal var previewFieldBuilds = 0
        private set
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

    fun render(strength: Double, adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
        shouldStop: () -> Boolean = { false }): Bitmap = exclusive {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Original preview requires existing IO owner" }
        require(strength.isFinite() && strength in 0.0..1.0)
        check(!closed && !failed && !stopped) { "Original preview session is unavailable" }
        val stop = { stopped || signal.isCanceled || shouldStop() }
        var result: Bitmap? = null
        var failure: Throwable? = null
        try {
            originalCheckpoint(stop)
            resourceAdmission() // Cached Look renders also need the fixed original-trial resource gate.
            verifyOriginal(stop)
            val input = asShot ?: loadInput(stop).also { asShot = it }
            resourceAdmission()
            val foundation = if (base != null && baseAdjustments == adjustments) requireNotNull(base) else {
                // Drop the old base before allocating a replacement; warmth and EV both invalidate it.
                base = null; baseAdjustments = null; previewGlow = null
                original24PreviewFoundation(input, adjustments, stop).also {
                    originalCheckpoint(stop)
                    base = it; baseAdjustments = adjustments
                }
            }
            val noProtection = FaceMask(foundation.width, foundation.height, FloatArray(foundation.pixelCount))
            val glow = previewGlow ?: PreparedStageClearGlow.prepare(foundation) {
                originalCheckpoint(stop)
            }.also { previewGlow = it; previewFieldBuilds++ }
            val image = glow.render(noProtection, strength) { originalCheckpoint(stop) }.image
            originalCheckpoint(stop)
            resourceAdmission()
            result = bitmap(image, stop)
            verifyOriginal(stop)
            releaseTransient()
            originalCheckpoint(stop)
            requireNotNull(result)
        } catch (error: Throwable) {
            failed = true; asShot = null; base = null; baseAdjustments = null; previewGlow = null
            failure = error
            result?.recycle()
            if (error is OutOfMemoryError) throw IOException("Original preview memory unavailable", error)
            throw error
        } finally {
            try { releaseTransient() }
            catch (cleanup: Throwable) {
                failed = true; asShot = null; base = null; baseAdjustments = null; previewGlow = null
                result?.recycle()
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    /** Caller-owned Before from cached, physically oriented AS_SHOT input. Uses the capture's WB
     * and preliminary LibRaw basis; no R7, Look, manual warmth or manual EV is applied. It is a
     * separate software sRGB Bitmap, never the returned After or a saved/completed image.
     * Bitmap allocation/conversion OOM alone is optional; source/close/cancel checks remain fatal. */
    fun comparisonBitmap(createBitmap: (LinearSrgbImage, () -> Boolean) -> Bitmap = ::bitmap,
        shouldStop: () -> Boolean = { false }): Bitmap? = exclusive {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Original preview requires existing IO owner" }
        check(!closed && !failed && !stopped) { "Original preview session is unavailable" }
        val input = checkNotNull(asShot) { "Original preview input has not been acquired" }
        val stop = { stopped || signal.isCanceled || shouldStop() }
        var result: Bitmap? = null
        var failure: Throwable? = null
        try {
            originalCheckpoint(stop)
            resourceAdmission()
            verifyOriginal(stop)
            resourceAdmission()
            try { result = createBitmap(input, stop) }
            catch (_: OutOfMemoryError) { result = null } // bitmap() recycles any partially created Bitmap.
            verifyOriginal(stop)
            releaseTransient()
            originalCheckpoint(stop)
            result
        } catch (error: Throwable) {
            failed = true; asShot = null; base = null; baseAdjustments = null; previewGlow = null
            failure = error
            result?.recycle()
            if (error is OutOfMemoryError) throw IOException("Original comparison memory unavailable", error)
            throw error
        } finally {
            try { releaseTransient() }
            catch (cleanup: Throwable) {
                failed = true; asShot = null; base = null; baseAdjustments = null; previewGlow = null
                result?.recycle()
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    private fun loadInput(stop: () -> Boolean): LinearSrgbImage {
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
        resourceAdmission()
        val metadata = JSONObject(NativeOriginalRaster.decode24(requireNotNull(sourceFd).fd,
            requireNotNull(rasterFd).fd, signal))
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
        val scale = min(1.0, 1024.0 / max(width, height))
        val reducedWidth = floor(width * scale).toInt(); val reducedHeight = floor(height * scale).toInt()
        if (reducedWidth < 4 || reducedHeight < 4) throw IOException("Original preview aspect is unsupported")
        resourceAdmission()
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

    private fun bitmap(image: LinearSrgbImage, stop: () -> Boolean): Bitmap {
        val result = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888, false,
            ColorSpace.get(ColorSpace.Named.SRGB))
        try {
            val row = IntArray(image.width)
            for (y in 0 until image.height) {
                originalCheckpoint(stop)
                for (x in row.indices) {
                    val offset = (y * image.width + x) * 3
                    row[x] = (255 shl 24) or (channel(image[offset]) shl 16) or
                        (channel(image[offset + 1]) shl 8) or channel(image[offset + 2])
                }
                result.setPixels(row, 0, image.width, 0, y, image.width, 1)
            }
            originalCheckpoint(stop)
            return result
        } catch (error: Throwable) { result.recycle(); throw error }
    }

    private fun channel(linear: Float): Int {
        val value = linear.toDouble().coerceIn(0.0, 1.0)
        return ((if (value <= .0031308) 12.92 * value else 1.055 * value.pow(1.0 / 2.4) - .055) * 255)
            .roundToInt().coerceIn(0, 255)
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
            closed = true; asShot = null; base = null; baseAdjustments = null; previewGlow = null
            releaseTransient()
        }
    }
    private inline fun <T> exclusive(action: () -> T): T {
        if (!active.compareAndSet(false, true)) throw IOException("Original preview session already active")
        try { return action() } finally { active.set(false) }
    }
}

/** Meter the unaltered AS_SHOT input so relative warmth is never compensated by automatic metering.
 * User EV is added to that fixed diagnostic EV after warmth, matching original-size rendering. */
internal fun original24PreviewFoundation(input: LinearSrgbImage, adjustments: PhotoAdjustments,
    stop: () -> Boolean): LinearSrgbImage {
    val diagnostic = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
        R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList(), stop)
    val warmed = adjustments.applyWarmth(input, stop)
    return R7Base.render(warmed, diagnostic.foundationSkinRoi,
        diagnostic.exposure.externalBaseEv + adjustments.exposureEv, stop)
}
