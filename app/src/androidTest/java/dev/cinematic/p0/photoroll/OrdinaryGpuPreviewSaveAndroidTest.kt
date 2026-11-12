package dev.cinematic.p0.photoroll

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Ordinary app, real list/editor/Surface/GPU/FGS/JPEG. Synthetic source only; no engine injection. */
class OrdinaryGpuPreviewSaveAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun ordinaryGpuListPreviewPresentsPixelsThenUiSavePublishesSameSource() {
        check(BuildConfig.BUILD_TYPE == "debug")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val app = instrumentation.targetContext.applicationContext
        check(app.packageName == "dev.cinematic.p0")
        val setting = DevelopEngineSetting(app)
        assertFalse(setting.visible)
        assertEquals(DevelopEngine.GPU, setting.current())
        val resolver = app.contentResolver
        val name = "synthetic_ordinary_gpu_${UUID.randomUUID()}.dng"
        val original = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
        val bytes = requireNotNull(resolver.openOutputStream(original, "w")).use { writeSyntheticTrialDng(it, 256, 192, 1) }
        check(resolver.update(original, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        val fingerprint = DngContentVerifier.identify(bytes, { requireNotNull(resolver.openInputStream(original)) })
        val source = SharedOriginalReference(original.toString(), fingerprint, OriginalGrant.APP_OWNED)
        val values = PhotoEditValues(.65, 20.0, .3)
        AndroidPhotoEditRecipes(app).save(source, values).get(30, TimeUnit.SECONDS)
        cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach { instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it) }
        var camera: PhotoRollCameraOwners.Owner? = null
        var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        var output: Pair<android.net.Uri, String>? = null
        var success = false
        try {
            instrumentation.runOnMainSync { camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) } }
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                click(name)
                // Production save-enable requires presented == requested and displayed == requested.
                await("presented GPU preview and enabled save") { action("JPEGを保存") != null }
                var surface: SurfaceView? = null
                fun locate(view: View) {
                    if (view is SurfaceView && view.isShown && view.width > 0 && view.height > 0) surface = view
                    if (view is ViewGroup) for (i in 0 until view.childCount) locate(view.getChildAt(i))
                }
                scenario.onActivity { locate(it.window.decorView) }
                val view = requireNotNull(surface) { "Real GPU SurfaceView missing" }
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    val copied = CountDownLatch(1)
                    var result = -1
                    PixelCopy.request(view, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                    assertTrue(copied.await(10, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, result)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("Presented surface must contain non-uniform image pixels", pixels.toSet().size > 8)
                } finally { bitmap.recycle() }
                val save = AndroidManualOriginalTrialSaves.prepare(app, source, values.strength, ALLOWED,
                    PhotoAdjustments(20.0, .3)).get(60, TimeUnit.SECONDS).also { handle = it }
                val request = save.request
                assertEquals(source, request.source)
                assertEquals(ManualOriginalTrialSavePhase.READY, save.state.value.phase)
                assertNull(save.state.value.uri)
                click("JPEGを保存")
                await("same owner publication and FGS completion", 180_000) {
                    save.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED && ForegroundExports.gate.active() == null
                }
                assertEquals(request, save.request)
                assertFalse(save.hasUnreleasedResources)
                val directory = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId, request.generation).generationRoot()
                assertFalse(File(directory, "original-work").exists())
                val journal = JpegPublicationJournal(request.captureId, request.generation,
                    AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), CaptureJournalFile.JPEG_PUBLICATION))
                val record = requireNotNull(journal.read())
                assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
                assertEquals(save.state.value.uri, record.uri)
                val proof = record.render as ManualOriginalTrialRender
                assertEquals(source, proof.request.source)
                assertEquals(request.generation, proof.request.generation)
                assertEquals(request.recipe, proof.request.recipe)
                val jpeg = android.net.Uri.parse(requireNotNull(record.uri))
                output = jpeg to record.target.displayName
                val inspection = AndroidJpegInspector().inspect({ requireNotNull(resolver.openInputStream(jpeg)) }, record.fingerprint.bytes) { false }
                assertEquals(record.fingerprint, inspection.fingerprint)
                assertEquals(proof.request.output, inspection.output)
                assertEquals(256, inspection.output.width)
                assertEquals(192, inspection.output.height)
                assertEquals(fingerprint, DngContentVerifier.verify(fingerprint, { requireNotNull(resolver.openInputStream(original)) }))
                await("published UI") { action("保存したJPEGを照合して開く") != null }
            }
            AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS)
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            assertFalse(requireNotNull(handle).hasUnreleasedResources)
            success = true
        } finally {
            instrumentation.runOnMainSync { camera?.close() }
            if (success) {
                for ((uri, expectedName) in listOf(requireNotNull(output), original to name)) {
                    requireNotNull(resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)).use {
                        check(it.count == 1 && it.moveToFirst() && it.getLong(0) == ContentUris.parseId(uri) &&
                            it.getString(1) == expectedName && it.getString(2) == app.packageName && it.getInt(3) == 0)
                    }
                    check(resolver.delete(uri, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0", arrayOf(app.packageName, expectedName)) == 1)
                }
            } // Failure retains this test's synthetic rows/journals; no blind retry.
        }
    }
    private fun action(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        var node = find(instrumentation.uiAutomation.rootInActiveWindow)
        while (node != null && !node.isClickable && node.isEnabled) node = node.parent
        return node?.takeIf { it.refresh() && it.isVisibleToUser && it.isClickable && it.isEnabled }
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await(label) { action(label).also { node = it } != null }
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Ordinary GPU UI did not reach: $label")
    }
    companion object {
        private val ALLOWED = AutoDevelopConditions(true, false, false, false, true, true, true, true, previewMemoryAvailable = true)
    }
}
