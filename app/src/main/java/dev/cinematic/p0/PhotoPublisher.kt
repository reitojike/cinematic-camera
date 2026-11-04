package dev.cinematic.p0

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.Closeable

enum class PhotoFormat(val mimeType: String) {
    JPEG("image/jpeg"), DNG("image/x-adobe-dng")
}

data class PhotoSource(val file: File, val format: PhotoFormat)
data class PublishedPhoto(val uri: String, val name: String, val bytes: Long, val format: PhotoFormat)

/** Only rows created for this capture are exposed to this transaction. */
interface ImageStore {
    fun createPending(name: String, mimeType: String, relativePath: String): String
    fun openOutput(uri: String): OutputStream
    fun publish(uri: String)
    fun delete(uri: String)
}

class PhotoPublishException(cause: Exception, val cleanupFailures: Int) : IOException(
    "Shared image save failed: ${cause.javaClass.simpleName}: ${cause.message}; cleanupFailures=$cleanupFailures", cause
)

class PhotoPublisher(private val store: ImageStore) {
    private var outstandingResources = 0
    /** Publication failure and successful stream closure are independent facts. */
    val resourcesClosed get() = outstandingResources == 0

    private fun <T : Closeable, R> useTracked(resource: T, block: (T) -> R): R {
        outstandingResources++
        var failed: Throwable? = null
        try { return block(resource) }
        catch (failure: Throwable) { failed = failure; throw failure }
        finally {
            try { resource.close(); outstandingResources-- }
            catch (closing: Throwable) { if (failed != null) failed.addSuppressed(closing) else throw closing }
        }
    }

    fun publish(sources: List<PhotoSource>): List<PublishedPhoto> {
        check(resourcesClosed) { "Retain unconfirmed stream ownership" }
        require(sources.isNotEmpty()) { "No photo outputs" }
        require(sources.all { it.file.isFile && it.file.length() > 0L }) { "Missing/empty camera output" }
        val created = mutableListOf<PublishedPhoto>()
        try {
            sources.forEach { source ->
                val bytes = source.file.length()
                val uri = store.createPending(source.file.name, source.format.mimeType, RELATIVE_PATH)
                val photo = PublishedPhoto(uri, source.file.name, bytes, source.format)
                created += photo // Register before open/copy so those failures also roll back.
                useTracked(source.file.inputStream()) { input ->
                    useTracked(store.openOutput(uri)) { output ->
                        val copied = input.copyTo(output)
                        if (copied != bytes) throw IOException("Camera output changed during copy")
                    }
                }
            }
            // Both RAW and JPEG streams are complete before either is made visible.
            created.forEach { store.publish(it.uri) }
            return created
        } catch (e: Exception) {
            var failed = 0
            created.asReversed().forEach { photo ->
                try { store.delete(photo.uri) } catch (_: Exception) { failed++ }
            }
            throw PhotoPublishException(e, failed)
        }
    }

    companion object { const val RELATIVE_PATH = "Pictures/Cinematic Camera/" }
}
