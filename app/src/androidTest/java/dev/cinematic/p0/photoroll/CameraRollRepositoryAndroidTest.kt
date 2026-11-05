package dev.cinematic.p0.photoroll

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import dev.cinematic.p0.rawcapture.OriginalGrant
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CameraRollRepositoryAndroidTest {
    @Test fun listsAccessiblePublishedMediaAndRechecksBeforeReadOnlyViewerGrant() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val created = mutableListOf<Uri>()
        fun row(name: String, mime: String, video: Boolean = false, pending: Boolean = false,
            folder: String? = null): Uri {
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder ?: if (video) "Movies/Cinematic Camera Synthetic Tests/" else "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })).also { created += it }
            resolver.openOutputStream(uri, "w")!!.use { output ->
                if (mime == "image/jpeg") {
                    val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
                    try { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) } finally { bitmap.recycle() }
                } else output.write(byteArrayOf(0x49, 0x49, 0x2a, 0)) // Candidate metadata only; no claim of valid DNG/video.
            }
            if (!pending) assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            return uri
        }
        try {
            val photo = row("synthetic.jpg", "image/jpeg")
            val dng = row("synthetic.dng", "image/x-adobe-dng")
            val video = row("synthetic.mp4", "video/mp4", video = true)
            val raw = row("synthetic.nef", "image/x-nikon-nef", folder = "Pictures/Cinematic Camera Other Synthetic Folder/")
            val pending = row("pending.jpg", "image/jpeg", pending = true)
            val trash = row("trash.jpg", "image/jpeg")
            assertEquals(1, resolver.update(trash, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }, null, null))
            val repository = PhotoRollRepository(resolver, context.packageName)
            val listed = repository.list(CancellationSignal()).items.associateBy { it.uri }
            assertEquals(CameraRollKind.PHOTO, listed[photo.toString()]?.kind)
            assertEquals(CameraRollKind.DNG, listed[dng.toString()]?.kind)
            assertEquals(CameraRollKind.VIDEO, listed[video.toString()]?.kind)
            assertFalse(listed.containsKey(pending.toString()))
            assertFalse(listed.containsKey(trash.toString()))
            assertEquals(CameraRollKind.RAW, listed[raw.toString()]?.kind)
            val differentOwner = PhotoRollRepository(resolver, "synthetic.other.app")
            assertTrue(differentOwner.list(CancellationSignal()).items.any { it.uri == photo.toString() })
            assertEquals(OriginalGrant.APP_OWNED, repository.check(dng, false, { false }).grant)
            assertEquals(OriginalGrant.USER_SELECTED, differentOwner.check(dng, false, { false }).grant)
            val first = repository.list(CancellationSignal(), PhotoRollQuery(pageSize = 1))
            assertEquals(1, first.items.size)
            assertTrue(first.hasMore)
            val otherFolder = requireNotNull(listed[raw.toString()]?.folderKey)
            assertTrue(first.folders.any { it.key == otherFolder }) // Independent of the visible first page.
            val fromOtherFolder = repository.list(CancellationSignal(), PhotoRollQuery(folderKey = otherFolder))
            assertTrue(fromOtherFolder.items.any { it.uri == raw.toString() })
            val allPages = mutableListOf<PhotoRollItem>()
            var page = first
            do {
                allPages += page.items
                if (!page.hasMore) break
                page = repository.list(CancellationSignal(), PhotoRollQuery(pageSize = 1, after = page.nextCursor, includeFolders = false))
            } while (allPages.size < 1000)
            assertEquals(listed.keys, allPages.map { it.uri }.toSet())
            assertEquals(allPages.size, allPages.map { it.uri }.distinct().size)
            val rawItems = repository.list(CancellationSignal(), PhotoRollQuery(kindFilter = PhotoRollKindFilter.RAW)).items
            assertTrue(rawItems.any { it.uri == dng.toString() }); assertTrue(rawItems.any { it.uri == raw.toString() })
            assertTrue(rawItems.all { it.kind in setOf(CameraRollKind.DNG, CameraRollKind.RAW) })
            assertTrue(repository.list(CancellationSignal(), PhotoRollQuery(kindFilter = PhotoRollKindFilter.IMAGES)).items.all { it.kind == CameraRollKind.PHOTO })
            assertTrue(repository.list(CancellationSignal(), PhotoRollQuery(kindFilter = PhotoRollKindFilter.VIDEOS)).items.all { it.kind == CameraRollKind.VIDEO })
            val intent = repository.viewIntent(listed.getValue(photo.toString()), CancellationSignal())
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("image/jpeg", intent.type)
            assertEquals(photo, intent.data)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
            assertEquals(photo, intent.clipData!!.getItemAt(0).uri)
            assertEquals("video/mp4", repository.viewIntent(listed.getValue(video.toString()), CancellationSignal()).type)
            for (attribute in listOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.IS_TRASHED)) {
                assertEquals(1, resolver.update(photo, ContentValues().apply { put(attribute, 1) }, null, null))
                try { repository.viewIntent(listed.getValue(photo.toString()), CancellationSignal()); fail("Changed publication state must not open") }
                catch (_: PhotoRollSourceFailure) { }
                assertEquals(1, resolver.update(photo, ContentValues().apply { put(attribute, 0) }, null, null))
            }
            assertEquals(photo, differentOwner.viewIntent(listed.getValue(photo.toString()), CancellationSignal()).data)
            try { repository.viewIntent(listed.getValue(dng.toString()), CancellationSignal()); fail("DNG must use verified editor") }
            catch (_: PhotoRollSourceFailure) { }
            assertEquals(1, resolver.delete(photo, null, null))
            created.remove(photo)
            try { repository.viewIntent(listed.getValue(photo.toString()), CancellationSignal()); fail("Deleted row must not open") }
            catch (_: PhotoRollSourceFailure) { }
        } finally { created.forEach { resolver.delete(it, null, null) } }
    }
}
