package dev.cinematic.p0.jpegrender.original

import android.app.ActivityManager
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit synthetic-only measurement. Does not lower admission or claim a device memory budget. */
@RunWith(AndroidJUnit4::class)
class PreviewMemoryProbeTest {
    @Test fun measureColdAndRetainedPreview() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("isolated") == "true" && args.getString("previewMemoryProbe") == "true")
        val megapixels = requireNotNull(args.getString("previewMemoryMegapixels")).toInt()
        require(megapixels == 12 || megapixels == 24)
        val width = if (megapixels == 12) 3000 else 6000
        val height = 4000
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "preview-memory-${System.nanoTime()}")
        assertTrue(root.mkdir())
        val input = File(root, "synthetic.dng")
        input.outputStream().use { writeSyntheticTrialDng(it, width, height, 1) }
        val source = SharedOriginalReference("content://example.test/preview-memory/synthetic",
            DngContentVerifier.identify(input.length(), { input.inputStream() }), OriginalGrant.USER_SELECTED)
        val preflightBefore = memorySnapshot(context)
        val preflightMetrics = OriginalProbeMetrics(context)
        var preflightFailure: Throwable? = null
        val geometry = try { ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd, CancellationSignal())
        } } catch (error: Throwable) {
            preflightFailure = error; throw error
        } finally {
            preflightMetrics.stop()
            preflightFailure?.let { error ->
                printReport(preflightMetrics.appendTo(JSONObject().put("scope", "SYNTHETIC_PREFLIGHT")
                    .put("width", width).put("height", height).put("before", preflightBefore)
                    .put("outcome", "FAILED").put("failureType", error.javaClass.simpleName)))
            }
        }
        val activity = context.getSystemService(ActivityManager::class.java)
        val system = ActivityManager.MemoryInfo().also(activity::getMemoryInfo)
        val report = JSONObject().put("scope", "SYNTHETIC_SESSION_NOT_UI_NOT_DEVICE_BUDGET")
            .put("width", width).put("height", height).put("heapLimitBytes", Runtime.getRuntime().maxMemory())
            .put("memoryClassMiB", activity.memoryClass).put("systemThresholdBytes", system.threshold)
            .put("systemTotalBytes", system.totalMem).put("baseline", memorySnapshot(context))
            .put("preflight", preflightMetrics.appendTo(JSONObject().put("before", preflightBefore)))
        val phases = JSONArray()
        var metrics: OriginalProbeMetrics? = null
        var gate = 0
        val session = Original24PreviewSession(source, File(root, "session"), {
            metrics?.stage("admission-${++gate}-before")
            Original24Resources.requireAvailable(context)
        }, geometry) { input.inputStream() }
        var after: Bitmap? = null
        var before: Bitmap? = null
        var succeeded = false
        fun measure(name: String, action: () -> Unit) {
            gate = 0
            val phase = JSONObject().put("name", name).put("before", memorySnapshot(context))
            val current = OriginalProbeMetrics(context)
            metrics = current
            try { action(); current.stage("result-held") }
            finally { metrics = null; current.stop(); phases.put(current.appendTo(phase)) }
        }
        fun replaceExact(strength: Double, adjustments: PhotoAdjustments = PhotoAdjustments.NONE) {
            var nextAfter: Bitmap? = null; var nextBefore: Bitmap? = null
            try {
                nextAfter = session.render(strength, adjustments)
                nextBefore = requireNotNull(session.comparisonBitmap())
                after?.recycle(); before?.recycle(); after = nextAfter; before = nextBefore
                nextAfter = null; nextBefore = null
            } finally { nextAfter?.recycle(); nextBefore?.recycle() }
        }
        try {
            measure("cold-exact-and-comparison") {
                after = session.render(.5)
                before = requireNotNull(session.comparisonBitmap())
                assertEquals(1024, maxOf(requireNotNull(after).width, requireNotNull(after).height))
            }
            measure("warm-strength-old-pair-retained") {
                replaceExact(.7)
            }
            measure("warm-warmth-and-ev-old-pair-retained") {
                replaceExact(.7, PhotoAdjustments(30.0, .5))
            }
            measure("draft-old-exact-pair-retained") {
                val draft = requireNotNull(session.renderDraft(.8, PhotoAdjustments(35.0, .6)))
                try {
                    val comparison = requireNotNull(session.comparisonBitmap(maxEdge = 256))
                    try { assertEquals(256, maxOf(draft.width, draft.height)) }
                    finally { comparison.recycle() }
                } finally { draft.recycle() }
            }
            measure("exact-regeneration-with-prior-draft-fields") { replaceExact(.6, PhotoAdjustments(-20.0, -.4)) }
            report.put("previewWidth", requireNotNull(after).width).put("previewHeight", requireNotNull(after).height)
            report.put("heldExactFieldBytes", session.previewFieldBytes).put("heldDraftFieldBytes", session.draftFieldBytes)
            measure("release") { after?.recycle(); before?.recycle(); after = null; before = null; session.close() }
            assertFalse(session.hasUnreleasedResources)
            assertEquals(0, session.previewFieldBytes); assertEquals(0, session.draftFieldBytes)
            DngContentVerifier.verify(source.fingerprint, { input.inputStream() })
            report.put("sourceUnchanged", true).put("ownersReleased", true).put("phases", phases)
            report.put("outcome", "SUCCESS")
            succeeded = true
        } catch (error: Throwable) {
            report.put("outcome", "FAILED").put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            try {
                after?.recycle(); before?.recycle(); session.close()
                if (succeeded) { assertTrue(input.delete()); assertTrue(root.delete()) }
            } catch (cleanup: Throwable) {
                report.put("outcome", "CLEANUP_FAILED").put("cleanupFailureType", cleanup.javaClass.simpleName)
                throw cleanup
            } finally {
                report.put("phases", phases)
                printReport(report)
            }
        }
    }
    private fun printReport(report: JSONObject) {
        val parts = report.toString().chunked(3000)
        parts.forEachIndexed { index, part -> println("PREVIEW_MEMORY_PART=$index/${parts.size}:$part") }
    }
}
