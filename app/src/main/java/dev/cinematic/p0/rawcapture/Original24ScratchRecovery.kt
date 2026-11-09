package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Cold-process recovery only, before exposing the serial product queue or starting a diagnostic.
 * Fixed owned paths only; never follows links, deletes journals, or touches shared originals.
 */
internal object Original24ScratchRecovery {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val capture = Regex("TRIAL24_[0-9a-f]{56}")
    private val raster = Regex("raster-[0-9]+\\.f32")

    fun product(privateRoot: File) {
        val root = checkedRoot(privateRoot)
        val captures = children(File(root, "raw-captures"))
        captures.filter { capture.matches(it.name) }.forEach { item ->
            directory(item)
            children(File(item, "renders")).forEach { generation ->
                if (generation.name.toLongOrNull()?.let { it > 0 && it.toString() == generation.name } != true)
                    throw IOException("Unexpected original generation")
                directory(generation)
                clearWorkspace(File(generation, "original-work"))
                clearAbandonedOutput(item.name, generation)
            }
        }
        children(File(root, "manual-original24-previews")).forEach {
            if (!uuid.matches(it.name)) throw IOException("Unexpected original preview workspace")
            clearWorkspace(it, allowRasters = false)
        }
        val preflight = File(root, "manual-original24-preflight")
        children(preflight).onEach {
            if (!it.name.endsWith(".dng") || !uuid.matches(it.name.removeSuffix(".dng")))
                throw IOException("Unexpected original preflight file")
            regular(it)
        }.forEach(::remove)
    }

    fun diagnostics(privateRoot: File) {
        val root = checkedRoot(privateRoot)
        children(root).filter { it.name.startsWith("raw-diagnostic-") }.forEach { session ->
            if (!uuid.matches(session.name.removePrefix("raw-diagnostic-")))
                throw IOException("Unexpected diagnostic workspace")
            directory(session)
            val entries = children(session)
            // Validate names/types before deleting any session data. Numeric reports are retained.
            entries.forEach {
                when {
                    it.name == "report.json" || it.name == "input.dng" -> regular(it)
                    it.name.matches(Regex("attempt-[0-9]+")) -> directory(it)
                    else -> throw IOException("Unexpected diagnostic file")
                }
            }
            entries.filter { it.name.startsWith("attempt-") }.forEach { attempt ->
                children(attempt).forEach {
                    if (it.name != "scratch" && it.name != "result.jpg") throw IOException("Unexpected diagnostic attempt file")
                }
                clearWorkspace(File(attempt, "scratch"))
                val output = File(attempt, "result.jpg")
                if (exists(output)) { regular(output); remove(output) }
                remove(attempt)
            }
            val input = File(session, "input.dng")
            if (exists(input)) remove(input)
        }
    }

    private fun clearAbandonedOutput(id: String, generation: File) {
        val output = File(generation, "output.jpg")
        if (!exists(output)) return
        regular(output)
        // Any publication record, including an interrupted AtomicFile write, protects its JPEG.
        if (listOf("", ".new", ".bak").any { exists(File(generation, "jpeg-publication.bin$it")) }) return
        val intent = File(generation, "manual-request.bin")
        if (listOf(".new", ".bak").any { exists(File(generation, "manual-request.bin$it")) })
            throw IOException("Original generation intent is uncertain")
        regular(intent)
        if (intent.length() > ManualOriginalTrialRequestCodec.MAX_BYTES) throw IOException("Original generation intent too large")
        val request = ManualOriginalTrialRequestCodec.decode(intent.readBytes())
        if (request.captureId != id || request.generation.toString() != generation.name)
            throw IOException("Original generation intent differs")
        // A pre-publication process-death generation is terminal HOLD; its JPEG cannot be resumed.
        remove(output)
    }

    private fun clearWorkspace(work: File, allowRasters: Boolean = true) {
        val files = children(work)
        if (!exists(work)) return
        files.forEach {
            if (it.name !in setOf("source-snapshot.dng", "source-linear.rgb16") &&
                !(allowRasters && raster.matches(it.name))) throw IOException("Unexpected original scratch file")
            regular(it)
        }
        files.forEach(::remove)
        remove(work)
    }
    private fun checkedRoot(root: File): File {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("Private root is redirected")
        val absolute = root.canonicalFile
        directory(absolute)
        return absolute
    }
    /** Only a confirmed missing path is absent. Stat/access failures must hold recovery. */
    internal fun exists(file: File, stat: (File) -> Unit = {
        Files.readAttributes(it.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        Unit
    }): Boolean = try {
        stat(file)
        true
    } catch (_: java.nio.file.NoSuchFileException) { false }
    private fun directory(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile ||
            !Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Private workspace is not a trusted directory")
    }
    private fun regular(file: File) {
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile != file.absoluteFile ||
            !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Private workspace is not a trusted file")
    }
    private fun children(file: File): List<File> {
        if (!exists(file)) return emptyList()
        directory(file)
        return file.listFiles()?.toList() ?: throw IOException("Private workspace inventory unavailable")
    }
    private fun remove(file: File) { if (!file.delete()) throw IOException("Private workspace cleanup unconfirmed") }
}
