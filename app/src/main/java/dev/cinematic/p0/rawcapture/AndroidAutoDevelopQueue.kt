package dev.cinematic.p0.rawcapture

import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import dev.cinematic.p0.jpegrender.JpegGenerationRecipe

/** One process owner. Restore fixed requests, never fabricate production requests from trial output. */
@RequiresApi(29)
internal object AndroidAutoDevelopQueue {
    private var owner: AutoDevelopQueue? = null
    private var adapters: List<RendererAdapter> = emptyList()
    /** Explicit enrollment before creating the process owner. No production caller/provider exists yet. */
    @Synchronized fun enrollManualRenderer(adapter: RendererAdapter) {
        check(owner == null) { "Retain the existing process owner; do not replace a live catalog" }
        require(adapter.quality == AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY &&
            adapter.contract == JpegGenerationRecipe.PUBLICATION_CONTRACT)
        check(adapters.isEmpty())
        adapters = listOf(adapter)
    }
    @Synchronized fun get(context: Context): AutoDevelopQueue = owner ?: create(context.applicationContext).also { owner = it }

    fun prepareManual(context: Context, source: SharedOriginalReference, recipe: JpegGenerationRecipe): CompletableFuture<ManualDevelopHandle> {
        require(!recipe.isTrial) { "Private trial has a separate handle" }
        val app = context.applicationContext
        val id = ManualDevelopRequests.identity(source)
        val queue = get(app)
        return queue.prepareManual(id) {
            ManualDevelopRequests(source, AndroidAtomicJournalStorage(File(app.noBackupFilesDir, "raw-captures"),
                id, CaptureJournalFile.MANUAL_REQUEST)).prepare(recipe.snapshot)
        }
    }
    fun preparePrivateTrial(context: Context, source: SharedOriginalReference,
        recipe: JpegGenerationRecipe = JpegGenerationRecipe.trial(),
        reuseSession: PrivateTrialReuseSession? = null,
        shouldStop: () -> Boolean = { false }): CompletableFuture<PrivateTrialHandle> {
        require(recipe.isTrial && JpegGenerationRecipe.fromTrialSnapshot(recipe.snapshot) != null)
        val app = context.applicationContext
        val id = ManualDevelopRequests.identity(source)
        // This metadata admission runs on the same IO owner before any latest-generation fence is installed.
        // A cancelled Future alone is not evidence that native/FD/stream resources have actually closed.
        return get(app).preparePrivateTrial(id, shouldStop) {
            ManualDevelopRequests(source, AndroidAtomicJournalStorage(File(app.noBackupFilesDir, "raw-captures"),
                id, CaptureJournalFile.MANUAL_REQUEST)).prepare(recipe.snapshot).also {
                // Bind on this IO owner before acceptance constructs a backend/attempt for the request.
                PrivateTrialReuseRegistry.bind(reuseSession, it)
            }
        }
    }

    private fun create(context: Context): AutoDevelopQueue {
        val root = File(context.noBackupFilesDir, "raw-captures")
        fun journal(captureId: String, generation: Long): AutoDevelopJournal {
            val directory = RenderedJpegFiles(root, captureId, generation).generationRoot()
            return AutoDevelopJournal(captureId, generation,
                AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
        }
        val catalog = AutoRendererCatalog(adapters + BoundedJpegRendererAdapter.privateTrial(PrivateTrialReuseRegistry::forRequest)) // Completed/full auto enrollment remains empty.
        return AutoDevelopQueue(Executors.newSingleThreadExecutor { command ->
            Thread(command, "auto-develop").apply { priority = Thread.MIN_PRIORITY }
        }, DevelopExecution.coordinator, journalFactory = { request ->
            // Auto requests refer only to newly captured, fully handed-off originals. Picker/manual edits are separate.
            val original = if (request.interactive) null else DngPublicationJournal(request.captureId,
                AndroidAtomicJournalStorage(root, request.captureId, CaptureJournalFile.DNG_PUBLICATION)).read()
            if (!request.interactive && (request.source.grant != OriginalGrant.APP_OWNED || original?.phase != DngPublicationPhase.SHARED_ONLY ||
                original.uri != request.source.uri || original.fingerprint != request.source.fingerprint)) {
                throw IOException("Shared original handoff is not confirmed")
            }
            journal(request.captureId, request.generation)
        }, backendFactory = { request -> AndroidAutoDevelopBackend(context, request, catalog) }).also { queue ->
            // First task on the existing IO owner, ahead of manual/private prepare. Failure retains this owner.
            queue.restore { AutoDevelopInventory(root, { id, generation -> journal(id, generation).read() }).load() }
                .whenComplete { _, failure ->
                    if (failure != null) Log.w("CinematicRaw", "AUTO_INVENTORY_HELD type=${failure.javaClass.simpleName}")
                }
            DevelopExecution.attach(queue)
        }
    }
}
