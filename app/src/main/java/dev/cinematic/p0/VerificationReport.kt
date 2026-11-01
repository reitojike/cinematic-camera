package dev.cinematic.p0

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.time.Instant

data class LogTail(val text: String, val sourceBytes: Long, val truncated: Boolean)

object VerificationReport {
    const val MAX_LOG_BYTES = 128 * 1024

    fun readTail(file: File, maximumBytes: Int = MAX_LOG_BYTES): LogTail {
        require(maximumBytes in 1..MAX_LOG_BYTES) { "Invalid log byte limit" }
        return RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val offset = (length - maximumBytes).coerceAtLeast(0)
            input.seek(offset)
            val bytes = ByteArray((length - offset).toInt())
            input.readFully(bytes)
            // Drop the partial first line, including any split UTF-8 code point.
            val start = if (offset == 0L) 0 else bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it + 1 }
            LogTail(String(bytes, start, bytes.size - start, Charsets.UTF_8), length, offset > 0)
        }
    }

    fun render(snapshotAt: Instant, device: String, snapshot: String, tail: LogTail) = buildString {
        appendLine("Cinematic Camera verification report")
        appendLine("Snapshot captured: $snapshotAt; log tail read afterward")
        appendLine("UNVERIFIED: API acceptance and metadata are not proof of device image/audio quality or AC completion.")
        appendLine("No photo/video/audio bytes are included. Review this text before sharing; nothing is uploaded automatically.")
        appendLine(device)
        appendLine()
        appendLine("--- State / requested controls / observed CaptureResult / capabilities ---")
        appendLine(snapshot)
        appendLine()
        appendLine("--- Current session log tail: originalBytes=${tail.sourceBytes}, limitBytes=$MAX_LOG_BYTES, truncated=${tail.truncated} ---")
        if (tail.truncated) appendLine("[Earlier bytes and any partial first line omitted; this is not a complete session log.]")
        if (tail.text.isEmpty() && tail.truncated) appendLine("[No complete log line fitted inside the byte limit.]")
        append(tail.text)
    }

    /** Success is returned only after write, flush AND close; caller owns any partial document on failure. */
    fun write(open: () -> OutputStream?, text: String): Long {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val output = checkNotNull(open()) { "Document provider returned no output stream" }
        output.use { it.write(bytes); it.flush() }
        return bytes.size.toLong()
    }
}
