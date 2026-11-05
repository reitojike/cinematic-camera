package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Read the complete inventory before installing any job. Never infer requests from JPEGs or DNGs. */
internal class AutoDevelopInventory(
    root: File,
    private val read: (captureId: String, generation: Long) -> AutoDevelopRecord?,
    private val children: (File) -> Array<File>? = { it.listFiles() },
) {
    // The app supplies this trusted root; Android may expose it through a system data-directory alias.
    // As with CaptureJournalPaths, normalize the root and reject redirection only below it.
    private val root = root.canonicalFile
    fun load(): List<AutoDevelopRequest> {
        if (Files.notExists(root.toPath(), NOFOLLOW_LINKS)) return emptyList()
        directory(root)
        val requests = mutableListOf<AutoDevelopRequest>()
        entries(root).forEach { capture ->
            directory(capture)
            try { requireCaptureId(capture.name) } catch (e: IllegalArgumentException) {
                throw IOException("Invalid capture inventory entry", e)
            }
            val renders = File(capture, "renders")
            if (Files.notExists(renders.toPath(), NOFOLLOW_LINKS)) return@forEach
            directory(renders)
            entries(renders).forEach generationLoop@ { generation ->
                directory(generation)
                val number = generation.name.toLongOrNull()?.takeIf { it > 0 && it.toString() == generation.name }
                    ?: throw IOException("Invalid generation inventory entry")
                val journal = File(generation, "auto-develop.bin")
                // AtomicFile may have only a backup/new file following an interrupted write.
                val files = listOf(journal, File(journal.path + ".bak"), File(journal.path + ".new"))
                files.forEach { file ->
                    if (!Files.notExists(file.toPath(), NOFOLLOW_LINKS) &&
                        (file.canonicalFile != file.absoluteFile || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))) {
                        throw IOException("Redirected or invalid auto-develop journal")
                    }
                }
                if (files.all { Files.notExists(it.toPath(), NOFOLLOW_LINKS) }) return@generationLoop
                val request = read(capture.name, number)?.request ?: throw IOException("Unresolved auto-develop journal")
                val readableLegacy = request.quality == AutoRenderQuality.FINAL_ORIGINAL
                if (request.captureId != capture.name || request.generation != number || (!request.admissible && !readableLegacy)) {
                    throw IOException("Foreign or unsupported auto-develop inventory")
                }
                requests.add(request)
            }
        }
        return requests
    }

    private fun directory(file: File) {
        if (file.canonicalFile != file.absoluteFile || !Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
            throw IOException("Redirected or invalid inventory directory")
        }
    }
    private fun entries(file: File): Array<File> = children(file) ?: throw IOException("Incomplete auto-develop inventory")
}
