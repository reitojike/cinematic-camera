package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.raw.FloatPlane
import dev.cinematic.p0.raw.StageClearDecisions
import dev.cinematic.p0.raw.original.OriginalRaster
import dev.cinematic.p0.raw.FaceMask
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.PreparedStageClearGlow
import dev.cinematic.p0.raw.RawPreviewTiming
import dev.cinematic.p0.raw.PreviewPixelExecution
import dev.cinematic.p0.BuildConfig
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
 * Retains that input/base/Glow plus one <=256 input/base/Glow and immutable exact decisions.
 * Returned software sRGB Bitmaps belong to callers.
 * Source/capture/lifecycle invalidation and resource/memory admission belong to the existing factory.
 * A failed or stopped session never silently acquires a fresh decode attempt. */
internal class Original24PreviewSession(
    val source: SharedOriginalReference,
    private val scratchDirectory: File,
    private val resourceAdmission: (Original24MemoryStage) -> Unit,
    private val expectedGeometry: Original24PreflightGeometry,
    private val openOriginal: () -> InputStream,
) : AutoCloseable {
    private val active = AtomicBoolean(false)
    private val signal = CancellationSignal()
    @Volatile private var stopped = false
    @Volatile private var closed = false
    private var failed = false
    private var decodeStarted = false
    private var timingSequence = 0L
    private var asShot: LinearSrgbImage? = null
    private var base: LinearSrgbImage? = null
    private var baseAdjustments: PhotoAdjustments? = null
    internal var previewExternalEv: Double? = null
        private set
    private var previewGlow: PreparedStageClearGlow? = null
    internal var previewDecisions: StageClearDecisions? = null
        private set
    private var meteredBaseEv: Double? = null
    private var draftInput: LinearSrgbImage? = null
    private var draftBase: LinearSrgbImage? = null
    private var draftAdjustments: PhotoAdjustments? = null
    private var draftGlow: PreparedStageClearGlow? = null
    internal val draftFieldBytes get() = draftGlow?.retainedFieldBytes ?: 0
    internal val hasDraftDecisions get() = previewDecisions != null && meteredBaseEv != null && !closed && !failed && !stopped
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
        shouldStop: () -> Boolean = { false }): Bitmap = requireNotNull(renderInternal(strength, adjustments, false, null, shouldStop))

    /** No decode, new judgement or save generation. Missing exact decisions retain the UI's prior image. */
    fun renderDraft(strength: Double, adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
        shouldStop: () -> Boolean = { false }): Bitmap? = renderInternal(strength, adjustments, true, null, shouldStop)

    /** Integrated diagnostic only. Null execution for OFF is exactly the normal serial path. */
    fun renderDiagnostic(strength: Double, adjustments: PhotoAdjustments, draft: Boolean, workers: Int,
        shouldStop: () -> Boolean): Bitmap? {
        check(BuildConfig.EDITOR_PREVIEW_DIAGNOSTIC)
        require(workers in 1..2)
        return renderInternal(strength, adjustments, draft, workers, shouldStop)
    }

    private fun renderInternal(strength: Double, adjustments: PhotoAdjustments, draft: Boolean,
        diagnosticWorkers: Int?, shouldStop: () -> Boolean): Bitmap? = exclusive {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Original preview requires existing IO owner" }
        require(strength.isFinite() && strength in 0.0..1.0)
        check(!closed && !failed && !stopped) { "Original preview session is unavailable" }
        if (draft && !hasDraftDecisions) return@exclusive null
        val stop = { stopped || signal.isCanceled || shouldStop() }
        val timing = if (diagnosticWorkers != null || android.util.Log.isLoggable("PreviewTiming", android.util.Log.DEBUG))
            RawPreviewTiming.optional { RawPreviewTiming(if (draft) RawPreviewTiming.Kind.DRAFT else RawPreviewTiming.Kind.EXACT,
                ++timingSequence, sink = {
                    if (diagnosticWorkers != null) android.util.Log.i("RawPreviewTiming",
                        "workers=$diagnosticWorkers maxEdge=${if (draft) 256 else 1024} $it")
                    else android.util.Log.d("RawPreviewTiming", it)
                }) } else null
        var execution: PreviewPixelExecution? = null
        var result: Bitmap? = null
        var failure: Throwable? = null
        try {
            if (diagnosticWorkers == 2) execution = PreviewPixelExecution(2, stop)
            originalCheckpoint(stop)
            resourceAdmission(Original24MemoryStage.BOUNDED_PREVIEW) // Reduced work checks current OS pressure, not the original-decode allowance.
            timing?.enter(RawPreviewTiming.Stage.VERIFY_BEFORE)
            verifyOriginal(stop)
            timing?.enter(RawPreviewTiming.Stage.INPUT)
            val input = if (draft) draftInput ?: reduceDraftInput(requireNotNull(asShot), stop).also { draftInput = it }
                else asShot ?: loadInput(stop).also { asShot = it }
            resourceAdmission(Original24MemoryStage.BOUNDED_PREVIEW)
            timing?.enter(RawPreviewTiming.Stage.FOUNDATION)
            val foundation = if (draft) {
                if (draftBase == null || draftAdjustments != adjustments) {
                    draftBase = null; draftGlow = null; draftAdjustments = null
                    val warmed = adjustments.applyWarmth(input, stop)
                    // Exact foundation uses emptyList() ellipses, so this is the same zero ROI,
                    // without recomputing automatic exposure on the coarse image.
                    draftBase = R7Base.render(warmed, FloatPlane(input.width, input.height, FloatArray(input.pixelCount)),
                        requireNotNull(meteredBaseEv) + adjustments.exposureEv, execution, stop)
                    draftAdjustments = adjustments
                }
                requireNotNull(draftBase)
            } else if (base != null && baseAdjustments == adjustments) requireNotNull(base) else {
                // Drop the old base before allocating a replacement; warmth and EV both invalidate it.
                base = null; baseAdjustments = null; previewExternalEv = null; previewGlow = null
                original24PreviewFoundation(input, adjustments, stop, execution).also {
                    originalCheckpoint(stop)
                    base = it.image; baseAdjustments = adjustments; previewExternalEv = it.externalEv
                }.image
            }
            val noProtection = FaceMask(foundation.width, foundation.height, FloatArray(foundation.pixelCount))
            timing?.enter(RawPreviewTiming.Stage.GLOW_PREPARE)
            val glow = (if (draft) draftGlow else previewGlow) ?: PreparedStageClearGlow.prepare(foundation) {
                originalCheckpoint(stop)
            }.also { if (draft) draftGlow = it else { previewGlow = it; previewFieldBuilds++ } }
            val rendered = if (draft) glow.render(noProtection, strength, requireNotNull(previewDecisions), timing, execution) {
                originalCheckpoint(stop)
            } else glow.render(noProtection, strength, null, timing, execution) { originalCheckpoint(stop) }
            val image = rendered.image
            originalCheckpoint(stop)
            resourceAdmission(Original24MemoryStage.BOUNDED_PREVIEW)
            timing?.enter(RawPreviewTiming.Stage.BITMAP)
            result = bitmap(image, stop)
            timing?.enter(RawPreviewTiming.Stage.VERIFY_AFTER)
            verifyOriginal(stop)
            timing?.enter(RawPreviewTiming.Stage.CLEANUP)
            releaseTransient()
            originalCheckpoint(stop)
            if (!draft) {
                previewDecisions = requireNotNull(rendered.decisions)
                meteredBaseEv = requireNotNull(previewExternalEv) - adjustments.exposureEv
                draftBase = null; draftGlow = null; draftAdjustments = null
            }
            requireNotNull(result)
        } catch (error: Throwable) {
            failed = true; clearInputs()
            failure = error
            result?.recycle()
            if (error is OutOfMemoryError) throw Original24MemoryUnavailable(error)
            throw error
        } finally {
            timing?.enter(RawPreviewTiming.Stage.CLEANUP)
            var cleanupFailed = false
            try { execution?.close(); releaseTransient() }
            catch (cleanup: Throwable) {
                cleanupFailed = true
                failed = true; clearInputs()
                result?.recycle()
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            } finally {
                timing?.finish(if (failure == null && !cleanupFailed) RawPreviewTiming.Outcome.SUCCESS
                    else RawPreviewTiming.Outcome.FAILED)
            }
        }
    }

    /** Caller-owned Before from cached, physically oriented AS_SHOT input. Uses the capture's WB
     * and preliminary LibRaw basis; no R7, Look, manual warmth or manual EV is applied. It is a
     * separate software sRGB Bitmap, never the returned After or a saved/completed image.
     * Bitmap allocation/conversion OOM alone is optional; source/close/cancel checks remain fatal. */
    fun comparisonBitmap(createBitmap: (LinearSrgbImage, () -> Boolean) -> Bitmap = ::bitmap,
        maxEdge: Int = 1024, shouldStop: () -> Boolean = { false }): Bitmap? = exclusive {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Original preview requires existing IO owner" }
        check(!closed && !failed && !stopped) { "Original preview session is unavailable" }
        require(maxEdge == 1024 || maxEdge == 256)
        val stop = { stopped || signal.isCanceled || shouldStop() }
        val exact = checkNotNull(asShot) { "Original preview input has not been acquired" }
        val input = if (maxEdge == 256) checkNotNull(draftInput) { "Draft input has not been acquired" } else exact
        var result: Bitmap? = null
        var failure: Throwable? = null
        try {
            originalCheckpoint(stop)
            resourceAdmission(Original24MemoryStage.BOUNDED_PREVIEW)
            verifyOriginal(stop)
            resourceAdmission(Original24MemoryStage.BOUNDED_PREVIEW)
            try { result = createBitmap(input, stop) }
            catch (_: OutOfMemoryError) { result = null } // bitmap() recycles any partially created Bitmap.
            verifyOriginal(stop)
            releaseTransient()
            originalCheckpoint(stop)
            result
        } catch (error: Throwable) {
            failed = true; clearInputs()
            failure = error
            result?.recycle()
            if (error is OutOfMemoryError) throw Original24MemoryUnavailable(error)
            throw error
        } finally {
            try { releaseTransient() }
            catch (cleanup: Throwable) {
                failed = true; clearInputs()
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
        resourceAdmission(Original24MemoryStage.ORIGINAL_DECODE)
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

    private fun clearInputs() {
        asShot = null; base = null; baseAdjustments = null; previewExternalEv = null; previewGlow = null
        previewDecisions = null; meteredBaseEv = null
        draftInput = null; draftBase = null; draftAdjustments = null; draftGlow = null
    }

    override fun close() {
        requestStop()
        exclusive {
            closed = true; clearInputs()
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
internal data class Original24PreviewFoundation(val image: LinearSrgbImage, val externalEv: Double)

internal fun original24PreviewFoundation(input: LinearSrgbImage, adjustments: PhotoAdjustments,
    stop: () -> Boolean): Original24PreviewFoundation = original24PreviewFoundation(input, adjustments, stop, null)

internal fun original24PreviewFoundation(input: LinearSrgbImage, adjustments: PhotoAdjustments,
    stop: () -> Boolean, execution: PreviewPixelExecution?): Original24PreviewFoundation {
    val diagnostic = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
        R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList(), stop)
    val warmed = adjustments.applyWarmth(input, stop)
    val externalEv = diagnostic.exposure.externalBaseEv + adjustments.exposureEv
    return Original24PreviewFoundation(R7Base.render(warmed, diagnostic.foundationSkinRoi, externalEv, execution, stop), externalEv)
}

/** Preview-only BOX reduction reuses the existing reducer, without changing any processing kernel. */
internal fun reduceDraftInput(input: LinearSrgbImage, stop: () -> Boolean): LinearSrgbImage {
    val scale = min(1.0, 256.0 / max(input.width, input.height))
    val width = floor(input.width * scale).toInt().coerceAtLeast(1)
    val height = floor(input.height * scale).toInt().coerceAtLeast(1)
    if (width == input.width && height == input.height) return input
    val rows = object : OriginalRaster {
        override val width = input.width
        override val height = input.height
        override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) {
            val row = FloatArray(input.width * 3)
            repeat(input.height) { y ->
                originalCheckpoint(stop)
                for (x in row.indices) row[x] = input[y * row.size + x]
                consume(y, row)
            }
        }
    }
    return OriginalFields.reduce(rows, width, height, 3, stop) { rgb, output -> rgb.copyInto(output) }.reducedRgb()
}
