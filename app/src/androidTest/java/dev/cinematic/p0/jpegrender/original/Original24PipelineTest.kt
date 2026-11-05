package dev.cinematic.p0.jpegrender.original

import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in private fixture only. Never runs on the camera app or accesses shared media. */
@RunWith(AndroidJUnit4::class)
class Original24PipelineTest {
    @Test fun dngToR7LookOriginalJpegAndSameFileReadback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("original24") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish") || args.getString("allowPhysical") == "true")
        val runId = requireNotNull(args.getString("runId")).also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val size = args.getString("size") ?: "SMALL"
        val dimensions = when (size) { "SMALL" -> 64 to 48; "12MP" -> 4000 to 3000; "24MP" -> 6000 to 4000; else -> error("Unknown fixed case") }
        val reportFile = File(context.filesDir, "original24-$runId.json")
        check(reportFile.createNewFile())
        val reports = JSONArray()
        val all = JSONObject().put("runId", runId).put("scope", "synthetic-original-r7-stage-clear-jpeg-private-no-publication")
            .put("applicationId", context.packageName).put("size", size).put("complete", false).put("status", "STARTED")
            .put("heapLimitBytes", Runtime.getRuntime().maxMemory()).put("abi", Build.SUPPORTED_ABIS[0]).put("cases", reports)
        reportFile.writeText(all.toString(2))
        val root = File(context.noBackupFilesDir, "original24-$runId")
        check(!root.exists() && root.mkdir())
        try {
            val orientations = if (size == "SMALL") listOf(1, 6) else listOf(1)
            for (orientation in orientations) {
                val input = File(root, "synthetic-$orientation.dng")
                val bytes = input.outputStream().use { writeSyntheticTrialDng(it, dimensions.first, dimensions.second, orientation) }
                val fingerprint = DngContentVerifier.identify(bytes, { input.inputStream() })
                val source = SharedOriginalReference("content://example.test/original24/$orientation", fingerprint, OriginalGrant.USER_SELECTED)
                val strengths = if (size == "SMALL") listOf(0.0, .5) else listOf(.5)
                val hashes = mutableListOf<String>()
                for ((index, strength) in strengths.withIndex()) {
                    val output = File(root, "result-$orientation-$index.jpg")
                    val io = object : AutoRenderIo {
                        override fun openOriginal() = input.inputStream()
                        override fun openOriginalDescriptor(): AutoReadOnlyDescriptor = error("Native input must use verified immutable attempt snapshot")
                        override fun openOutput() = Files.newOutputStream(output.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                        override fun openOutputRead() = output.inputStream()
                    }
                    val request = Original24Request("synthetic_original", index + 1L, source, strength)
                    val attempt = Original24Attempt(context, request, io, File(root, "scratch-$orientation-$index"))
                    val metrics = OriginalProbeMetrics(context)
                    val report = JSONObject().put("orientation", orientation).put("strength", strength).put("status", "STARTED")
                    reports.put(report)
                    var failure: Throwable? = null
                    try {
                        metrics.stage("before")
                        val result = attempt.render { false }
                        metrics.stage("returned")
                        val expectedWidth = if (orientation >= 5) dimensions.second else dimensions.first
                        val expectedHeight = if (orientation >= 5) dimensions.first else dimensions.second
                        assertEquals(expectedWidth, result.output.width); assertEquals(expectedHeight, result.output.height)
                        assertTrue(result.geometry.fullCoverage)
                        assertEquals(strength, result.request.strength, 0.0)
                        assertEquals(RenderedFaceState.NOT_RUN, result.face)
                        assertEquals(RenderedExposureState.DIAGNOSTIC, result.exposure)
                        assertEquals(AutoInputProfile.UNKNOWN, result.profile)
                        DngContentVerifier.verify(fingerprint, { input.inputStream() })
                        val same = AndroidJpegInspector().inspect({ output.inputStream() }, output.length()) { false }
                        assertEquals(result.fingerprint, same.fingerprint)
                        val options = BitmapFactory.Options().apply { inSampleSize = 1; inScaled = false }
                        val actual = requireNotNull(BitmapFactory.decodeFile(output.path, options))
                        try { assertEquals(expectedWidth, actual.width); assertEquals(expectedHeight, actual.height) } finally { actual.recycle() }
                        hashes.add(result.fingerprint.sha256)
                        report.put("status", "success").put("width", result.output.width).put("height", result.output.height)
                            .put("jpegBytes", result.fingerprint.bytes).put("jpegSha256", result.fingerprint.sha256)
                            .put("externalEv", result.externalEv).put("sourceUnchanged", true).put("sameJpegReadback", true)
                    } catch (error: Throwable) { failure = error; report.put("status", "failure").put("code", error.javaClass.simpleName); throw error }
                    finally {
                        try { attempt.close() } catch (close: Throwable) { if (failure == null) throw close else if (close !== failure) failure.addSuppressed(close) }
                        finally { metrics.stop(); metrics.appendTo(report); reportFile.writeText(all.toString(2)) }
                    }
                    assertFalse(attempt.hasUnreleasedResources)
                }
                if (size == "SMALL") assertNotEquals("Strength must affect real saved JPEG", hashes[0], hashes[1])
            }
            all.put("status", "success").put("complete", true)
        } catch (error: Throwable) { all.put("status", "failure").put("code", error.javaClass.simpleName); throw error }
        finally { reportFile.writeText(all.toString(2)) }
    }
}
