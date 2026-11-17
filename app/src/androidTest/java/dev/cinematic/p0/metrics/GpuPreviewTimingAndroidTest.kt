package dev.cinematic.p0.metrics

import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GpuPreviewTimingAndroidTest {
    @Test fun explicitAndroidCancellationKeepsCancelledOutcome() {
        var rows = emptyList<GpuPreviewTimingRecord>()
        val timing = requireNotNull(GpuPreviewTiming.start(22, { rows = it }))
        timing.failed(android.os.OperationCanceledException())
        assertEquals(GpuPreviewTimingOutcome.CANCELLED, rows.single { it.stage == GpuPreviewTimingStage.OPEN }.outcome)
    }
    @Test fun shellOnlyReadSnapshotIsSeparateFromSaveRecords() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext
        val record = GpuPreviewTimingRecord(UUID.randomUUID().toString().replace("-", ""), 22,
            GpuPreviewTimingStage.OPEN, GpuPreviewTimingOutcome.PRESENTED, 3072, 123)
        gpuPreviewTimingStore(app).append(record)
        val path = "content://${app.packageName}.gpu-preview-timing/records"
        fun shell(uri: String) = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("content read --uri $uri")
        ).bufferedReader().use { it.readText() }
        assertTrue(shell(path).contains(record.line()))
        assertFalse(shell("content://${app.packageName}.save-timing/records").contains(record.attempt))
        assertFalse(shell(path.replace("/records", "/other")).contains(record.attempt))
        assertThrows(SecurityException::class.java) { app.contentResolver.openInputStream(Uri.parse(path))?.close() }
        assertThrows(SecurityException::class.java) { app.contentResolver.openOutputStream(Uri.parse(path))?.close() }
    }
}
