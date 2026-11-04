package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RenderedJpegFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun writerMustCloseBeforeReadOrSealAndExistingOutputIsNeverOverwritten() {
        val root = temporary.newFolder()
        val files = RenderedJpegFiles(root, "capture", 1)
        val writer = files.createOutput()
        writer.write(byteArrayOf(1, 2, 3))
        assertThrows(IOException::class.java) { files.openRead() }
        assertThrows(IOException::class.java) { files.sealCompleted() }
        assertThrows(IOException::class.java) { files.createOutput() }
        writer.close()
        files.sealCompleted()
        assertArrayEquals(byteArrayOf(1, 2, 3), files.openRead().use { it.readBytes() })
        assertThrows(IOException::class.java) { files.createOutput() }
        val recreated = RenderedJpegFiles(root, "capture", 1)
        assertThrows(IOException::class.java) { recreated.createOutput() }
        assertArrayEquals(byteArrayOf(1, 2, 3), recreated.openRead().use { it.readBytes() })
        assertFalse(java.io.File(root, "capture/original.dng").exists())
    }

    @Test fun separateGenerationsKeepTheirFilesAndInvalidIdentitiesAreRejected() {
        val root = temporary.newFolder()
        for (generation in 1L..2) RenderedJpegFiles(root, "capture", generation).createOutput().use { it.write(generation.toInt()) }
        assertEquals(1, RenderedJpegFiles(root, "capture", 1).openRead().use { it.read() })
        assertEquals(2, RenderedJpegFiles(root, "capture", 2).openRead().use { it.read() })
        assertThrows(IllegalArgumentException::class.java) { RenderedJpegFiles(root, "../other", 1) }
        assertThrows(IllegalArgumentException::class.java) { RenderedJpegFiles(root, "capture", 0) }
    }

    @Test fun redirectedGenerationAndOutputDoNotReachAnotherFile() {
        val root = temporary.newFolder()
        val files = RenderedJpegFiles(root, "capture", 1)
        files.generationRoot().mkdirs()
        val other = temporary.newFile().also { it.writeText("keep") }
        val output = java.io.File(files.generationRoot(), "output.jpg")
        try { Files.createSymbolicLink(output.toPath(), other.toPath()) } catch (e: Exception) { assumeNoException(e) }
        assertThrows(IOException::class.java) { files.openRead() }
        assertThrows(IOException::class.java) { files.createOutput() }
        assertEquals("keep", other.readText())
    }
}
