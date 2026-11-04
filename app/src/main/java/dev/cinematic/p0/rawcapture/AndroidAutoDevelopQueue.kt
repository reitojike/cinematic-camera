package dev.cinematic.p0.rawcapture

import android.content.Context
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Unconnected process owner. No production renderer is enrolled and no requests are fabricated from trial output. */
@RequiresApi(29)
internal object AndroidAutoDevelopQueue {
    private var owner: AutoDevelopQueue? = null
    @Synchronized fun get(context: Context): AutoDevelopQueue = owner ?: create(context.applicationContext).also { owner = it }

    private fun create(context: Context): AutoDevelopQueue {
        val root = File(context.noBackupFilesDir, "raw-captures")
        val catalog = AutoRendererCatalog() // StageClearTrialRenderer is PREVIEW/diagnostic/NOT_RUN, not FINAL.
        return AutoDevelopQueue(Executors.newSingleThreadExecutor { command ->
            Thread(command, "auto-develop").apply { priority = Thread.MIN_PRIORITY }
        }, AutoDevelopCoordinator(), journalFactory = { request ->
            // Auto requests refer only to newly captured, fully handed-off originals. Picker/manual edits are separate.
            val original = DngPublicationJournal(request.captureId,
                AndroidAtomicJournalStorage(root, request.captureId, CaptureJournalFile.DNG_PUBLICATION)).read()
            if (request.source.grant != OriginalGrant.APP_OWNED || original?.phase != DngPublicationPhase.SHARED_ONLY ||
                original.uri != request.source.uri || original.fingerprint != request.source.fingerprint) {
                throw IOException("Shared original handoff is not confirmed")
            }
            val directory = RenderedJpegFiles(root, request.captureId, request.generation).generationRoot()
            AutoDevelopJournal(request.captureId, request.generation,
                AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
        }, backendFactory = { request -> AndroidAutoDevelopBackend(context, request, catalog) })
    }
}
