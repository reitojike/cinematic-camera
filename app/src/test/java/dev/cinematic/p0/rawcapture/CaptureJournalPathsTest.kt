package dev.cinematic.p0.rawcapture

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptureJournalPathsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fixedJournalPathStaysUnderTrustedRootWithoutCreatingFiles() {
        val root = temporary.newFolder("private-captures")
        val file = CaptureJournalPaths.receiptFile(root, "capture-1")
        assertEquals(File(File(root.canonicalFile, "capture-1"), "receipt.bin"), file)
        assertFalse(file.exists())
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun traversalAbsoluteAndUnexpectedCaptureNamesCannotReachAnotherFile() {
        val root = temporary.newFolder("private-captures")
        for (id in listOf("../x", "/outside", "C:outside", "one/two", "one\\two", ".", "..")) {
            assertThrows(IllegalArgumentException::class.java) { CaptureJournalPaths.receiptFile(root, id) }
        }
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun redirectedCaptureBaseAndAtomicSidecarsAreRejected() {
        val root = temporary.newFolder("private-captures")
        val outside = temporary.newFolder("other-test-data")
        val link = File(root, "redirected").toPath()
        try { Files.createSymbolicLink(link, outside.toPath()) }
        catch (e: IOException) {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) assumeNoException("Windows symlink permission unavailable", e)
            else throw e
        }
        assertThrows(IOException::class.java) { CaptureJournalPaths.receiptFile(root, "redirected") }
        for ((index, name) in listOf("receipt.bin", "receipt.bin.new", "receipt.bin.bak").withIndex()) {
            val directory = File(root, "capture-$index").also { assertTrue(it.mkdir()) }
            Files.createSymbolicLink(File(directory, name).toPath(), File(outside, "foreign-$index").toPath())
            assertThrows(IOException::class.java) { CaptureJournalPaths.receiptFile(root, directory.name) }
        }
    }
}
