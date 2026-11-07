package dev.cinematic.p0.rawcapture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import dev.cinematic.p0.jpegrender.original.Original24Attempt
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.jpegrender.original.Original24PreviewSession
import dev.cinematic.p0.jpegrender.original.Original24Resources
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.jpegrender.original.Original24Preflight
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.Closeable

/** Process-retained original-trial authority. All metadata, generation and publication use the sole
 * existing queue IO owner. Preparing never generates a JPEG or inserts a shared row. */
@RequiresApi(29)
internal object AndroidManualOriginalTrialSaves {
    private val authorities = mutableMapOf<Pair<String, SharedOriginalReference>, Authority>() // Queue IO only.
    private val previewEpoch = AtomicLong(0)
    @Volatile private var activePreview: Original24PreviewSession? = null
    private var previewAuthority: Authority? = null // Queue IO only; bounded to one cached source.

    /** Fast capture/lifecycle invalidation. Actual cleanup remains on the same serial IO owner. */
    fun invalidatePreviews() { previewEpoch.incrementAndGet(); activePreview?.requestStop() }

    fun discardPreview(context: Context): CompletableFuture<Unit> {
        invalidatePreviews()
        return AndroidAutoDevelopQueue.get(context).manualOriginalIo {
            previewAuthority?.discardPreview(); previewAuthority = null
        }
    }

    fun renderPreview(context: Context, source: SharedOriginalReference, strength: Double,
        conditions: AutoDevelopConditions, adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
        shouldStop: () -> Boolean = { false }): CompletableFuture<ManualOriginalTrialPreview> {
        val app = context.applicationContext
        val queue = AndroidAutoDevelopQueue.get(app)
        return queue.manualOriginalIo {
            if (shouldStop()) throw java.util.concurrent.CancellationException("Trial preview stopped")
            val key = app.noBackupFilesDir.canonicalPath to source
            val authority = authorities.getOrPut(key) { Authority(app, source) }
            val handle = authority.prepare(queue, strength, adjustments, conditions, shouldStop)
            if (previewAuthority !== authority) {
                previewAuthority?.discardPreview()
                previewAuthority = authority
            }
            authority.renderPreview(handle, conditions, shouldStop)
        }
    }

    fun prepare(context: Context, source: SharedOriginalReference, strength: Double,
        conditions: AutoDevelopConditions, adjustments: PhotoAdjustments = PhotoAdjustments.NONE,
        shouldStop: () -> Boolean = { false }): CompletableFuture<ManualOriginalTrialHandle> {
        val app = context.applicationContext
        val queue = AndroidAutoDevelopQueue.get(app)
        return queue.manualOriginalIo {
            if (shouldStop()) throw java.util.concurrent.CancellationException("Trial preparation stopped")
            val key = app.noBackupFilesDir.canonicalPath to source
            val authority = authorities.getOrPut(key) { Authority(app, source) }
            authority.prepare(queue, strength, adjustments, conditions, shouldStop)
        }
    }

    private class Authority(private val app: Context, private val source: SharedOriginalReference) {
        private val id = ManualOriginalTrialRequests.identity(source)
        private val root = File(app.noBackupFilesDir, "raw-captures")
        private val handles = mutableMapOf<Long, ManualOriginalTrialHandle>()
        private val preflightResources = DngIoResources()
        private var preflightFd: ParcelFileDescriptor? = null
        private var preflightSlot: AutoDevelopCoordinator.Attempt? = null
        private var geometry: Original24PreflightGeometry? = null
        private var currentRequest: Original24Request? = null // Same serialized metadata owner as publication.
        private var previewSession: Original24PreviewSession? = null
        private var sessionEpoch = -1L
        private var previewSlot: AutoDevelopCoordinator.Attempt? = null
        private val requests = ManualOriginalTrialRequests(source,
            AndroidAtomicJournalStorage(File(app.noBackupFilesDir, "manual-original24-requests"), id,
                CaptureJournalFile.MANUAL_REQUEST), ::canSupersede)

