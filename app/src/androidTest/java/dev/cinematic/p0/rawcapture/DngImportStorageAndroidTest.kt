package dev.cinematic.p0.rawcapture

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.testing.syntheticCropDng
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DngImportStorageAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun gate() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("dngImportStorage") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu")) { "Synthetic disposable emulator only" }
    }

    @Test fun pendingCopyPublishesExactlyOneSharedOriginalAndCanReopen() = fixture { f ->
        val owner = f.owner(f.rows)
        assertEquals(DngImportResult.READY, owner.advance())
        assertFalse(owner.hasUnreleasedResources)
        val record = requireNotNull(f.files.journal(f.id).read())
        val found = f.rows.find(f.target)
        assertEquals(1, found.size); assertTrue(f.target.accepts(found.single(), false))
        val reopened = DngImportJournalFiles(f.root).journal(f.id)
        val restored = DngImportOwner(reopened, DngImportSource { error("SD is absent") }, f.rows) { true }
            .also { f.owners += it }
        assertEquals(DngImportResult.READY, restored.advance())
        DngContentVerifier.verify(record.fingerprint, { f.rows.openRead(f.target, requireNotNull(record.uri), false) })
        assertArrayEquals(f.source, syntheticCropDng())
    }

    @Test fun publishResponseLossReconcilesSameUriAfterJournalReopen() = fixture { f ->
        var publishes = 0
        val lost = object : DngImportRows by f.rows {
            override fun publish(target: DngImportTarget, uri: String) {
                publishes++; f.rows.publish(target, uri); throw IOException("Synthetic response loss")
            }
        }
        val owner = f.owner(lost)
        assertThrows(IOException::class.java) { owner.advance() }
        assertFalse(owner.hasUnreleasedResources)
        assertEquals(DngImportPhase.PUBLISHING, f.files.journal(f.id).read()!!.phase)
        assertEquals(DngImportResult.READY, f.owner(f.rows).advance())
        assertEquals(1, publishes); assertEquals(1, f.rows.find(f.target).size)
    }

    @Test fun pendingCancelAndEmptyPrecommitDirectoryRecoverWithoutPublishing() = fixture { f ->
        val empty = File(f.root, UUID.randomUUID().toString()).also { check(it.mkdir()) }
        assertEquals(1, f.files.records().size)
        assertTrue(empty.list()!!.isEmpty())
        val journal = f.files.journal(f.id)
        var record = journal.advance(journal.read()!!, DngImportPhase.INSERTING)
        val uri = requireNotNull(f.rows.insert(f.target))
        record = journal.advance(record, DngImportPhase.COPYING, uri)
        assertTrue(f.rows.inspect(uri)!!.pending == true)
        journal.stop(record)
        assertEquals(DngImportResult.CANCELLED, f.owner(f.rows).advance())
        assertNull(f.rows.inspect(uri)); assertEquals(DngImportPhase.CANCELLED, journal.read()!!.phase)
    }

    private class Fixture(val root: File, val id: String, val target: DngImportTarget,
        val source: ByteArray, val files: DngImportJournalFiles, val rows: MediaStoreDngPendingRows) {
        val owners = mutableListOf<DngImportOwner>()
        fun owner(port: DngImportRows) = DngImportOwner(files.journal(id), DngImportSource { source.inputStream() }, port) { true }
            .also { owners += it }
    }
    private fun fixture(test: (Fixture) -> Unit) {
        gate()
        val id = UUID.randomUUID().toString()
        val root = File(context.noBackupFilesDir.canonicalFile, "import-fixture-$id")
        val target = DngImportTarget(id, UUID.randomUUID().toString().replace("-", ""))
        val bytes = syntheticCropDng()
        val fingerprint = DngContentVerifier.identifyBounded(DngImportRecord.MAX_SOURCE_BYTES, { bytes.inputStream() })
        val files = DngImportJournalFiles(root)
        val rows = MediaStoreDngPendingRows(context)
        files.journal(id).prepare(DngImportRecord(target, "content://synthetic/import", fingerprint, DngImportGrant.SESSION_ONLY))
        val fixture = Fixture(root, id, target, bytes, files, rows)
        var failure: Throwable? = null
        try { test(fixture) }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            val primary = failure
            // Only exact, newly created synthetic target rows. Never user photographs or other test fixtures.
            fun attempt(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
            }
            fixture.owners.forEach { operation -> attempt { operation.releaseResources() } }
            attempt {
                check(fixture.owners.none { it.hasUnreleasedResources })
                rows.find(target).forEach { row ->
                    check(row.pending != null && target.accepts(row, requireNotNull(row.pending)))
                    check(context.contentResolver.delete(Uri.parse(row.uri), null, null) == 1)
                }
                check(rows.find(target).isEmpty())
            }
            attempt {
                check(fixture.owners.none { it.hasUnreleasedResources } && rows.find(target).isEmpty())
                check(root.canonicalFile == root.absoluteFile && root.name == "import-fixture-$id")
                check(root.deleteRecursively())
            }
            if (primary == null) failure?.let { throw it }
        }
    }
}
