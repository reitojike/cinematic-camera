package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class VerificationReportTest {
    @Test fun tailPreservesUtf8AndDropsPartialFirstLineInsideByteBound() {
        val file = File.createTempFile("verification-tail", ".txt")
        try {
            file.writeText("older\n日本語の途中\n最新結果\n", Charsets.UTF_8)
            val tail = VerificationReport.readTail(file, 20)
            assertTrue(tail.truncated)
            assertEquals("最新結果\n", tail.text)
            assertFalse(tail.text.contains('\uFFFD'))
            assertTrue(tail.text.toByteArray(Charsets.UTF_8).size <= 20)
            assertEquals(file.length(), tail.sourceBytes)
            file.writeText("", Charsets.UTF_8)
            assertEquals(LogTail("", 0, false), VerificationReport.readTail(file))
            file.writeText("single line too long")
            assertEquals("", VerificationReport.readTail(file, 5).text)
        } finally { file.delete() }
    }

    @Test fun successfulWritePreservesUtf8AndClosesBeforeSuccess() {
        var closed = false
        val stream = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        val text = "検証\nISO=100\n"
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), VerificationReport.write({ stream }, text))
        assertEquals(text, stream.toString("UTF-8"))
        assertTrue(closed)
    }

    @Test fun providerOpenWriteAndCloseFailuresNeverReportSuccess() {
        assertThrows(IllegalStateException::class.java) { VerificationReport.write({ null }, "report") }
        assertThrows(IOException::class.java) { VerificationReport.write({ throw IOException("open") }, "report") }
        val writeFailure = object : ByteArrayOutputStream() { override fun write(b: ByteArray) { throw IOException("write") } }
        assertThrows(IOException::class.java) { VerificationReport.write({ writeFailure }, "report") }
        val flushFailure = object : ByteArrayOutputStream() { override fun flush() { throw IOException("flush") } }
        assertThrows(IOException::class.java) { VerificationReport.write({ flushFailure }, "report") }
        val closeFailure = object : ByteArrayOutputStream() { override fun close() { throw IOException("close") } }
        assertThrows(IOException::class.java) { VerificationReport.write({ closeFailure }, "report") }
    }
}
