package dev.cinematic.p0.rawdiagnostic

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** ATD: verifies picker contract/result, not the external DocumentsUI application's rendering. */
class RawDiagnosticSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun uri(slow: Boolean = false) = Uri.parse(
        "content://dev.cinematic.p0.rawdiagnostic.test.synthetic/" + if (slow) "slow.dng" else "fixture.dng")
    @Before fun requireIsolatedEmulator() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(context.packageName == "dev.cinematic.p0.rawdiagnostic")
        context.contentResolver.call(uri(), "prepareSynthetic", null, android.os.Bundle().apply {
            putByteArray("bytes", dev.cinematic.p0.testing.syntheticTrialDng(64, 48))
        })
    }
    @Test fun eightFreshAttemptsAndPrivacyForEveryComparison() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(info.requestedPermissions.isNullOrEmpty())
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        for (comparison in DiagnosticComparison.entries) {
            val existingRoots = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.toSet()
            val runner = RawDiagnosticRunner(context)
            val progress = mutableListOf<String>()
            val reportText = runner.run(uri(), false, false, comparison, progress::add)
            val report = JSONObject(reportText)
            assertEquals(reportText, "SUCCESS", report.getString("status"))
            assertTrue(report.getBoolean("complete"))
            assertTrue(report.getBoolean("selectedOriginalUnchanged"))
            assertTrue(report.getBoolean("privateInputRemoved"))
            assertTrue(report.getBoolean("resourcesQuiescent"))
            assertTrue(runner.resourcesQuiescent)
            assertEquals(3, report.getInt("measuredSuccessesA"))
            assertEquals(3, report.getInt("measuredSuccessesB"))
            val runs = report.getJSONArray("runs")
            assertEquals(8, runs.length()); assertEquals(8, progress.size)
            for (index in 0 until 8) {
                val run = runs.getJSONObject(index)
                assertEquals(index, run.getInt("sequence"))
                assertEquals(index < 2, run.getBoolean("excludedWarmup"))
                assertTrue(run.getBoolean("jpegMatchesSessionBaseline"))
                assertTrue(run.getBoolean("sameJpegReadback"))
                assertTrue(run.getBoolean("sourceUnchanged"))
                assertEquals("SUCCESS", run.getString("status"))
            }
            assertFalse(reportText.contains("content://"))
            assertFalse(reportText.contains("fixture.dng"))
            assertFalse(reportText.contains("Synthetic"))
            assertFalse(reportText.contains("sha256", ignoreCase = true))
            val sessionRoot = context.noBackupFilesDir.listFiles().orEmpty().single {
                it.name.startsWith("raw-diagnostic-") && it.name !in existingRoots
            }
            assertEquals((0 until 8).map { "attempt-$it" }.toSet(),
                sessionRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet())
            assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        }
        val roots = context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("raw-diagnostic-") }
        assertTrue(roots.isNotEmpty())
        assertTrue(roots.none { File(it, "input.dng").exists() })
        assertTrue(roots.none { root -> root.walkTopDown().any { it.extension == "jpg" } })
    }
    @Test fun memoryModeAndReverseOrderAreSeparate() {
        val report = JSONObject(RawDiagnosticRunner(context).run(uri(), true, true, DiagnosticComparison.FOUNDATION) {})
        assertEquals("SUCCESS", report.getString("status"))
        assertEquals("MEMORY_BOUNDARIES_NOT_TIMING", report.getString("mode"))
        assertEquals("BA-BAABBA", report.getString("order"))
        val runs = report.getJSONArray("runs")
        for (index in 0 until 8) {
            val run = runs.getJSONObject(index)
            assertEquals(DiagnosticPlan.workers(true)[index], run.getInt("arm"))
            assertTrue(run.has("memoryBoundaries"))
            assertFalse(run.has("renderAndCloseNanos"))
        }
    }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun click(scenario: ActivityScenario<RawDiagnosticActivity>, label: String) {
        scenario.onActivity { activity ->
            views(activity.window.decorView).filterIsInstance<Button>().single { it.text == label }.performClick()
        }
    }
    private fun awaitStatus(scenario: ActivityScenario<RawDiagnosticActivity>, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.uptimeMillis() + 15000
        var status = ""
        do {
            scenario.onActivity { activity ->
                status = views(activity.window.decorView).filterIsInstance<TextView>().last().text.toString()
            }
            if (predicate(status)) return status
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Expected diagnostic status, actual=$status")
    }
    @Test fun pickerCancellationBackgroundRecreationGateAndRestart() {
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val cancelled = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        ActivityScenario.launch(RawDiagnosticActivity::class.java).use { scenario ->
            click(scenario, "Select authorized DNG and start")
            assertEquals(1, cancelled.hits)
            awaitStatus(scenario) { it == "Ready" }
            instrumentation.removeMonitor(cancelled)
            for (recreate in listOf(false, true)) {
                val oldRoots = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.toSet()
                val result = Intent().setData(uri(true)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val picker = instrumentation.addMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_OK, result), true)
                try {
                    click(scenario, "Select authorized DNG and start")
                    awaitStatus(scenario) { it.startsWith("Preparing") || it.startsWith("Attempt") }
                    click(scenario, "Select authorized DNG and start")
                    awaitStatus(scenario) { it == "An attempt is running or closing. Please wait." }
                    assertEquals(1, picker.hits)
                    if (recreate) scenario.recreate() else {
                        scenario.moveToState(Lifecycle.State.CREATED)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                    }
                    // Recreation intentionally leaves old owner results on the old Activity. Inspect its private report.
                    val deadline = SystemClock.uptimeMillis() + 15000
                    var stopped: JSONObject? = null
                    do {
                        stopped = context.noBackupFilesDir.listFiles().orEmpty()
                            .filter { it.name.startsWith("raw-diagnostic-") && it.name !in oldRoots }
                            .mapNotNull { root -> runCatching { JSONObject(File(root, "report.json").readText()) }.getOrNull() }
                            .firstOrNull { it.optString("status") == "STOPPED" && it.optBoolean("resourcesQuiescent") }
                        if (stopped != null) break
                        SystemClock.sleep(25)
                    } while (SystemClock.uptimeMillis() < deadline)
                    assertNotNull("Cancellation must finish and release the owner", stopped)
                    assertTrue(requireNotNull(stopped).getBoolean("privateInputRemoved"))
                } finally { instrumentation.removeMonitor(picker) }
                val restart = instrumentation.addMonitor(filter,
                    Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri())), true)
                try {
                    click(scenario, "Select authorized DNG and start")
                    val finalReport = JSONObject(awaitStatus(scenario) { it.startsWith("{") })
                    assertEquals("SUCCESS", finalReport.getString("status"))
                    assertTrue(finalReport.getBoolean("complete"))
                } finally { instrumentation.removeMonitor(restart) }
            }
        }
    }
}
