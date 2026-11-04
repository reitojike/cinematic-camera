package dev.cinematic.p0.rawcapture

import android.content.Context
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import dev.cinematic.p0.jpegrender.JpegGenerationRecipe

/** Unconnected process owner. No production renderer is enrolled and no requests are fabricated from trial output. */
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
        val app = context.applicationContext
        val id = ManualDevelopRequests.identity(source)
        val queue = get(app)
        return queue.prepareManual(id) {
            ManualDevelopRequests(source, AndroidAtomicJournalStorage(File(app.noBackupFilesDir, "raw-captures"),
                id, CaptureJournalFile.MANUAL_REQUEST)).prepare(recipe.snapshot)
        }
    }

    private fun create(context: Context): AutoDevelopQueue {
        val root = File(context.noBackupFilesDir, "raw-captures")
        val catalog = AutoRendererCatalog(adapters) // Trial is never enrolled; full auto remains unavailable.
        return AutoDevelopQueue(Executors.newSingleThreadExecutor { command ->
            Thread(command, "auto-develop").apply { priority = Thread.MIN_PRIORITY }
        }, DevelopExecution.coordinator, journalFactory = { request ->
            // Auto requests refer only to newly captured, fully handed-off originals. Picker/manual edits are separate.
            val original = if (request.boundedManual) null else DngPublicationJournal(request.captureId,
                AndroidAtomicJournalStorage(root, request.captureId, CaptureJournalFile.DNG_PUBLICATION)).read()
            if (!request.boundedManual && (request.source.grant != OriginalGrant.APP_OWNED || original?.phase != DngPublicationPhase.SHARED_ONLY ||
                original.uri != request.source.uri || original.fingerprint != request.source.fingerprint)) {
                throw IOException("Shared original handoff is not confirmed")
            }
            val directory = RenderedJpegFiles(root, request.captureId, request.generation).generationRoot()
            AutoDevelopJournal(request.captureId, request.generation,
                AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
        }, backendFactory = { request -> AndroidAutoDevelopBackend(context, request, catalog) }).also(DevelopExecution::attach)
    }
}
