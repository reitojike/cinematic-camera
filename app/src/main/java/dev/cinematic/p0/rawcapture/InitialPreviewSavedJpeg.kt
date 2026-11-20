package dev.cinematic.p0.rawcapture

import android.content.Context
import android.os.Looper
import dev.cinematic.p0.jpegrender.original.Original24PreflightGeometry
import dev.cinematic.p0.jpegrender.original.Original24Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

/** Read-only initial guide lookup, not a save/recovery authority. Caller has already verified the
 * original snapshot and derived its geometry, and fences the returned guide by selection/recipe.
 * Only app-produced PUBLISHED v3 output is considered. SHA proves identity, not JPEG completeness. */
internal object InitialPreviewSavedJpeg {
    data class Candidate(val encoded: EncodedJpegSnapshot, val width: Int, val height: Int,
        val request: Original24Request, val publication: JpegPublicationRecord)

    /** No AtomicFile.openRead: its recovery can rename/delete a writer's sidecars. */
    internal interface Reader {
        fun cursor(): ByteArray?
        fun intent(generation: Long): ByteArray?
        fun publication(generation: Long): ByteArray?
        fun inspect(uri: String): DngPendingRow?
        fun open(record: JpegPublicationRecord): InputStream
    }

    fun read(context: Context, source: SharedOriginalReference, geometry: Original24PreflightGeometry,
        recipe: RenderRecipeSnapshot, shouldStop: () -> Boolean): Candidate? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Saved guide lookup requires IO" }
        val app = context.applicationContext
        val id = ManualOriginalTrialRequests.identity(source)
        val rows by lazy(LazyThreadSafetyMode.NONE) { MediaStoreJpegRows(app) }
        val root = app.noBackupFilesDir
        val reader = object : Reader {
            override fun cursor() = readBase(root, "manual-original24-requests/$id/manual-request.bin",
                ManualOriginalTrialRequestCodec.MAX_BYTES)
            override fun intent(generation: Long) = readBase(root,
                "raw-captures/$id/renders/$generation/manual-request.bin", ManualOriginalTrialRequestCodec.MAX_BYTES)
            override fun publication(generation: Long) = readBase(root,
                "raw-captures/$id/renders/$generation/jpeg-publication.bin", JpegPublicationCodec.MAX_BYTES)
            override fun inspect(uri: String) = rows.inspect(uri)
            override fun open(record: JpegPublicationRecord) = rows.openRead(record.target, requireNotNull(record.uri), false)
        }
        return read(source, geometry, recipe, reader, shouldStop) { snapshot ->
            AndroidJpegInspector().inspectSnapshot(snapshot, shouldStop)
        }
    }

    internal fun read(source: SharedOriginalReference, geometry: Original24PreflightGeometry,
        recipe: RenderRecipeSnapshot, reader: Reader, shouldStop: () -> Boolean,
        inspect: (EncodedJpegSnapshot) -> JpegInspection): Candidate? {
        fun checkpoint() { if (shouldStop()) throw CancellationException("Saved guide stopped") }
        checkpoint()
        return try {
            val cursor = reader.cursor() ?: return null
            val request = ManualOriginalTrialRequestCodec.decode(cursor)
            if (request.source != source || request.captureId != ManualOriginalTrialRequests.identity(source) ||
                request.recipe != recipe) return null
            val intent = reader.intent(request.generation) ?: return null
            if (!intent.contentEquals(cursor)) return null
            val journal = reader.publication(request.generation) ?: return null
            // Publication v3 is the only codec carrying the admitted manual-original contract.
            if (journal.size < 8 || ByteBuffer.wrap(journal).getInt(4) != 3) return null
            val record = JpegPublicationCodec.decode(journal)
            if (!matches(request, geometry, record)) return null
            val uri = requireNotNull(record.uri)
            val row = reader.inspect(uri) ?: return null
            if (row.uri != uri || !record.target.accepts(row, pending = false)) return null
            checkpoint()
            val snapshot = EncodedJpegSnapshot.read({ reader.open(record) }, record.fingerprint.bytes, shouldStop)
            if (snapshot.fingerprint != record.fingerprint) return null
            val observed = inspect(snapshot)
            if (observed.fingerprint != record.fingerprint || observed.output != record.render.request.output) return null
            checkpoint()
            // Re-observe the same identities, never mix an older publication with a newer cursor.
            if (reader.inspect(uri) != row || reader.publication(request.generation)?.contentEquals(journal) != true ||
                reader.intent(request.generation)?.contentEquals(intent) != true || reader.cursor()?.contentEquals(cursor) != true)
                return null
            checkpoint()
            Candidate(snapshot, observed.output.width, observed.output.height, request, record)
        } catch (_: IOException) { checkpoint(); null }
          catch (_: SecurityException) { checkpoint(); null }
          catch (_: IllegalArgumentException) { checkpoint(); null }
    }

    internal fun matches(request: Original24Request, geometry: Original24PreflightGeometry,
        record: JpegPublicationRecord): Boolean {
        val render = record.render as? ManualOriginalTrialRender ?: return false
        val saved = render.request
        // Known integer-DNG contract derives crop origin from this same source's bytes. A missing
        // explicit origin is not invented: the caller's preflight must establish that contract.
        return record.phase == JpegPublicationPhase.PUBLISHED && record.uri != null &&
            record.target.captureId == request.captureId && record.target.generation == request.generation &&
            saved.captureId == request.captureId && saved.generation == request.generation &&
            saved.source == request.source && saved.recipe == request.recipe &&
            render.rendererContract == ManualOriginalTrialRender.CONTRACT.renderer &&
            render.appliedStrength == request.strength && render.adjustments == request.adjustments &&
            render.geometry == OriginalRasterGeometry(geometry.sourceWidth, geometry.sourceHeight,
                geometry.orientation, geometry.outputWidth, geometry.outputHeight, true) &&
            saved.output.width == geometry.outputWidth && saved.output.height == geometry.outputHeight &&
            saved.output.orientation == 1 && saved.output.color == "sRGB" &&
            record.fingerprint == render.outputFingerprint
    }

    /** Bounded, non-recovering base read. Sidecars, replacement, redirection and cleanup are misses.
     * This observes immutable bytes; it does not claim a lock against a later legitimate save. */
    internal fun readBase(root: File, relative: String, limit: Int): ByteArray? {
        // Typical first open: no cursor. A no-follow stat is enough to miss without
        // canonicalizing, opening a record, or touching the saved-JPEG provider.
        val file = File(root.absoluteFile, relative)
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val canonical = root.canonicalFile
        fun pathsSafe(): Boolean {
            if (root.absoluteFile != canonical || !Files.isDirectory(canonical.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
            var current = file
            while (current != canonical) {
                if (current.parentFile == null || !current.toPath().startsWith(canonical.toPath()) ||
                    Files.isSymbolicLink(current.toPath()) || current.canonicalFile != current) return false
                current = requireNotNull(current.parentFile)
            }
            return listOf(File(file.path + ".new"), File(file.path + ".bak")).all {
                Files.notExists(it.toPath(), LinkOption.NOFOLLOW_LINKS)
            }
        }
        if (!pathsSafe() || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (before.size() !in 1..limit.toLong()) return null
        val bytes = ByteArray(before.size().toInt())
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val n = input.read(bytes, offset, bytes.size - offset)
                if (n <= 0) return null
                offset += n
            }
            if (input.read() != -1) return null
        }
        if (!pathsSafe()) return null
        val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!after.isRegularFile || before.size() != after.size() || before.fileKey() != after.fileKey() ||
            before.lastModifiedTime() != after.lastModifiedTime()) return null
        return bytes
    }
}
