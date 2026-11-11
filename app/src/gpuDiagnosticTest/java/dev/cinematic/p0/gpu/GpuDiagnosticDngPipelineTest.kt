package dev.cinematic.p0.gpu

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.photoroll.PhotoRollCameraSnapshot
import dev.cinematic.p0.photoroll.DngInputProbe
import dev.cinematic.p0.photoroll.PhotoRollResources
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.DevelopExecution
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** In-process synthetic document provider, native AS_SHOT loader and real GPU pipeline.
 * ContentResolver.wrap never accesses Android's real Documents provider. No Files UI claim.
 */
@RunWith(AndroidJUnit4::class)
class GpuDiagnosticDngPipelineTest {
    @Test fun syntheticDocumentLoadsNativeAsShotAndRendersWithoutChangingOriginal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "dev.cinematic.p0.gpudiagnostic")
        val provider = SyntheticDocument(target)
        val resolver = ContentResolver.wrap(provider)
        val app = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = resolver
        }
        // Admission observations are fixture inputs. The loader's actual OS memory/thermal checks remain.
        val available = PhotoRollResources(true, 0, false, 2L * 1024 * 1024 * 1024,
            PhotoRollCameraSnapshot(false, false, false, false), true, true)
        DevelopExecution.updateConditions(available.developConditions(true))
        var succeeded = false
        try {
            DngInputProbe.grant(app, provider.uri, "WRAPPED_PROVIDER.direct_call_not_picker_result")
            val input = GpuDiagnosticDngInput.load(app, provider.uri, { available }, CancellationSignal())
                .get(60, TimeUnit.SECONDS)
            assertTrue(input.width in 1..1024 && input.height in 1..1024)
            provider.verifyClosedAndUnchanged()
            GlThread().use { thread -> thread.call {
                EglContexts(thread, target.assets).use { egl ->
                    GlCapabilities.inspect(egl).requireSupported()
                    val ledger = GpuMemoryLedger()
                    DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE).use { engine ->
                        engine.openPhoto(input) { false }.use { photo ->
                            for (params in listOf(GpuRenderParams(0.0, PhotoAdjustments.NONE),
                                GpuRenderParams(1.0, PhotoAdjustments(60.0, 1.0)))) {
                                val expected = KotlinReference.render(input, params)
                                val actual = engine.renderForTest(photo, params, Point.DISPLAY8)
                                ImageDiff.display(expected.display8, ImageDiff.encodeDisplay(actual),
                                    input.width, input.height, "synthetic DNG DISPLAY8 $params").assertDisplayWithin()
                            }
                        }
                        assertEquals(0L, ledger.allocatedBytes)
                        assertEquals(0, ledger.textureCount)
                        assertTrue(ledger.peakAllocatedBytes <= GpuMemoryLedger.PREVIEW_BUDGET)
                    }
                }
            } }
            provider.verifyClosedAndUnchanged()
            succeeded = true
        } finally {
            try {
                GpuDiagnosticDngInput.release(app).get(30, TimeUnit.SECONDS)
                provider.verifyClosedAndUnchanged()
                if (succeeded) assertTrue(provider.source.delete())
            } finally { DevelopExecution.updateConditions(AutoDevelopConditions()) }
        }
    }

    private class SyntheticDocument(context: Context) : ContentProvider() {
        val source = File.createTempFile("gpu-native-input-", ".dng", context.cacheDir).apply {
            outputStream().use { writeSyntheticTrialDng(it, 64, 48, 1) }
        }
        val uri: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
            "primary:DCIM/Synthetic/gpu-input.dng")
        private val fingerprint = DngContentVerifier.identify(source.length(), { source.inputStream() })
        private val descriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()
        private val writes = AtomicInteger()
        init { attachInfo(context, ProviderInfo().apply {
            authority = "com.android.externalstorage.documents"; exported = false
        }) }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/x-adobe-dng"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            check(uri == this.uri)
            val columns = requireNotNull(projection)
            return MatrixCursor(columns).apply { addRow(Array<Any?>(columns.size) { index ->
                when (columns[index]) {
                    OpenableColumns.DISPLAY_NAME -> "Synthetic GPU input.dng"
                    OpenableColumns.SIZE -> source.length()
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> 1L
                    else -> null
                }
            }) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(uri == this.uri && mode == "r")
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also { descriptors += it }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? { writes.incrementAndGet(); error("No writes") }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            writes.incrementAndGet(); error("No writes")
        }
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { writes.incrementAndGet(); error("No writes") }
        fun verifyClosedAndUnchanged() {
            assertTrue("Completed loader retains no provider descriptor", descriptors.none { it.fileDescriptor.valid() })
            assertEquals(0, writes.get())
            DngContentVerifier.verify(fingerprint, { source.inputStream() })
        }
    }
}
