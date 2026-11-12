package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Import namespace only. Empty pre-commit directories are not corrupt or completed operations. */
internal class DngImportJournalFiles(private val root: File) {
    fun journal(id: String): DngImportJournal {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        checkRoot()
        return DngImportJournal(id, AndroidAtomicJournalStorage(root, id, CaptureJournalFile.IMPORT))
    }
    fun records(): List<DngImportRecord> {
        checkRoot()
        return (root.listFiles() ?: throw IOException("Import records unavailable")).mapNotNull { directory ->
            if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) throw IOException("Unknown import entry")
            val record = journal(directory.name).read()
            val entries = directory.list() ?: throw IOException("Import entries unavailable")
            if (entries.any { it !in setOf("import.bin", "import.bin.new", "import.bin.bak") }) throw IOException("Unknown import metadata")
            if (record == null && entries.isNotEmpty()) throw IOException("Import commit unavailable")
            record
        }
    }
    private fun checkRoot() {
        if (Files.isSymbolicLink(root.toPath()) || root.canonicalFile != root.absoluteFile) throw IOException("Redirected import root")
        if (!root.exists() && !root.mkdirs()) throw IOException("Import root unavailable")
        if (!root.isDirectory) throw IOException("Import root unavailable")
    }
}
