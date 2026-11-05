package dev.cinematic.p0.rawcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import dev.cinematic.p0.photoroll.TrialDisplayPixels
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** Owns ORIGINAL reads and distinct attempt outputs. No source/provisional/delete capability is handed to adapters. */
@RequiresApi(29)
internal class AndroidAutoDevelopBackend(
    context: Context,
    private val request: AutoDevelopRequest,
    private val catalog: AutoRendererCatalog,
    private val comparisonFrameFactory: (AutoRenderIo, SharedOriginalReference) -> OwnedJpegFrameSource =
        { io, source -> AndroidOwnedJpegFrames(io, source) },
) : AutoDevelopBackend {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val root = File(app.noBackupFilesDir, "raw-captures")
    private val generationFiles = RenderedJpegFiles(root, request.captureId, request.generation)
    private val attemptFiles = mutableMapOf<String, RenderedJpegFiles>()
    private var resources = DngIoResources()
    private val descriptors = mutableListOf<OwnedDescriptor>()
    private var publisher: AutoDevelopPublication? = null
    private var publishedAttempt: String? = null
    private val previewOwnership = Any()
    private var comparisonFrames: OwnedJpegFrameSource? = null

    override fun privatePreviewStopAction(): (() -> Unit)? = synchronized(previewOwnership) {
        comparisonFrames?.let { exact -> { exact.requestStop() } }
    }

    override val hasUnreleasedResources get() = resources.hasOpenResources || descriptors.any { !it.closed }
    override fun releaseResources() {
        var failure = resources.releaseAll()
        descriptors.asReversed().forEach { descriptor ->
            try { descriptor.close() } catch (t: Throwable) { if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t) }
        }
        failure?.let { throw it }
        if (hasUnreleasedResources) throw IOException("Auto-develop IO remains open")
        resources = DngIoResources()
        descriptors.clear()
    }
    private fun checkRequest(value: AutoDevelopRequest) { if (value != request) throw CaptureJournalConflict() }
    override fun supports(request: AutoDevelopRequest) = request == this.request && catalog.find(request) != null
    override fun verifyOriginal(request: AutoDevelopRequest, shouldStop: () -> Boolean) {
        checkRequest(request)
        releaseResources()
        DngContentVerifier.verify(request.source.fingerprint, { resources.input(readOriginal()) }, shouldStop)
    }
    private fun readOriginal(): InputStream = resolver.openInputStream(Uri.parse(request.source.uri)) ?: throw IOException("Shared original unavailable")

    private inner class OwnedDescriptor(private val owner: ParcelFileDescriptor) : AutoReadOnlyDescriptor {
        var closed = false
            private set
        override val fd: Int get() { if (closed) throw IOException("Original descriptor is closed"); return owner.fd }
        override fun close() { if (!closed) { owner.close(); closed = true } }
    }
    private fun files(attemptId: String): RenderedJpegFiles {
        require(attemptId.matches(Regex("[0-9a-f]{32}")))
        val captureRoot = File(root.canonicalFile, request.captureId)
        val attemptsRoot = File(captureRoot, "auto-attempts")
        listOf(captureRoot, attemptsRoot).forEach {
            if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile != it) throw IOException("Auto-develop attempt directory redirected")
        }
        // Reuse fixed CREATE_NEW/writer-close ownership. One directory per attempt, never the previous failed output.
        return attemptFiles.getOrPut(attemptId) { RenderedJpegFiles(attemptsRoot, attemptId, 1) }
    }
    override fun createAttempt(request: AutoDevelopRequest, attemptId: String): AutoRenderAttempt {
        checkRequest(request)
        val adapter = catalog.find(request) ?: throw IOException("Final renderer unavailable")
        val output = files(attemptId)
        var rendering = false
        fun requireRendering() { if (!rendering) throw IOException("Renderer IO is outside its owned attempt") }
        val io = object : AutoRenderIo {
            override fun openOriginal(): InputStream { requireRendering(); return resources.input(readOriginal()) }
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor {
                requireRendering()
                val owner = resolver.openFileDescriptor(Uri.parse(request.source.uri), "r") ?: throw IOException("Original descriptor unavailable")
                return OwnedDescriptor(owner).also { descriptors += it }
            }
            override fun openOutput(): OutputStream { requireRendering(); return resources.output(output.createOutput()) }
            override fun openOutputRead(): InputStream { requireRendering(); return resources.input(output.openRead()) }
        }
        // The adapter constructor must not acquire resources: the returned owner is retained before render.
        val engine = adapter.createAttempt(request, attemptId, io)
        return object : AutoRenderAttempt {
            override fun render(shouldStop: () -> Boolean): AutoRenderReceipt {
                rendering = true
                try { return engine.render(shouldStop) }
                finally { rendering = false }
            }
            override fun requestStop() = engine.requestStop()
            override fun close() {
                engine.close() // A still-running kernel must retain its source/output handles too.
                releaseResources()
            }
        }
    }
    override fun inspect(request: AutoDevelopRequest, attemptId: String, shouldStop: () -> Boolean): JpegInspection {
        checkRequest(request)
        releaseResources()
        val output = files(attemptId)
        output.sealCompleted() // Invalid/missing output is validation failure, not a resource close failure.
        return AndroidJpegInspector().inspect({ resources.input(output.openRead()) }, shouldStop)
    }
    override fun publication(record: AutoDevelopRecord, isCurrent: () -> Boolean): AutoDevelopPublication {
        checkRequest(record.request)
        if (request.quality == AutoRenderQuality.PRIVATE_TRIAL_1024) throw IOException("Private trial cannot acquire a publication owner")
        if (!supports(record.request)) throw IOException("Final renderer contract is not enrolled")
        val attemptId = requireNotNull(record.attemptId)
        publisher?.let { if (publishedAttempt != attemptId) throw CaptureJournalConflict(); return it }
        JpegOperationOwners.registry.claim(app.noBackupFilesDir.canonicalPath, request.captureId, request.generation, this)
        releaseResources()
        val directory = generationFiles.generationRoot()
        val journal = JpegPublicationJournal(request.captureId, request.generation,
            AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), CaptureJournalFile.JPEG_PUBLICATION))
        val render = record.completed()
        val fingerprint = requireNotNull(record.outputFingerprint)
        val existing = journal.read()
        if (existing == null) DngContentVerifier.verify(fingerprint, { resources.input(files(attemptId).openRead()) }, { !isCurrent() })
        else if (existing.render != render || existing.fingerprint != fingerprint) throw CaptureJournalConflict()
        val native = RecoverablePhotoPublisher(journal, MediaStoreJpegRows(app), render, setOf(request.contract),
            object : RenderedJpegInput {
                override fun openJpeg() = files(attemptId).openRead()
                override fun openOriginal(reference: SharedOriginalReference): InputStream {
                    if (reference != request.source) throw CaptureJournalConflict()
                    return readOriginal()
                }
            }, AndroidJpegInspector(), isCurrent)
        publishedAttempt = attemptId
        return RecoverableAutoPublication(native).also { publisher = it }
    }

    override fun privatePreview(record: AutoDevelopRecord, shouldStop: () -> Boolean): PrivateTrialPreview {
        checkRequest(record.request)
        if (!request.boundedTrial || record.phase != AutoJobPhase.RENDERED) throw CaptureJournalConflict()
        // This invocation owns one binding. Do not attach reusable pixels to each durable job/backend.
        val reuse = PrivateTrialReuseRegistry.forRequest(request)
        var ownedImage: Bitmap? = null
        var ownedComparison: Bitmap? = null
        try {
            val expected = requireNotNull(record.outputFingerprint)
            val description = requireNotNull(record.observed).output
            releaseResources()
            verifyOriginal(request, shouldStop)
            val encoded = EncodedJpegSnapshot.read({ resources.input(files(requireNotNull(record.attemptId)).openRead()) },
                expected.bytes, shouldStop)
            if (encoded.fingerprint != expected) throw DngContentChanged()
            val inspection = AndroidJpegInspector().inspectSnapshot(encoded, shouldStop)
            if (inspection.fingerprint != expected || inspection.output != description) throw DngContentChanged()
            if (shouldStop()) throw DngVerificationStopped()
            val bitmap = ImageDecoder.decodeBitmap(encoded.source()) { decoder, info, _ ->
                if (info.size.width != description.width || info.size.height != description.height) throw IOException("Private JPEG dimensions changed")
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                decoder.setOnPartialImageListener { false }
            }.also { ownedImage = it }
            if (bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE || bitmap.width != description.width ||
                bitmap.height != description.height || bitmap.width > 1024 || bitmap.height > 1024 || bitmap.colorSpace?.isSrgb != true) {
                throw IOException("Private display image is unsupported")
            }
            var candidate: IntArray? = null
            val comparison = try {
                val cached = cachedComparison(reuse, description, shouldStop)
                val result = cached ?: comparisonImage(description, shouldStop)
                candidate = result.candidate
                result.bitmap.also { ownedComparison = it }
            }
            catch (failure: java.util.concurrent.CancellationException) { throw failure }
            catch (failure: android.os.OperationCanceledException) { throw failure }
            catch (failure: DngVerificationStopped) { throw failure }
            catch (failure: DngContentChanged) { throw failure }
            catch (_: Exception) { reuse?.invalidate(); comparisonUnavailable(shouldStop) }
            catch (_: OutOfMemoryError) { reuse?.invalidate(); comparisonUnavailable(shouldStop) }
            verifyOriginal(request, shouldStop)
            if (shouldStop()) throw DngVerificationStopped()
            releaseResources() // Commit only after the final original hash and every stream/FD close succeeds.
            if (shouldStop()) throw DngVerificationStopped()
            candidate?.let { pixels ->
                try { reuse?.commitComparison(description.width, description.height, pixels) }
                catch (_: Exception) { reuse?.invalidate() }
                catch (_: OutOfMemoryError) { reuse?.invalidate() }
            }
            return PrivateTrialPreview(bitmap, PrivateTrialStatus.from(record), comparison).also {
                ownedImage = null; ownedComparison = null
            }
        } catch (failure: Throwable) {
            if (failure is java.util.concurrent.CancellationException ||
                failure is android.os.OperationCanceledException || failure is DngVerificationStopped) {
                // A superseded display request cannot destroy a previously verified session entry.
                // Keep it only after actual stream/FD closure; no candidate is committed on this path.
                try { releaseResources() }
                catch (cleanup: Throwable) {
                    reuse?.invalidate()
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            } else reuse?.invalidate()
            throw failure
        } finally { ownedImage?.recycle(); ownedComparison?.recycle() }
    }

    private data class ComparisonImage(val bitmap: Bitmap, val candidate: IntArray? = null)

    /** Cache allocation failures fall back to the existing decode; cancellation never does. */
    private fun cachedComparison(reuse: PrivateTrialReuseLease?, description: JpegOutputDescription,
        shouldStop: () -> Boolean): ComparisonImage? {
        try {
            if (shouldStop()) throw DngVerificationStopped()
            val pixels = reuse?.comparisonCopy(description.width, description.height) ?: return null
            if (shouldStop()) throw DngVerificationStopped()
            return ComparisonImage(Bitmap.createBitmap(pixels, description.width, description.height, Bitmap.Config.ARGB_8888))
        } catch (failure: java.util.concurrent.CancellationException) { throw failure }
        catch (failure: android.os.OperationCanceledException) { throw failure }
        catch (failure: DngVerificationStopped) { throw failure }
        catch (_: Exception) { reuse?.invalidate(); return null }
        catch (_: OutOfMemoryError) { reuse?.invalidate(); return null }
    }

    private fun comparisonUnavailable(shouldStop: () -> Boolean): Bitmap? {
        if (shouldStop()) throw DngVerificationStopped()
        releaseResources() // A failed close must fail the whole result and retain its coordinator slot.
        return null // The caller still re-verifies the original before returning the valid After image.
    }

    /** Called only by readPrivateTrial under the same coordinator slot as fresh JPEG/source checks.
     * No R7, diagnostic EV, W1/look, source thumbnail, file copy or second JPEG is substituted. */
    private fun comparisonImage(description: JpegOutputDescription, shouldStop: () -> Boolean): ComparisonImage {
        val io = object : AutoRenderIo {
            override fun openOriginal() = resources.input(readOriginal())
            override fun openOriginalDescriptor(): AutoReadOnlyDescriptor {
                val owner = resolver.openFileDescriptor(Uri.parse(request.source.uri), "r")
                    ?: throw IOException("Comparison source descriptor unavailable")
                return OwnedDescriptor(owner).also { descriptors += it }
            }
            override fun openOutput(): OutputStream = throw IOException("Comparison cannot write a file")
        }
        val frames = comparisonFrameFactory(io, request.source)
        synchronized(previewOwnership) { check(comparisonFrames == null); comparisonFrames = frames }
        var owned: Bitmap? = null
        var completed = false
        try {
            val frame = frames.load(request.source, 1024, shouldStop)
            if (!frame.physicallyOriented || frame.image.width != description.width || frame.image.height != description.height)
                throw IOException("Comparison geometry differs from private JPEG")
            val pixels = TrialDisplayPixels.encode(frame.image, 1024, shouldStop)
            val bitmap = Bitmap.createBitmap(pixels, frame.image.width, frame.image.height, Bitmap.Config.ARGB_8888).also { owned = it }
            frames.close()
            if (shouldStop()) throw DngVerificationStopped()
            return ComparisonImage(bitmap, pixels).also { completed = true }
        } finally {
            try { frames.close() }
            catch (failure: Throwable) { owned?.recycle(); throw failure }
            finally {
                synchronized(previewOwnership) { if (comparisonFrames === frames) comparisonFrames = null }
                if (!completed) owned?.recycle()
            }
        }
    }
}
