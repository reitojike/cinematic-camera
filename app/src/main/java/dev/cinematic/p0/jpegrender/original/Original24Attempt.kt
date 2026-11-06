package dev.cinematic.p0.jpegrender.original

import android.graphics.Bitmap
import android.content.Context
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.raw.original.*
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import org.json.JSONObject

internal data class Original24Request(val captureId: String, val generation: Long,
    val source: SharedOriginalReference, val strength: Double = .5,
    val adjustments: dev.cinematic.p0.raw.PhotoAdjustments = dev.cinematic.p0.raw.PhotoAdjustments.NONE) {
    init { requireCaptureId(captureId); require(generation > 0 && strength.isFinite() && strength in 0.0..1.0) }
    val recipe get() = ManualOriginalTrialRender.recipe(strength, adjustments)
}

/** Actual original-size JPEG and observations, deliberately not CompletedRender or a save authority. */
internal data class Original24TrialJpeg(val request: Original24Request, val output: JpegOutputDescription,
    val fingerprint: OriginalFingerprint, val geometry: OriginalRasterGeometry, val externalEv: Double,
    val observedGeometry: Original24PreflightGeometry) {
    val face get() = RenderedFaceState.NOT_RUN
    val exposure get() = RenderedExposureState.DIAGNOSTIC
    val profile get() = AutoInputProfile.UNKNOWN
}

