package dev.cinematic.p0.photoedit

import android.content.Context
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.util.concurrent.Executors

/** Single process-wide IO queue provides AtomicFile exclusion and close-admission order. */
internal class AndroidPhotoEditRecipes(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "photo-edit-recipes")
    private val queue = PhotoEditRecipeQueue(executor) { source ->
        AndroidAtomicJournalStorage(root, ManualOriginalTrialRequests.identity(source), CaptureJournalFile.EDIT_RECIPE)
    }
    /** Call from IO; it observes all earlier admitted close requests. */
    fun read(source: SharedOriginalReference): PhotoEditValues? = queue.read(source).get()
    /** Call when Close is accepted, not later from a cancellable/possibly reordered IO coroutine. */
    fun save(source: SharedOriginalReference, values: PhotoEditValues) = queue.save(source, values)

    private companion object {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "photo-edit-recipes") }
    }
}