        private fun generationFiles(request: Original24Request) = RenderedJpegFiles(root, id, request.generation)
        private fun storage(request: Original24Request, kind: CaptureJournalFile): AtomicJournalStorage {
            val directory = generationFiles(request).generationRoot()
            return AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), kind)
        }
        private fun journal(request: Original24Request) = JpegPublicationJournal(id, request.generation,
            storage(request, CaptureJournalFile.JPEG_PUBLICATION))
        private fun canSupersede(previous: Original24Request): Boolean {
            val handle = handles[previous.generation]
            if (handle?.hasUnreleasedResources == true || handle?.state?.value?.phase in
                setOf(ManualOriginalTrialSavePhase.HOLD, ManualOriginalTrialSavePhase.UNCONFIRMED,
                    ManualOriginalTrialSavePhase.SAVING)) return false
            val publication = journal(previous).read()
            if (publication != null) return publication.phase == JpegPublicationPhase.PUBLISHED
            return storage(previous, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES) == null
        }

        fun prepare(queue: AutoDevelopQueue, strength: Double, adjustments: PhotoAdjustments,
            conditions: AutoDevelopConditions, stop: () -> Boolean): ManualOriginalTrialHandle {
            if (preflightSlot != null) throw IOException("Trial preflight resources remain held")
            if (source.fingerprint.bytes > 64L * 1024 * 1024) throw IOException("Original source byte limit")
            // Header admission precedes durable request allocation, so >24MP never creates a held generation.
            if (geometry == null) geometry = inspect(conditions, stop)
            if (stop()) throw java.util.concurrent.CancellationException("Trial preparation stopped")
            currentRequest = null // A failed/uncertain metadata change cannot leave an older owner authoritative.
            val request = requests.prepare(strength, adjustments).also { currentRequest = it }
            return handles.getOrPut(request.generation) {
                val backend = Backend(app, request, requireNotNull(geometry), ::journal) { currentRequest == request }
                val owner = ManualOriginalTrialSaveOwner(request, backend,
                    storage(request, CaptureJournalFile.MANUAL_REQUEST)) { currentRequest == request }
                ManualOriginalTrialHandle(queue, owner, requireNotNull(geometry), backend) {
                    currentRequest == request && requests.isCurrent(request)
                }
            }
        }

        fun renderPreview(handle: ManualOriginalTrialHandle, conditions: AutoDevelopConditions,
            stop: () -> Boolean): ManualOriginalTrialPreview {
            if (previewSlot != null || sessionEpoch != previewEpoch.get()) discardPreview()
            val slot = DevelopExecution.tryStartObserved(conditions) ?: throw IOException("Trial preview admission blocked")
            previewSlot = slot
            var delivered = false
            var bitmap: Bitmap? = null
            var comparison: Bitmap? = null
            try {
                val session = previewSession ?: run {
                    val parent = File(app.noBackupFilesDir.canonicalFile, "manual-original24-previews")
                    if (Files.isSymbolicLink(parent.toPath()) || parent.canonicalFile != parent ||
                        (!parent.isDirectory && !parent.mkdirs())) throw IOException("Trial preview directory unavailable")
                    Original24PreviewSession(source, File(parent, UUID.randomUUID().toString()),
                        { Original24Resources.requireAvailable(app) }, handle.geometry, ::readOriginal).also {
                        previewSession = it; sessionEpoch = previewEpoch.get()
                    }
                }
                activePreview = session
                DevelopExecution.registerStop(slot, session::requestStop)
                bitmap = session.render(handle.request.strength, handle.request.adjustments) {
                    stop() || DevelopExecution.shouldStop(slot) || currentRequest != handle.request || sessionEpoch != previewEpoch.get()
                }
                comparison = session.comparisonBitmap {
                    stop() || DevelopExecution.shouldStop(slot) || currentRequest != handle.request || sessionEpoch != previewEpoch.get()
                }
                if (stop() || DevelopExecution.shouldStop(slot) || currentRequest != handle.request || sessionEpoch != previewEpoch.get())
                    throw java.util.concurrent.CancellationException("Trial preview stopped")
                val delivery = ManualOriginalTrialPreview(handle, requireNotNull(bitmap), comparison)
                delivered = true
                return delivery
            } finally {
                activePreview = null
                if (!delivered) { bitmap?.recycle(); comparison?.recycle(); previewSession?.close(); previewSession = null }
                if (previewSession?.hasUnreleasedResources != true) {
                    DevelopExecution.completeAfterResourcesClosed(slot); previewSlot = null
                }
            }
        }

        fun discardPreview() {
            previewSession?.close()
            if (previewSession?.hasUnreleasedResources == true) throw IOException("Trial preview resources remain held")
            previewSession = null
            previewSlot?.let { DevelopExecution.completeAfterResourcesClosed(it) }; previewSlot = null
        }

        private fun readOriginal(): InputStream = app.contentResolver.openInputStream(Uri.parse(source.uri))
            ?: throw IOException("Shared original unavailable")

        private fun inspect(conditions: AutoDevelopConditions, stop: () -> Boolean): Original24PreflightGeometry {
            val slot = DevelopExecution.tryStartObserved(conditions) ?: throw IOException("Original preflight admission blocked")
            preflightSlot = slot
            val signal = CancellationSignal()
            DevelopExecution.registerStop(slot) { signal.cancel() }
            val stopped = { signal.isCanceled || stop() || DevelopExecution.shouldStop(slot) }
            val directory = File(app.noBackupFilesDir.canonicalFile, "manual-original24-preflight")
            val snapshot = File(directory, UUID.randomUUID().toString() + ".dng")
            var failure: Throwable? = null
            try {
                if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile != directory)
                    throw IOException("Trial preflight directory redirected")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Trial preflight directory unavailable")
                DngContentVerifier.copy(source.fingerprint, { preflightResources.input(readOriginal()) }, {
                    preflightResources.output(Files.newOutputStream(snapshot.toPath(), StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))
                }, stopped)
                if (stopped()) throw IOException("Trial preflight stopped")
                val descriptor = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY)
                preflightFd = descriptor
                return Original24Preflight.inspect(descriptor.fd, signal).also {
                    if (stopped()) throw IOException("Trial preflight stopped")
                    DngContentVerifier.verify(source.fingerprint, { preflightResources.input(readOriginal()) }, stopped)
                }
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                var cleanup = preflightResources.releaseAll()
                try { preflightFd?.close(); preflightFd = null }
                catch (error: Throwable) { if (cleanup == null) cleanup = error else cleanup.addSuppressed(error) }
                if (!preflightResources.hasOpenResources && preflightFd == null) {
                    if (snapshot.exists() && !snapshot.delete()) {
                        val error = IOException("Trial preflight snapshot removal failed")
                        if (cleanup == null) cleanup = error else cleanup.addSuppressed(error)
                    }
                    DevelopExecution.completeAfterResourcesClosed(slot)
                    preflightSlot = null
                }
                cleanup?.let { if (failure == null) throw it else if (failure !== it) failure.addSuppressed(it) }
            }
        }
    }

    internal class Backend(context: Context, private val request: Original24Request,
        private val expectedGeometry: Original24PreflightGeometry,
        private val journal: (Original24Request) -> JpegPublicationJournal,
        private val isCurrent: () -> Boolean) : ManualOriginalTrialSaveBackend {
        private val app = context.applicationContext
        private val operation = AndroidRenderedJpegOperation.get(app, request.captureId, request.generation)
        @Volatile private var attempt: Original24Attempt? = null
        private val stopped = AtomicBoolean(false)
        override val hasUnreleasedResources get() = attempt?.hasUnreleasedResources == true
        fun requestStop() { stopped.set(true); attempt?.requestStop() }
        override fun readPublication() = journal(request).read()
        override fun render(stop: () -> Boolean): ManualOriginalTrialRender {
            check(attempt == null)
            val io = object : AutoRenderIo {
                override fun openOriginal() = app.contentResolver.openInputStream(Uri.parse(request.source.uri))
                    ?: throw IOException("Shared original unavailable")
                override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = throw IOException("Use verified snapshot")
                override fun openOutput(): OutputStream = operation.createOutput()
                override fun openOutputRead(): InputStream = operation.openOutputRead()
            }
            val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId,
                request.generation).generationRoot()
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Trial generation directory unavailable")
            // Only this production save opts in; low-level and diagnostic serial baselines stay explicit.
            val engine = Original24Attempt(app, request, io, File(directory, "original-work"),
                parallelFoundationWorkers = 2).also { attempt = it }
            if (stopped.get() || stop()) engine.requestStop()
            val result = engine.render(stop)
            engine.close()
            if (engine.hasUnreleasedResources) throw IOException("Trial producer resources remain held")
            if (result.observedGeometry != expectedGeometry) throw IOException("Original render geometry changed after preflight")
            return ManualOriginalTrialRender(RenderedJpegRequest(request.captureId, request.generation, request.source,
                request.recipe, result.output), result.geometry, result.fingerprint, request.strength, result.externalEv,
                request.adjustments)
        }
        override fun publisher(render: ManualOriginalTrialRender): ManualOriginalTrialPublication {
            val publisher = operation.publisher(render, setOf(ManualOriginalTrialRender.CONTRACT), isCurrent)
            return object : ManualOriginalTrialPublication {
                override val hasUnreleasedResources get() = publisher.hasUnreleasedResources
                override fun advance(stop: () -> Boolean) = publisher.advance(stop)
                override fun releaseResources() = publisher.releaseResources()
            }
        }
        override fun releaseResources() { attempt?.close() }
    }

    internal class ManualOriginalTrialHandle internal constructor(
        private val queue: AutoDevelopQueue,
        private val owner: ManualOriginalTrialSaveOwner,
        val geometry: Original24PreflightGeometry,
        private val backend: Backend,
        private val revalidate: () -> Boolean,
    ) {
        val request get() = owner.request
        val state get() = owner.state
        val appliedStrength get() = request.strength
        val appliedAdjustments get() = request.adjustments
        fun matches(source: SharedOriginalReference) = owner.matches(source)
        val hasUnreleasedResources get() = owner.hasUnreleasedResources
        @Volatile private var slot: AutoDevelopCoordinator.Attempt? = null // Written by IO; stop reads identity only.
        fun advance(conditions: AutoDevelopConditions, stop: () -> Boolean): CompletableFuture<ManualOriginalTrialSaveState> =
            advanceOwned({ conditions }, null, stop)

        /** Same owner, journal, queue and slot; only a foreground-started service uses this entry. */
        fun reserveForegroundExport(conditions: AutoDevelopConditions) = DevelopExecution.reserveForegroundExport(this, conditions)
        fun advanceForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest,
            conditions: () -> AutoDevelopConditions, stop: () -> Boolean) = advanceOwned(conditions, request, stop)
        fun stopForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest) {
            DevelopExecution.stopForegroundExport(request); requestStop()
        }
        fun abandonForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest) = DevelopExecution.abandonForegroundExport(request)

        fun updateExportConditions(conditions: AutoDevelopConditions) {
            slot?.let { DevelopExecution.updateExportConditions(it, conditions) }
        }

        private fun advanceOwned(conditions: () -> AutoDevelopConditions, foregroundExport: AutoDevelopCoordinator.ForegroundExportRequest?,
            stop: () -> Boolean): CompletableFuture<ManualOriginalTrialSaveState> = queue.manualOriginalIo {
            if (slot != null) {
                owner.releaseResources()
                if (hasUnreleasedResources) throw IOException("Trial resources remain held")
                DevelopExecution.completeAfterResourcesClosed(requireNotNull(slot)); slot = null
            }
            if (stop()) throw java.util.concurrent.CancellationException("Trial save stopped before admission")
            if (!revalidate()) throw CaptureJournalConflict()
            previewAuthority?.discardPreview(); previewAuthority = null
            val observed = conditions()
            val admitted = (if (foregroundExport != null) DevelopExecution.tryStartForegroundExport(foregroundExport, this, observed)
                else DevelopExecution.tryStartObserved(observed))
                ?: throw IOException("Trial save admission blocked")
            slot = admitted
            DevelopExecution.registerStop(admitted, backend::requestStop)
            try { owner.advance { stop() || DevelopExecution.shouldStop(admitted) } }
            catch (failure: OutOfMemoryError) { throw IOException("Original rendering memory unavailable", failure) }
            finally {
                try { owner.releaseResources() }
                finally { if (!hasUnreleasedResources) {
                    DevelopExecution.completeAfterResourcesClosed(admitted); slot = null
                } }
            }
        }
        fun requestStop() { slot?.let(DevelopExecution::requestStop) }
    }

    /** UI claims this one independent software bitmap, or closes an abandoned delivery. */
    internal class ManualOriginalTrialPreview(val save: ManualOriginalTrialHandle, private var owned: Bitmap?,
        private var ownedComparison: Bitmap?) : Closeable {
        val comparisonDescription = ownedComparison?.let { "撮影時WBのRAW基準表示（R7・ルック・手動調整なし、暫定色）" }
        init {
            val after = requireNotNull(owned)
            ownedComparison?.let { before ->
                require(after !== before && after.width == before.width && after.height == before.height)
            }
        }
        @Synchronized fun claimBitmap(): Bitmap = requireNotNull(owned).also { owned = null }
        @Synchronized fun claimComparisonBitmap(): Bitmap? = ownedComparison?.also { ownedComparison = null }
        @Synchronized override fun close() {
            owned?.recycle(); owned = null
            ownedComparison?.recycle(); ownedComparison = null
        }
    }
}