/** Existing IO/coordinator's synchronous attempt. Does not create a queue, publish, or enroll a renderer. */
internal class Original24Attempt(context: Context, private val request: Original24Request, private val io: AutoRenderIo,
    private val scratchDirectory: File, private val trace: OriginalStageTrace? = null,
    private val parallelOrtonWorkers: Int = 4,
    private val ortonRowProbe: ((Int) -> Unit)? = null) : AutoCloseable {
    private val context = context.applicationContext
    private val active = AtomicBoolean(false)
    private var started = false
    private var closed = false
    private val signal = CancellationSignal()
    private val resources = DngIoResources()
    private var sourceFd: AutoReadOnlyDescriptor? = null
    private var rasterFd: ParcelFileDescriptor? = null
    private var workspace: OriginalRasterWorkspace? = null
    private var rawRaster: OriginalRasterFile? = null
    val hasUnreleasedResources get() = sourceFd != null || rasterFd != null || resources.hasOpenResources ||
        rawRaster?.hasPendingOwners == true || workspace?.hasPendingOwners == true
    fun requestStop() = signal.cancel()

    fun render(shouldStop: () -> Boolean): Original24TrialJpeg {
        check(active.compareAndSet(false, true))
        var failure: Throwable? = null
        try {
            check(Looper.myLooper() != Looper.getMainLooper())
            check(!started && !closed); started = true
            require(request.source.fingerprint.bytes <= 64L * 1024 * 1024)
            val stop = { signal.isCanceled || shouldStop() }
            fun checkpoint() = originalCheckpoint(stop)
            fun verify() = DngContentVerifier.verify(request.source.fingerprint, { resources.input(io.openOriginal()) }, stop)
            checkpoint(); Original24Resources.requireAvailable(context)
            originalStage(trace, OriginalStage.SOURCE_VERIFY_BEFORE) { verify() }
            val work = OriginalRasterWorkspace(scratchDirectory).also { workspace = it }
            // The decode pass can produce at most 144MB RGB16. Leave the row-renderer and reserve disk intact.
            if (scratchDirectory.usableSpace < 2L * 1024 * 1024 * 1024) throw IOException("Insufficient original workspace")
            // Bind the exact native input to the fingerprint, even if a provider changes its URI/FD mapping.
            // This is one bounded attempt snapshot, removed after confirmed success; never a permanent mirror.
            val snapshot = File(scratchDirectory, "source-snapshot.dng")
            originalStage(trace, OriginalStage.SOURCE_SNAPSHOT) { DngContentVerifier.copy(request.source.fingerprint, { resources.input(io.openOriginal()) }, {
                resources.output(java.nio.file.Files.newOutputStream(snapshot.toPath(),
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS))
            }, stop) }
            val raw = File(scratchDirectory, "source-linear.rgb16")
            check(raw.createNewFile())
            rasterFd = ParcelFileDescriptor.open(raw, ParcelFileDescriptor.MODE_READ_WRITE)
            val immutableFd = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY)
            sourceFd = object : AutoReadOnlyDescriptor {
                override val fd get() = immutableFd.fd
                override fun close() = immutableFd.close()
            }
            val metadata = JSONObject(originalStage(trace, OriginalStage.NATIVE_DECODE) {
                NativeOriginalRaster.decode24(requireNotNull(sourceFd).fd, requireNotNull(rasterFd).fd, signal)
            })
            originalStage(trace, OriginalStage.DECODE_CLOSE) { closeDescriptorOwners() }
            checkpoint(); Original24Resources.requireAvailable(context)
            val width = metadata.getInt("width"); val height = metadata.getInt("height")
            validateOriginalSize(width, height)
            val sourceWidth = metadata.getInt("sourceWidth"); val sourceHeight = metadata.getInt("sourceHeight")
            val orientation = metadata.getInt("orientation")
            val observedGeometry = Original24PreflightGeometry(metadata.getInt("rawWidth"), metadata.getInt("rawHeight"),
                sourceWidth, sourceHeight, orientation, width, height, metadata.getInt("cropLeft"), metadata.getInt("cropTop"))
            val geometry = OriginalRasterGeometry(sourceWidth, sourceHeight, orientation, width, height, true)
            if (metadata.getInt("bits") != 16 || metadata.getInt("channels") != 3 || metadata.getInt("halfSize") != 0 ||
                metadata.getLong("pixelsWritten") != width.toLong() * height ||
                metadata.getString("color") != "linear-sRGB-D65" || metadata.getString("scope") != "PRELIMINARY_ORIGINAL_NO_FACE_NO_VERIFIED_EV" ||
                width != geometry.orientedWidth || height != geometry.orientedHeight || raw.length() != width.toLong() * height * 6)
                throw IOException("Original raster observations differ")
            val source = OriginalRasterFile(raw, width, height, 2).also { rawRaster = it }
            val scale = min(1.0, 1024.0 / max(width, height))
            val analysis = originalStage(trace, OriginalStage.ANALYSIS_REDUCE) {
                OriginalFields.reduce(source, max(4, floor(width * scale).toInt()), max(4, floor(height * scale).toInt()),
                    3, stop) { rgb, out -> rgb.copyInto(out) }.reducedRgb()
            }
            val controls = originalStage(trace, OriginalStage.CONTROL_PREPARE) {
                R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(analysis,
                    R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList(), stop)
            }
            val ev = controls.exposure.externalBaseEv + request.adjustments.exposureEv
            // Match the bounded preview's relative WB BEFORE R7 without a full-frame Float copy.
            val red = 2.0.pow(request.adjustments.warmth / 200.0)
            val blue = 2.0.pow(-request.adjustments.warmth / 200.0)
            val norm = .2126 * red + .7152 + .0722 * blue
            val gains = doubleArrayOf(red / norm, 1.0 / norm, blue / norm)
            val adjusted = if (request.adjustments.warmth == 0.0) source else object : OriginalRaster {
                override val width = source.width
                override val height = source.height
                override fun rows(stop: () -> Boolean, consume: (Int, FloatArray) -> Unit) = source.rows(stop) { y, row ->
                    row.indices.forEach { i -> row[i] = (row[i] * gains[i % 3]).toFloat() }
                    consume(y, row)
                }
            }
            val rendered = OriginalStageClear24(work, trace, parallelOrtonWorkers, ortonRowProbe, parallelCharacterWorkers = 2)
                .render(adjusted, ev, request.strength, stop = stop)
            checkpoint(); Original24Resources.requireAvailable(context)
            val digest = MessageDigest.getInstance("SHA-256")
            var encodedBytes = 0L
            var bitmap: Bitmap? = null
            try {
                originalStage(trace, OriginalStage.BITMAP_PACK) {
                // Retain before invoking the observer: a reporting failure must also recycle it.
                val packed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
                packed.setHasAlpha(false)
                val row = IntArray(width)
                rendered.raster.rows(stop) { y, rgb ->
                    row.indices.forEach { x -> row[x] = (255 shl 24) or (channel(rgb[x * 3]) shl 16) or
                        (channel(rgb[x * 3 + 1]) shl 8) or channel(rgb[x * 3 + 2]) }
                    packed.setPixels(row, 0, width, 0, y, width, 1)
                }
                }
                originalStage(trace, OriginalStage.JPEG_ENCODE) { resources.output(io.openOutput()).use { output ->
                    val bounded = object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, count: Int) {
                            checkpoint()
                            if (count < 0 || count.toLong() > JpegOutputDescription.MAX_JPEG_BYTES - encodedBytes) throw IOException("Original JPEG byte limit")
                            output.write(bytes, offset, count); digest.update(bytes, offset, count); encodedBytes += count
                            checkpoint()
                        }
                        override fun flush() = output.flush()
                        override fun close() = throw IOException("Codec must not close owner output")
                    }
                    checkpoint()
                    if (!requireNotNull(bitmap).compress(Bitmap.CompressFormat.JPEG, 95, bounded)) throw IOException("Original JPEG codec failed")
                    bounded.flush(); checkpoint()
                } }
            } finally { bitmap?.recycle() }
            val fingerprint = OriginalFingerprint(encodedBytes, digest.digest().joinToString("") { "%02x".format(it) })
            val observed = originalStage(trace, OriginalStage.JPEG_INSPECT) {
                AndroidJpegInspector().inspect({ resources.input(io.openOutputRead()) }, encodedBytes, stop)
            }
            if (observed.fingerprint != fingerprint || observed.output.width != width || observed.output.height != height ||
                observed.output.orientation != 1 || observed.output.color != "sRGB") throw IOException("Original JPEG readback differs")
            originalStage(trace, OriginalStage.SOURCE_VERIFY_AFTER) { verify() }; checkpoint()
            originalStage(trace, OriginalStage.RESOURCE_CLOSE) { closeOwners() }
            checkpoint()
            originalStage(trace, OriginalStage.SCRATCH_CLEANUP) {
                check(raw.delete())
                check(snapshot.delete())
                work.disposeConfirmed()
            }
            checkpoint()
            return Original24TrialJpeg(request, observed.output, fingerprint, geometry, ev, observedGeometry)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { originalStage(trace, OriginalStage.RESOURCE_CLOSE) { closeOwners() } }
            catch (cleanup: Throwable) {
                val primary = failure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            } finally { active.set(false) }
            // Failed/uncertain attempts retain their scratch/output for the existing owner.
        }
    }

    private fun closeDescriptorOwners() {
        sourceFd?.close(); sourceFd = null
        rasterFd?.close(); rasterFd = null
    }
    private fun closeOwners() {
        var failure: Throwable? = null
        fun close(action: () -> Unit) { try { action() } catch (e: Throwable) { if (failure == null) failure = e else if (failure !== e) failure!!.addSuppressed(e) } }
        close { sourceFd?.close(); sourceFd = null }
        close { rasterFd?.close(); rasterFd = null }
        close { resources.releaseAll()?.let { throw it } }
        close { rawRaster?.closePending() }
        close { workspace?.closePending() }
        failure?.let { throw it }
    }
    override fun close() {
        check(active.compareAndSet(false, true))
        try { closed = true; closeOwners() } finally { active.set(false) }
    }
    private fun channel(value: Float): Int {
        val x = value.toDouble().coerceIn(0.0, 1.0)
        return ((if (x <= .0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - .055) * 255).roundToInt().coerceIn(0, 255)
    }
}
