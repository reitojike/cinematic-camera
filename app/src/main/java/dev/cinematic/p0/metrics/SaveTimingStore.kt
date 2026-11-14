package dev.cinematic.p0.metrics

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small bounded ring, serialized across collector/store instances; no per-band writes. */
internal class SaveTimingStore(private val directory: File) {
    fun append(record: SaveTimingRecord) = timingBestEffort {
        synchronized(lock) {
            if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
            val lines = readLines().toMutableList()
            lines += record.line()
            var bytes = lines.sumOf { it.length } // schema is ASCII only
            while (lines.size > MAX_RECORDS || bytes > MAX_BYTES) bytes -= lines.removeAt(0).length
            val temporary = File(directory, "records.tmp")
            temporary.writeText(lines.joinToString(""), Charsets.US_ASCII)
            Files.move(temporary.toPath(), File(directory, "records.tsv").toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    /** Snapshot filters corrupt/partial lines, never follows a user-supplied file path. */
    fun snapshot(): ByteArray = synchronized(lock) { readLines().joinToString("").toByteArray(Charsets.US_ASCII) }
    private fun readLines(): List<String> {
        val file = File(directory, "records.tsv")
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_BYTES) return emptyList()
        val text = file.readText(Charsets.US_ASCII)
        return text.split('\n').dropLast(1).takeLast(MAX_RECORDS)
            .filter(SaveTimingRecord::valid).map { "$it\n" }
    }
    companion object {
        const val MAX_RECORDS = 1024
        const val MAX_BYTES = 256 * 1024L
        private val lock = Any()
    }
}
