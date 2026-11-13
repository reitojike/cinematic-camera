package dev.cinematic.p0.rawcapture

import android.content.Context
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.gpu.DevelopEngineSetting
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
import java.util.concurrent.atomic.AtomicLong
import java.io.Closeable

/** Process-retained original-trial authority. All metadata, generation and publication use the sole
 * existing queue IO owner. Preparing never generates a JPEG or inserts a shared row. */
@RequiresApi(29)
internal object AndroidManualOriginalTrialSaves {
    private val authorities = mutableMapOf<Pair<String, SharedOriginalReference>, Authority>() // Queue IO only.
    // Queue-published immutable index; readers never access Authority's mutable maps.
    @Volatile private var prepared = emptyMap<SharedOriginalReference, ManualOriginalTrialHandle>()
    fun knownPrepared(source: SharedOriginalReference): ManualOriginalTrialHandle? = prepared[source]

    /** Only ordinary GPU cold-open hydration, after the input's existing source/geometry checks. */
    fun restorePreparedGpu(context: Context, source: SharedOriginalReference, geometry: Original24PreflightGeometry,
        shouldStop: () -> Boolean): CompletableFuture<ManualOriginalTrialHandle?> {
        val app = context.applicationContext
        val queue = AndroidAutoDevelopQueue.get(app)
        return queue.manualOriginalIo {
            if (shouldStop()) throw java.util.concurrent.CancellationException("GPU restoration stopped")
            if (app.packageName != "dev.cinematic.p0") return@manualOriginalIo prepared[source]
            val authority = authorities.getOrPut(app.noBackupFilesDir.canonicalPath to source) { Authority(app, source) }
            authority.restore(queue, geometry)?.also { prepared = prepared + (source to it) }
        }
    }
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
        diagnosticWorkers: Int? = null,
        checkPreviewSource: ((DngIoResources, () -> Boolean) -> Unit)? = null,
        shouldStop: () -> Boolean = { false }): CompletableFuture<ManualOriginalTrialPreview> {
        require(diagnosticWorkers == null || (dev.cinematic.p0.BuildConfig.EDITOR_PREVIEW_DIAGNOSTIC && diagnosticWorkers in 1..2))
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
            authority.renderPreview(handle, conditions, diagnosticWorkers, checkPreviewSource, shouldStop)
        }
    }

    /** Drafts borrow only an existing exact session. They never allocate a durable save request. */
    fun renderDraftPreview(context: Context, source: SharedOriginalReference, strength: Double,
        conditions: AutoDevelopConditions, adjustments: PhotoAdjustments, diagnosticWorkers: Int? = null,
        shouldStop: () -> Boolean): CompletableFuture<ManualOriginalDraftPreview?> {
        require(diagnosticWorkers == null || (dev.cinematic.p0.BuildConfig.EDITOR_PREVIEW_DIAGNOSTIC && diagnosticWorkers in 1..2))
        val app = context.applicationContext
        return AndroidAutoDevelopQueue.get(app).manualOriginalIo {
            if (shouldStop()) throw java.util.concurrent.CancellationException("Draft preview stopped")
            val authority = authorities[app.noBackupFilesDir.canonicalPath to source]
            if (authority == null || previewAuthority !== authority) null
            else authority.renderDraftPreview(strength, adjustments, conditions, diagnosticWorkers, shouldStop)
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

    /** GPU input already proved this exact fingerprint and native geometry on its own owner.
     * Only metadata is serialized here; never re-enter the CPU preview/preflight admission. */
    fun prepareGpu(context: Context, source: SharedOriginalReference, geometry: Original24PreflightGeometry,
        strength: Double, conditions: AutoDevelopConditions, adjustments: PhotoAdjustments,
        shouldStop: () -> Boolean): CompletableFuture<ManualOriginalTrialHandle> {
        val app = context.applicationContext
        val queue = AndroidAutoDevelopQueue.get(app)
        return queue.manualOriginalIo {
            if (shouldStop()) throw java.util.concurrent.CancellationException("GPU save preparation stopped")
            val authority = authorities.getOrPut(app.noBackupFilesDir.canonicalPath to source) { Authority(app, source) }
            authority.prepare(queue, strength, adjustments, conditions, shouldStop, geometry)
                .also { prepared = prepared + (source to it) }
        }
    }

    private class Authority(private val app: Context, private val source: SharedOriginalReference) {
        private val id = ManualOriginalTrialRequests.identity(source)
        private val root = File(app.noBackupFilesDir, "raw-captures")
        private val handles = mutableMapOf<Long, ManualOriginalTrialHandle>()
        private val recoveryConsents = mutableMapOf<Long, ExplicitRecoveryExports>()
        private val recoveryRendererContract = "GPU:${ManualOriginalTrialRender.CONTRACT.renderer}:${ManualTrialAttemptCodec.EXECUTION_VERSION}"
        private val preflightResources = DngIoResources()
        private var preflightFd: ParcelFileDescriptor? = null
        private var preflightSnapshot: File? = null
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
        private fun consent(request: Original24Request) = recoveryConsents.getOrPut(request.generation) {
            ExplicitRecoveryExports(storage(request, CaptureJournalFile.EXPLICIT_RECOVERY_EXPORT))
        }
        private fun authoritative(request: Original24Request): Boolean {
            if (currentRequest != request || preflightResources.hasOpenResources || !requests.isCurrent(request) || !consent(request).oldMayRun()) return false
            if (request.generation > 1) {
                val predecessor = consent(request.copy(generation = request.generation - 1))
                predecessor.read()?.let {
                    if (it.request != request || it.rendererContract != recoveryRendererContract || !predecessor.newMayRun(it)) return false
                }
            }
            return true
        }
        private fun isExplicitRecovery(request: Original24Request): Boolean {
            if (request.generation <= 1) return false
            val saved = consent(request.copy(generation = request.generation - 1)).read() ?: return false
            if (saved.request != request || saved.rendererContract != recoveryRendererContract) throw CaptureJournalConflict()
            return true
        }
        private fun attemptJournal(request: Original24Request) = if (app.packageName == "dev.cinematic.p0")
            ManualOriginalTrialAttemptJournal(request, storage(request, CaptureJournalFile.MANUAL_ATTEMPT),
                storage(request, CaptureJournalFile.MANUAL_REQUEST)) else null
        private fun canSupersede(previous: Original24Request): Boolean {
            val handle = handles[previous.generation]
            if (handle?.state?.value?.phase == ManualOriginalTrialSavePhase.CANCELLED)
                return handle.canSupersedeConfirmedCancellation()
            if (handle?.hasUnreleasedResources == true || handle?.state?.value?.phase in
                setOf(ManualOriginalTrialSavePhase.HOLD, ManualOriginalTrialSavePhase.UNCONFIRMED,
                    ManualOriginalTrialSavePhase.SAVING, ManualOriginalTrialSavePhase.GPU_FAILED, ManualOriginalTrialSavePhase.CANCELLED)) return false
            val publication = handle?.backendPublication() ?: journal(previous).read()
            if (publication != null) return publication.phase == JpegPublicationPhase.PUBLISHED
            attemptJournal(previous)?.read()?.let { return it.phase == ManualTrialAttemptPhase.CANCELLED_CLEAN }
            if (isExplicitRecovery(previous)) return false // A READY consent must not be superseded by edit-only restoration.
            return storage(previous, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES) == null
        }

        fun restore(queue: AutoDevelopQueue, verifiedGeometry: Original24PreflightGeometry): ManualOriginalTrialHandle? {
            val request = requests.current() ?: return null
            if (geometry != null && geometry != verifiedGeometry) throw IOException("GPU input geometry changed")
            geometry = verifiedGeometry
            currentRequest = request
            return handle(queue, request)
        }

        private fun handle(queue: AutoDevelopQueue, request: Original24Request): ManualOriginalTrialHandle =
            handles.getOrPut(request.generation) {
                val backend = Backend(app, request, requireNotNull(geometry), ::journal) { authoritative(request) }
                val owner = ManualOriginalTrialSaveOwner(request, backend,
                    storage(request, CaptureJournalFile.MANUAL_REQUEST), attemptJournal(request)) { authoritative(request) }
                owner.restoreDurableState()
                ManualOriginalTrialHandle(queue, owner, requireNotNull(geometry), backend, isExplicitRecovery(request)) {
                    authoritative(request)
                }.also { it.explicitRecovery = { stop -> recoverExplicitly(queue, request, stop) } }
            }

        private fun recoverExplicitly(queue: AutoDevelopQueue, previous: Original24Request,
            stop: () -> Boolean): ManualOriginalTrialHandle {
            if (app.packageName != "dev.cinematic.p0") throw IOException("Recovery requires ordinary GPU save")
            if (preflightResources.hasOpenResources) releasePreflight()
            val old = handles[previous.generation] ?: throw CaptureJournalConflict()
            if (!old.quiescentForRecovery()) throw IOException("Old save resources or slot remain held")
            if (old.hasPublishedEvidence()) throw IOException("Published JPEG evidence must resume the same publication")
            discardPreview()
            val slot = consent(previous)
            val saved = slot.read()
            if (saved != null) {
                if (saved.previous != previous) throw CaptureJournalConflict()
                val intent = storage(previous, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES)
                    ?: throw CaptureJournalConflict()
                if (SaveRecoveryCodec.digest(intent).joinToString("") { "%02x".format(it) } != saved.previousIntentSha256 ||
                    !intent.contentEquals(ManualOriginalTrialRequestCodec.encode(previous)) ||
                    old.backendPublication() != saved.previousPublication || saved.rendererContract != recoveryRendererContract)
                    throw CaptureJournalConflict()
                DngContentVerifier.verify(source.fingerprint, { preflightResources.input(readOriginal()) }, stop)
                // Re-read a committed consent after lost acknowledgement. This does not allocate a new key.
                slot.accept(saved) { if (stop() || !old.quiescentForRecovery() || preflightResources.hasOpenResources) throw CaptureJournalConflict() }
                val installed = requests.installRecovery(saved) { slot.newMayRun(saved) && !stop() && old.quiescentForRecovery() }
                currentRequest = installed
                return handle(queue, installed).also { prepared = prepared + (source to it) }
            }
            if (!authoritative(previous)) throw CaptureJournalConflict()
            val oldPublication = old.backendPublication()
            if (oldPublication != null && oldPublication.phase != JpegPublicationPhase.UNKNOWN) throw CaptureJournalConflict()
            if (old.state.value.phase !in setOf(ManualOriginalTrialSavePhase.HOLD, ManualOriginalTrialSavePhase.UNCONFIRMED))
                throw CaptureJournalConflict()
            val intentStorage = storage(previous, CaptureJournalFile.MANUAL_REQUEST)
            val intent = intentStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES) ?: throw CaptureJournalConflict()
            if (!intent.contentEquals(ManualOriginalTrialRequestCodec.encode(previous))) throw CaptureJournalConflict()
            if (previous.generation == Long.MAX_VALUE) throw IOException("Recovery generation exhausted")
            val value = ExplicitRecoveryExport(UUID.randomUUID().toString().replace("-", ""), previous,
                SaveRecoveryCodec.digest(intent).joinToString("") { "%02x".format(it) }, oldPublication,
                previous.copy(generation = previous.generation + 1), recoveryRendererContract)
            DngContentVerifier.verify(source.fingerprint, { preflightResources.input(readOriginal()) }, stop)
            slot.accept(value) {
                if (stop() || currentRequest != previous || requests.current() != previous || !old.quiescentForRecovery() ||
                    preflightResources.hasOpenResources || old.backendPublication() != oldPublication ||
                    intentStorage.read(ManualOriginalTrialRequestCodec.MAX_BYTES)?.contentEquals(intent) != true)
                    throw CaptureJournalConflict()
            }
            currentRequest = null // A cursor failure cannot leave the old owner authorized.
            val installed = requests.installRecovery(value) { slot.newMayRun(value) && !stop() }
            currentRequest = installed
            return handle(queue, installed).also { prepared = prepared + (source to it) }
        }

        fun prepare(queue: AutoDevelopQueue, strength: Double, adjustments: PhotoAdjustments,
            conditions: AutoDevelopConditions, stop: () -> Boolean,
            verifiedGpuGeometry: Original24PreflightGeometry? = null): ManualOriginalTrialHandle {
            if (preflightSlot != null) releasePreflight()
            if (source.fingerprint.bytes > 64L * 1024 * 1024) throw IOException("Original source byte limit")
            // Header admission precedes durable request allocation, so >24MP never creates a held generation.
            if (verifiedGpuGeometry != null) {
                if (geometry != null && geometry != verifiedGpuGeometry) throw IOException("GPU input geometry changed")
                geometry = verifiedGpuGeometry
            } else if (geometry == null) geometry = inspect(conditions, stop)
            if (stop()) throw java.util.concurrent.CancellationException("Trial preparation stopped")
            currentRequest = null // A failed/uncertain metadata change cannot leave an older owner authoritative.
            val request = requests.prepare(strength, adjustments).also { currentRequest = it }
            return handle(queue, request)
        }

        fun renderPreview(handle: ManualOriginalTrialHandle, conditions: AutoDevelopConditions,
            diagnosticWorkers: Int?, checkPreviewSource: ((DngIoResources, () -> Boolean) -> Unit)?,
            stop: () -> Boolean): ManualOriginalTrialPreview {
            if (previewSlot != null || sessionEpoch != previewEpoch.get()) discardPreview()
            val slot = DevelopExecution.tryStartPreviewObserved(conditions) ?: throw IOException("Trial preview admission blocked")
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
                        { stage -> Original24Resources.requireAvailable(app, stage) }, handle.geometry, checkPreviewSource, ::readOriginal).also {
                        previewSession = it; sessionEpoch = previewEpoch.get()
                    }
                }
                activePreview = session
                DevelopExecution.registerStop(slot, session::requestStop)
                val stopped = { stop() || DevelopExecution.shouldStop(slot) || currentRequest != handle.request || sessionEpoch != previewEpoch.get() }
                bitmap = if (diagnosticWorkers == null) session.render(handle.request.strength, handle.request.adjustments, stopped)
                    else session.renderDiagnostic(handle.request.strength, handle.request.adjustments, false, diagnosticWorkers, stopped)
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

        fun renderDraftPreview(strength: Double, adjustments: PhotoAdjustments,
            conditions: AutoDevelopConditions, diagnosticWorkers: Int?, stop: () -> Boolean): ManualOriginalDraftPreview? {
            if (previewSlot != null || sessionEpoch != previewEpoch.get()) discardPreview()
            val session = previewSession?.takeIf { it.hasDraftDecisions } ?: return null
            val request = currentRequest ?: return null
            val slot = DevelopExecution.tryStartPreviewObserved(conditions) ?: throw IOException("Draft preview admission blocked")
            previewSlot = slot
            var delivered = false
            var bitmap: Bitmap? = null
            var comparison: Bitmap? = null
            val stopped = { stop() || DevelopExecution.shouldStop(slot) || currentRequest != request || sessionEpoch != previewEpoch.get() }
            try {
                activePreview = session
                DevelopExecution.registerStop(slot, session::requestStop)
                bitmap = (if (diagnosticWorkers == null) session.renderDraft(strength, adjustments, stopped)
                    else session.renderDiagnostic(strength, adjustments, true, diagnosticWorkers, stopped)) ?: return null
                comparison = session.comparisonBitmap(maxEdge = 256, shouldStop = stopped)
                if (stopped()) throw java.util.concurrent.CancellationException("Draft preview stopped")
                return ManualOriginalDraftPreview(requireNotNull(bitmap), comparison).also { delivered = true }
            } finally {
                activePreview = null
                if (!delivered) { bitmap?.recycle(); comparison?.recycle(); session.close(); previewSession = null }
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
            val slot = DevelopExecution.tryStartPreviewObserved(conditions) ?: throw IOException("Original preflight admission blocked")
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
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).also { preflightSnapshot = snapshot })
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
                try { releasePreflight() } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }

        private fun releasePreflight() {
            var cleanup = preflightResources.releaseAll()
            try { preflightFd?.close(); preflightFd = null }
            catch (error: Throwable) { if (cleanup == null) cleanup = error else cleanup.addSuppressed(error) }
            if (!preflightResources.hasOpenResources && preflightFd == null) {
                try {
                    preflightSnapshot?.let { Files.deleteIfExists(it.toPath()); preflightSnapshot = null }
                    preflightSlot?.let(DevelopExecution::completeAfterResourcesClosed)
                    preflightSlot = null
                } catch (error: Throwable) { if (cleanup == null) cleanup = error else cleanup.addSuppressed(error) }
            }
            cleanup?.let { throw it }
        }
    }

    internal class Backend(context: Context, private val request: Original24Request,
        private val expectedGeometry: Original24PreflightGeometry,
        private val journal: (Original24Request) -> JpegPublicationJournal,
        private val isCurrent: () -> Boolean) : ManualOriginalTrialSaveBackend {
        private val app = context.applicationContext
        private val operation = AndroidRenderedJpegOperation.get(app, request.captureId, request.generation)
        @Volatile private var attempt: Original24Attempt? = null
        private var outputCleanupPending = false
        private var selectedEngine: DevelopEngine? = null
        private val recoveryResources = DngIoResources()
        private fun recoveryStorage(kind: CaptureJournalFile): AtomicJournalStorage {
            val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId, request.generation).generationRoot()
            return AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), kind)
        }
        private val recoveryJournal = JpegPublicationJournal(request.captureId, request.generation, recoveryStorage(CaptureJournalFile.JPEG_RECOVERY))
        private val reconciliation = ReconciledJpegPublication(journal(request), recoveryJournal,
            JpegReconciliationReceipt(recoveryStorage(CaptureJournalFile.JPEG_RECONCILIATION)),
            MediaStoreJpegRows(app), recoveryResources, isCurrent)
        override val hasUnreleasedResources get() = attempt?.hasUnreleasedResources == true || outputCleanupPending || recoveryResources.hasOpenResources
        // The admitted coordinator owns the cancellation latch. Do not retain it across retries.
        fun requestStop() { attempt?.requestStop() }
        override fun readPublication() = reconciliation.read()
        override val reconciliationFinding get() = reconciliation.lastFinding
        fun hasPublishedEvidence() = reconciliation.hasPublishedEvidence()
        override fun reconcilePublication(stop: () -> Boolean): JpegPublicationRecord? {
            val old = readPublication() ?: return null
            return reconciliation.reconcile(old.render, setOf(ManualOriginalTrialRender.CONTRACT), stop)
        }
        override fun pinDurableGpuEngine() {
            check(app.packageName == "dev.cinematic.p0")
            val selected = selectedEngine ?: DevelopEngineSetting(app).beginExport().also { selectedEngine = it }
            if (selected != DevelopEngine.GPU) throw IOException("Durable recovery requires the fixed GPU engine")
        }
        override fun render(stop: () -> Boolean): ManualOriginalTrialRender {
            check(attempt == null)
            // Freeze once at the first actual save, including any explicit retry.
            val selected = selectedEngine ?: DevelopEngineSetting(app).beginExport().also { selectedEngine = it }
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
                parallelFoundationWorkers = 2, parallelGlowWorkers = 2,
                parallelToneWorkers = 2, parallelMistWorkers = 2, developEngine = selected).also { attempt = it }
            if (stop()) engine.requestStop()
            outputCleanupPending = true
            try {
                val result = engine.render(stop)
                engine.close()
                if (engine.hasUnreleasedResources) throw IOException("Trial producer resources remain held")
                if (result.observedGeometry != expectedGeometry) throw IOException("Original render geometry changed after preflight")
                val render = ManualOriginalTrialRender(RenderedJpegRequest(request.captureId, request.generation, request.source,
                    request.recipe, result.output), result.geometry, result.fingerprint, request.strength, result.externalEv,
                    request.adjustments)
                outputCleanupPending = false // Valid output remains owned by the existing publication workflow.
                return render
            } catch (failure: Throwable) {
                try { releaseResources() } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                if (engine.cancellationBeforeJpegConfirmed && failure.suppressed.isEmpty() && !hasUnreleasedResources)
                    throw ConfirmedSaveCancellationException(failure)
                throw reportFailure(failure)
            }
        }
        override fun publisher(render: ManualOriginalTrialRender): ManualOriginalTrialPublication {
            val publisher = operation.publisher(render, setOf(ManualOriginalTrialRender.CONTRACT), isCurrent,
                recoveryJournal.takeIf { it.read() != null })
            return object : ManualOriginalTrialPublication {
                override val hasUnreleasedResources get() = publisher.hasUnreleasedResources
                override fun advance(stop: () -> Boolean) = try { publisher.advance(stop) }
                    catch (failure: Throwable) { throw reportFailure(failure) }
                override fun releaseResources() = publisher.releaseResources()
            }
        }
        private fun reportFailure(failure: Throwable): Throwable {
            if (selectedEngine != DevelopEngine.GPU || (failure !is Exception && failure !is OutOfMemoryError) ||
                failure is java.util.concurrent.CancellationException || failure is android.os.OperationCanceledException ||
                failure is DngVerificationStopped || failure is CaptureJournalConflict) return failure
            val reported = failure as? GpuSaveRenderException
                ?: GpuSaveRenderException(GpuSaveFailureReason.SAVE_FAILED, false, failure)
            try { AndroidGpuSaveFailures.record(app, reported.reason) } catch (record: Throwable) {
                if (record !== reported) reported.addSuppressed(record)
            }
            return reported
        }
        override fun releaseResources() {
            recoveryResources.releaseAll()?.let { throw it }
            attempt?.let { it.close(); check(!it.hasUnreleasedResources); attempt = null }
            if (outputCleanupPending) {
                operation.discardFailedOutput()
                outputCleanupPending = false
            }
        }
    }

    internal class ManualOriginalTrialHandle internal constructor(
        private val queue: AutoDevelopQueue,
        private val owner: ManualOriginalTrialSaveOwner,
        val geometry: Original24PreflightGeometry,
        private val backend: Backend,
        val isExplicitRecoveryRequest: Boolean,
        private val revalidate: () -> Boolean,
    ) {
        val request get() = owner.request
        val state get() = owner.state
        val appliedStrength get() = request.strength
        val appliedAdjustments get() = request.adjustments
        fun matches(source: SharedOriginalReference) = owner.matches(source)
        val hasUnreleasedResources get() = owner.hasUnreleasedResources
        internal var explicitRecovery: ((() -> Boolean) -> ManualOriginalTrialHandle)? = null
        internal fun backendPublication() = backend.readPublication()
        internal fun hasPublishedEvidence() = backend.hasPublishedEvidence()
        internal fun quiescentForRecovery() = slot == null && !hasUnreleasedResources &&
            state.value.phase != ManualOriginalTrialSavePhase.SAVING
        fun requestExplicitRecovery(stop: () -> Boolean): CompletableFuture<ManualOriginalTrialHandle> = queue.manualOriginalIo {
            requireNotNull(explicitRecovery).invoke(stop)
        }
        @Volatile private var slot: AutoDevelopCoordinator.Attempt? = null // Written by IO; stop reads identity only.
        fun canSupersedeConfirmedCancellation() = slot == null && owner.canSupersedeConfirmedCancellation()
        fun advance(conditions: AutoDevelopConditions, retryGpuFailure: Boolean = false, retryCancellation: Boolean = false, stop: () -> Boolean): CompletableFuture<ManualOriginalTrialSaveState> =
            advanceOwned({ conditions }, null, stop, retryGpuFailure, retryCancellation)

        fun recheckPublication(conditions: AutoDevelopConditions, stop: () -> Boolean) =
            advanceOwned({ conditions }, null, stop, false, false, publicationOnly = true)

        /** Same owner, journal, queue and slot; only a foreground-started service uses this entry. */
        fun reserveForegroundExport(conditions: AutoDevelopConditions) = DevelopExecution.reserveForegroundExport(this, conditions)
        fun advanceForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest,
            conditions: () -> AutoDevelopConditions, retryGpuFailure: Boolean = false, retryCancellation: Boolean = false, stop: () -> Boolean) =
            advanceOwned(conditions, request, stop, retryGpuFailure, retryCancellation)
        fun stopForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest) {
            DevelopExecution.stopForegroundExport(request); requestStop()
        }
        fun abandonForegroundExport(request: AutoDevelopCoordinator.ForegroundExportRequest) = DevelopExecution.abandonForegroundExport(request)

        fun updateExportConditions(conditions: AutoDevelopConditions) {
            slot?.let { DevelopExecution.updateExportConditions(it, conditions) }
        }

        private fun advanceOwned(conditions: () -> AutoDevelopConditions, foregroundExport: AutoDevelopCoordinator.ForegroundExportRequest?,
            stop: () -> Boolean, retryGpuFailure: Boolean, retryCancellation: Boolean,
            publicationOnly: Boolean = false): CompletableFuture<ManualOriginalTrialSaveState> = queue.manualOriginalIo {
            if (slot != null) {
                owner.releaseResources()
                if (hasUnreleasedResources) throw IOException("Trial resources remain held")
                if (!DevelopExecution.completeAfterResourcesClosed(requireNotNull(slot))) {
                    owner.confirmCancellationAfterRelease(slotReleased = false)
                    throw IOException("Trial slot release unconfirmed")
                }
                slot = null
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
            try { owner.advance(retryGpuFailure = retryGpuFailure, retryCancellation = retryCancellation,
                publicationOnly = publicationOnly) { stop() || DevelopExecution.shouldStop(admitted) } }
            catch (failure: OutOfMemoryError) { throw IOException("Original rendering memory unavailable", failure) }
            finally {
                try { owner.releaseResources() }
                finally { if (!hasUnreleasedResources) {
                    if (DevelopExecution.completeAfterResourcesClosed(admitted)) slot = null
                } }
            }
            owner.confirmCancellationAfterRelease(slotReleased = slot == null)
            owner.state.value
        }
        fun requestStop() { slot?.let(DevelopExecution::requestStop) }
    }

    /** Display-only draft delivery, deliberately without a save handle or committed settings. */
    internal class ManualOriginalDraftPreview(private var after: Bitmap?, private var before: Bitmap?) : Closeable {
        @Synchronized fun claimBitmap(): Bitmap = requireNotNull(after).also { after = null }
        @Synchronized fun claimComparisonBitmap(): Bitmap? = before?.also { before = null }
        @Synchronized override fun close() { after?.recycle(); after = null; before?.recycle(); before = null }
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
