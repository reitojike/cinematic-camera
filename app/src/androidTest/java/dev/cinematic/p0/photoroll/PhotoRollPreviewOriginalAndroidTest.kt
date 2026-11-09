package dev.cinematic.p0.photoroll

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.DngContentChanged
import dev.cinematic.p0.rawcapture.DngVerificationStopped
import dev.cinematic.p0.rawcapture.DngIoResources
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Local wrapped provider only, synthetic identity bytes, no real MediaStore row or photo access. */
class PhotoRollPreviewOriginalAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val media = Uri.parse("content://media/external_primary/images/media/1")
    private val document = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ASynthetic%2Finput.dng")

    private fun fixture(block: (SyntheticProvider, PhotoRollRepository) -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val context = instrumentation.targetContext
        SyntheticProvider(context).use { provider ->
            block(provider, PhotoRollRepository(ContentResolver.wrap(provider), context.packageName))
            assertEquals(0, provider.writes)
        }
    }

    @Test fun sameMetadataUsesReadOnlyHintWhileStrongCheckStillRejectsSameSizeChange() = fixture { provider, repository ->
        for ((uri, files) in listOf(media to false, document to true)) {
            provider.file.writeText("abc")
            val original = repository.check(uri, files, { false })
            val fingerprint = original.fingerprint
            provider.file.writeText("abd") // Deliberately same size and provider mtime.
            val io = DngIoResources()
            repository.verifyPreview(original, { false }, io)
            assertFalse(io.hasOpenResources)
            assertEquals(fingerprint, original.fingerprint)
            assertThrows(DngContentChanged::class.java) { repository.verify(original) { false } }
        }
    }

    @Test fun unknownOmittedOrInvalidTimestampKeepsFullContentCheck() = fixture { provider, repository ->
        for (mode in listOf("null", "omitted", "zero", "negative")) {
            provider.file.writeText("abc")
            provider.omitTime = mode == "omitted"
            provider.modified = when (mode) { "zero" -> 0L; "negative" -> -1L; else -> null }
            val original = repository.check(media, false, { false })
            assertNull(original.previewObservation?.modified)
            provider.file.writeText("abd")
            assertThrows(DngContentChanged::class.java) { repository.verifyPreview(original, { false }) }
        }
    }

    @Test fun changedMetadataPermissionAndPublicationFailBeforeDisplay() {
        for (change in listOf("size", "mtime", "missing-time", "missing-row", "pending", "trash", "owner", "permission")) fixture { provider, repository ->
            val original = repository.check(media, false, { false })
            when (change) {
                "size" -> provider.file.writeText("abcd")
                "mtime" -> provider.modified = 101
                "missing-time" -> provider.modified = null
                "missing-row" -> provider.exists = false
                "pending" -> provider.pending = 1
                "trash" -> provider.trash = 1
                "owner" -> provider.owner = "another.synthetic.package"
                "permission" -> provider.readable = false
            }
            assertThrows(Exception::class.java) { repository.verifyPreview(original, { false }) }
        }
    }

    @Test fun cancellationAndChangedOpenObservationCannotEstablishNewBaseline() = fixture { provider, repository ->
        assertThrows(DngVerificationStopped::class.java) { repository.check(media, false, { true }) }
        assertEquals(0, provider.opens)
        provider.changeTimeWhenOpened = true
        assertThrows(DngContentChanged::class.java) { repository.check(media, false, { false }) }
        provider.changeTimeWhenOpened = false
        val original = repository.check(media, false, { false })
        val opens = provider.opens
        assertThrows(DngVerificationStopped::class.java) { repository.verifyPreview(original, { true }) }
        assertEquals(opens, provider.opens)
    }

    private class SyntheticProvider(context: Context) : ContentProvider(), AutoCloseable {
        val file = File.createTempFile("preview-check-synthetic-", ".bin", context.cacheDir).apply { writeText("abc") }
        var modified: Long? = 100
        var omitTime = false
        var exists = true
        var pending = 0
        var trash = 0
        var owner: String = context.packageName
        var readable = true
        var changeTimeWhenOpened = false
        var opens = 0
        var writes = 0
        init { attachInfo(context, ProviderInfo().apply { authority = "media"; exported = false }) }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/x-adobe-dng"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = requireNotNull(projection).filterNot { omitTime &&
                it in listOf(MediaStore.MediaColumns.DATE_MODIFIED, DocumentsContract.Document.COLUMN_LAST_MODIFIED) }.toTypedArray()
            return MatrixCursor(columns).also { cursor ->
                if (exists) cursor.addRow(Array<Any?>(columns.size) { index -> when (columns[index]) {
                    OpenableColumns.DISPLAY_NAME -> "Synthetic.dng"
                    OpenableColumns.SIZE -> file.length()
                    MediaStore.MediaColumns.MIME_TYPE -> "image/x-adobe-dng"
                    MediaStore.MediaColumns.VOLUME_NAME -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME -> owner
                    MediaStore.MediaColumns.IS_PENDING -> pending
                    MediaStore.MediaColumns.IS_TRASHED -> trash
                    MediaStore.MediaColumns.DATE_MODIFIED, DocumentsContract.Document.COLUMN_LAST_MODIFIED -> modified
                    else -> null
                } })
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r") { "Read-only fixture" }
            if (!readable) throw SecurityException("Synthetic revoked read")
            opens++
            if (changeTimeWhenOpened) modified = requireNotNull(modified) + 1
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes++; error("No writes") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int { writes++; error("No writes") }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes++; error("No writes") }
        override fun close() { check(file.delete()) }
    }
}
