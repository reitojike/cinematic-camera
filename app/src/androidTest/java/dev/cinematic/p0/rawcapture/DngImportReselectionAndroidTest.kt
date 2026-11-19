package dev.cinematic.p0.rawcapture

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.testing.syntheticCropDng
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Product import and editor handoff contract only, on a disposable emulator. No decode or UI. */
@RunWith(AndroidJUnit4::class)
class DngImportReselectionAndroidTest {
    @Test fun settledSelectionsReuseOnlyVerifiedBytes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("isolated") == "true" && args.getString("dngImportReselection") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        check(Build.HARDWARE in setOf("goldfish", "ranchu") && app.packageName == "dev.cinematic.p0")
        val resolver = app.contentResolver
        val imports = AndroidDngImports.get(app)
        val files = DngImportJournalFiles(File(app.noBackupFilesDir.canonicalFile, "dng-imports"))
        val rows = MediaStoreDngPendingRows(app)
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val sourceStem = "Pictures/Cinematic Camera Synthetic Tests/" + UUID.randomUUID()
        val projectPath = "Pictures/Cinematic Camera/"
        val name = "synthetic-reselection.dng"
        val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true,
            previewMemoryAvailable = true)
        val bytesA = syntheticCropDng()
        val bytesB = bytesA.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        fun write(uri: Uri, bytes: ByteArray) {
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
        }
        fun source(index: Int, bytes: ByteArray): Uri {
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$sourceStem/$index/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            write(uri, bytes)
            assertEquals(1, resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null))
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                assertTrue(it.moveToFirst())
                assertEquals(name, it.getString(0))
            } ?: error("Synthetic source metadata missing")
            return uri
        }
        fun importRowCount(): Int {
            // Test-only count of app-owned imported rows, including pending; not a product scan.
            val query = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "relative_path=? AND owner_package_name=? AND _display_name GLOB ?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(projectPath, app.packageName, "CINEMATIC_IMPORT_*.dng"))
            }
            return requireNotNull(resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), query, null)).use { it.count }
        }
        fun select(uri: Uri): DngImportScreenState {
            check(!imports.state.value.busy && imports.state.value.readyUri == null)
            imports.select(uri, 0) { allowed }
            return runBlocking { withTimeout(30_000) { imports.state.first { !it.busy } } }
        }
        fun accepted(state: DngImportScreenState): String {
            assertEquals(DngImportMessageKind.ACCEPTED, state.messageKind)
            val ready = requireNotNull(state.readyUri)
            assertTrue(imports.consumeReady(ready))
            assertTrue(!imports.consumeReady(ready)) // No stale handoff to a later operation.
            return ready
        }
        fun ownPublished(record: DngImportRecord): Uri {
            assertEquals(DngImportPhase.READY, record.phase)
            val row = rows.find(record.target).single()
            check(row.uri == record.uri && record.target.accepts(row, false)) { "Unexpected imported row ownership" }
            return Uri.parse(row.uri)
        }
        fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

        // Decline to operate if the emulator already has imports from another run.
        check(files.records().isEmpty() && importRowCount() == 0)
        val first = source(1, bytesA)
        val same = source(2, bytesA)
        val different = source(3, bytesB)
        check(setOf(first, same, different).size == 3)

        val sharedA = accepted(select(first))
        val a = files.records().single()
        val ownA = ownPublished(a)
        check(ownA.toString() == sharedA && ownA != first && ownA != same)
        assertArrayEquals(bytesA, read(ownA))
        assertEquals(1, importRowCount())

        // A new selection after the first operation completed must not allocate another journal/row.
        check(accepted(select(same)) == sharedA) { "Identical bytes did not reuse the existing row" }
        check(files.records() == listOf(a)) { "Identical bytes changed the settled journal" }
        assertEquals(1, importRowCount())

        val sharedB = accepted(select(different))
        val b = files.records().single { it.target != a.target }
        val ownB = ownPublished(b)
        check(ownB.toString() == sharedB && sharedA != sharedB && ownB != different)
        check(a.fingerprint != b.fingerprint && files.records().toSet() == setOf(a, b))
        assertEquals(2, importRowCount())
        assertArrayEquals(bytesB, read(ownB))

        // Mutate or remove only the verified test-generated imported row, not any source.
        write(ownPublished(a), bytesB)
        val modified = select(same)
        assertEquals(DngImportMessageKind.FAILURE, modified.messageKind)
        assertNull(modified.readyUri)
        assertTrue(!imports.consumeReady(sharedA))
        assertEquals(2, importRowCount())
        assertArrayEquals(bytesB, read(ownA))
        check(files.records().toSet() == setOf(a, b)) { "Modified row changed the settled journals" }

        assertEquals(1, resolver.delete(ownPublished(a), null, null))
        assertTrue(rows.find(a.target).isEmpty())
        val missing = select(same)
        assertEquals(DngImportMessageKind.FAILURE, missing.messageKind)
        assertNull(missing.readyUri)
        assertTrue(!imports.consumeReady(sharedA))
        assertEquals(1, importRowCount())
        check(files.records().toSet() == setOf(a, b)) { "Missing row caused a replacement journal" }
        assertArrayEquals(bytesB, read(ownB))
        assertArrayEquals(bytesA, read(first))
        assertArrayEquals(bytesA, read(same))
        assertArrayEquals(bytesB, read(different))
        // Retain the synthetic journal/rows for the remainder of this disposable VM only.
    }
}
