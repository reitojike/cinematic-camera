package dev.cinematic.p0.photoroll

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.testing.syntheticTrialDng
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fresh emulator only. Grants remain until the external test owner revokes them after instrumentation;
 * revoking this process's permissions from inside instrumentation could kill it before results flush. */
class CameraRollPermissionsAndroidTest {
    @Test fun deniedThenGrantedLibraryAccessUsesRealForeignUidAndReadOnlyDngSelection() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu"))
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        check(permissions.all { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            "Run on a fresh isolated app with image/video grants revoked before instrumentation starts"
        }
        val folder = "Pictures/CinematicSynthetic_${UUID.randomUUID().toString().replace("-", "")}/"
        val videoFolder = folder.replace("Pictures/", "Movies/")
        val foreign = mutableListOf<Uri>()
        fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        fun foreignRow(name: String, mime: String, bytes: ByteArray, video: Boolean = false): Uri {
            val collection = "content://media/external_primary/${if (video) "video" else "images"}/media"
            val inserted = shell("content insert --uri $collection --bind _display_name:s:$name --bind mime_type:s:$mime " +
                "--bind relative_path:s:${if (video) videoFolder else folder} --bind is_pending:i:1")
            val value = Regex("content://media/external_primary/${if (video) "video" else "images"}/media/[0-9]+").find(inserted)?.value
                ?: error("Synthetic shell insert failed")
            val uri = Uri.parse(value).also { foreign += it }
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            check(!shell("echo '$encoded' | base64 -d | content write --uri $value").contains("Error"))
            check(shell("content update --uri $value --bind is_pending:i:0").contains("Updated 1"))
            return uri
        }
        val owned = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "synthetic_owned.dng")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            resolver.openOutputStream(owned, "w")!!.use { it.write(syntheticTrialDng()) }
            assertEquals(1, resolver.update(owned, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null))
            val photoBytes = ByteArrayOutputStream().also { output ->
                val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
                try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) } finally { bitmap.recycle() }
            }.toByteArray()
            val photo = foreignRow("synthetic_foreign.jpg", "image/jpeg", photoBytes)
            val dngBytes = syntheticTrialDng()
            val dng = foreignRow("synthetic_foreign.dng", "image/x-adobe-dng", dngBytes)
            val video = foreignRow("synthetic_foreign.mp4", "video/mp4", byteArrayOf(0, 0, 0, 16), true)
            val repository = PhotoRollRepository(resolver, context.packageName)
            val denied = repository.list(CancellationSignal())
            assertTrue(denied.items.any { it.uri == owned.toString() })
            assertFalse(denied.items.any { row -> foreign.any { row.uri == it.toString() } })
            assertEquals(OriginalGrant.APP_OWNED, repository.check(owned, false, { false }).grant)
            permissions.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
            val folderKey = cameraRollFolder(MediaStore.VOLUME_EXTERNAL_PRIMARY, folder).key
            val granted = repository.list(CancellationSignal(), PhotoRollQuery(folderKey = folderKey)).items.associateBy { it.uri }
            assertTrue(granted.containsKey(photo.toString())); assertTrue(granted.containsKey(dng.toString()))
            assertEquals(photo, repository.viewIntent(granted.getValue(photo.toString()), CancellationSignal()).data)
            val videos = repository.list(CancellationSignal(), PhotoRollQuery(kindFilter = PhotoRollKindFilter.VIDEOS)).items
            assertEquals(video, repository.viewIntent(videos.first { it.uri == video.toString() }, CancellationSignal()).data)
            val selected = repository.check(dng, false, { false })
            assertEquals(OriginalGrant.USER_SELECTED, selected.grant)
            assertEquals(DngContentVerifier.identify(dngBytes.size.toLong(), { dngBytes.inputStream() }), selected.fingerprint)
            resolver.query(dng, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertNotEquals(context.packageName, it.getString(0))
            }
            try {
                repository.check(Uri.parse("content://media/FFFF-FFFF/images/media/1"), false, { false })
                fail("Secondary-volume DNG direct editing remains unsupported")
            } catch (failure: PhotoRollSourceFailure) { assertTrue(failure.userMessage.contains("外部SD")) }
        } finally {
            foreign.forEach { shell("content delete --uri $it") }
            assertEquals(1, resolver.delete(owned, null, null))
        }
    }
}
