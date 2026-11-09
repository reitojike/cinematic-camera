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
import android.widget.CheckBox
import android.widget.Spinner
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
    @Test fun tenFreshAttemptsAndPrivacyForEveryComparison() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        assertTrue(info.requestedPermissions.isNullOrEmpty())
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        val cases = DiagnosticComparison.timingComparisons.map { it to false } + (DiagnosticComparison.RAW4 to true)
        for ((comparison, reverse) in cases) {
            val existingRoots = context.noBackupFilesDir.listFiles().orEmpty().map { it.name }.toSet()
            val runner = RawDiagnosticRunner(context)
            val progress = mutableListOf<String>()
            val reportText = runner.run(uri(), false, reverse, comparison, progress::add)
            val report = JSONObject(reportText)
            assertEquals(reportText, "SUCCESS", report.getString("status"))
            assertTrue(report.getBoolean("complete"))
            assertTrue(report.getBoolean("selectedOriginalUnchanged"))
            assertTrue(report.getBoolean("privateInputRemoved"))
            assertTrue(report.getBoolean("resourcesQuiescent"))
            assertTrue(runner.resourcesQuiescent)
            assertEquals(4, report.getInt("measuredSuccessesA"))
            assertEquals(4, report.getInt("measuredSuccessesB"))
            val runs = report.getJSONArray("runs")
            assertEquals(10, runs.length()); assertEquals(10, progress.size)
            for (index in 0 until 10) {
                val run = runs.getJSONObject(index)
                assertEquals(index, run.getInt("sequence"))
                val profile = comparison.profile(run.getInt("arm"))
                assertEquals(profile.foundation, run.getInt("foundationWorkers"))
                assertEquals(profile.glow, run.getInt("glowWorkers"))
                assertEquals(profile.tone, run.getInt("toneWorkers"))
                assertEquals(profile.mist, run.getInt("mistWorkers"))
                assertEquals(profile.fuseColor, run.getBoolean("fuseR7ColorRows"))
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
            if (comparison == DiagnosticComparison.RAW4) {
                // Only fixed-schema numeric synthetic reports; never copies source/photos or paths into the report.
                val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                val outputRoot = if (configured != null) File(configured) else
                    File(requireNotNull(context.externalMediaDirs.firstOrNull()), "additional_test_output")
                val reports = File(outputRoot, "raw4-synthetic")
                check(reports.isDirectory || reports.mkdirs())
                File(reports, if (reverse) "reverse.json" else "forward.json").writeText(reportText)
            }
            val sessionRoot = context.noBackupFilesDir.listFiles().orEmpty().single {
                it.name.startsWith("raw-diagnostic-") && it.name !in existingRoots
            }
            assertEquals((0 until 10).map { "attempt-$it" }.toSet(),
                sessionRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet())
            assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        }
        val roots = context.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("raw-diagnostic-") }
        assertTrue(roots.isNotEmpty())
        assertTrue(roots.none { File(it, "input.dng").exists() })
        assertTrue(roots.none { root -> root.walkTopDown().any { it.extension == "jpg" } })
    }
    @Test fun memoryModeAndReverseOrderAreSeparate() {
        for (comparison in listOf(DiagnosticComparison.RAW4, DiagnosticComparison.SAVE_SAFETY)) {
            val reverse = comparison == DiagnosticComparison.RAW4
            val report = JSONObject(RawDiagnosticRunner(context).run(uri(), true, reverse, comparison) {})
            assertEquals("SUCCESS", report.getString("status"))
            assertEquals("MEMORY_PERIODIC_NOT_TIMING", report.getString("mode"))
            assertEquals(if (reverse) "BA" else "AB", report.getString("order"))
            assertEquals(comparison.name, report.getString("comparison"))
            assertTrue(report.getBoolean("complete"))
            assertTrue(report.getBoolean("selectedOriginalUnchanged"))
            assertTrue(report.getBoolean("privateInputRemoved"))
            assertTrue(report.getBoolean("resourcesQuiescent"))
            assertEquals("NOT_TIMING", report.getString("performanceDecision"))
            assertEquals(1, report.getInt("measuredSuccessesA"))
            assertEquals(1, report.getInt("measuredSuccessesB"))
            val runs = report.getJSONArray("runs")
            assertEquals(2, runs.length())
            for (index in 0 until 2) {
                val run = runs.getJSONObject(index)
                assertEquals(DiagnosticPlan.workers(reverse, memory = true)[index], run.getInt("arm"))
                assertProfile(report, run, comparison)
                assertFalse(run.getBoolean("excludedWarmup"))
                assertTrue(run.getBoolean("jpegMatchesSessionBaseline"))
                assertTrue(run.getBoolean("sourceUnchanged"))
                assertTrue(run.has("memoryObserved"))
                assertTrue(run.getJSONObject("memoryObserved").getBoolean("quiescent"))
                assertTrue(run.getJSONObject("memoryObserved").getLong("sampleCount") > 0)
                assertFalse(run.has("memoryBoundaries"))
                assertFalse(run.has("renderAndCloseNanos"))
            }
        }
    }
    @Test fun boundedCancellationChecksNeverCountAsTimingSuccess() {
        for (comparison in listOf(DiagnosticComparison.RAW4, DiagnosticComparison.SAVE_SAFETY)) {
            val runner = RawDiagnosticRunner(context)
            val report = JSONObject(runner.runCancellation(uri(), comparison) {})
            assertEquals("SUCCESS", report.getString("status"))
            assertEquals("CANCELLATION_BOUNDARIES_NOT_TIMING", report.getString("mode"))
            assertEquals(comparison.name, report.getString("comparison"))
            assertEquals("AFTER_NATIVE_DECODE_AND_FOUNDATION_NOT_WORKER_IN_FLIGHT", report.getString("cancellationScope"))
            assertEquals("BB", report.getString("order"))
            assertEquals("NOT_TIMING", report.getString("performanceDecision"))
            assertTrue(report.getBoolean("complete"))
            assertEquals(2, report.getInt("expectedCancellationChecks"))
            assertEquals(0, report.getInt("measuredSuccessesB"))
            assertTrue(report.getBoolean("selectedOriginalUnchanged"))
            assertTrue(report.getBoolean("privateInputRemoved"))
            assertTrue(runner.resourcesQuiescent)
            val runs = report.getJSONArray("runs")
            assertEquals(2, runs.length())
            for (index in 0 until 2) {
                val item = runs.getJSONObject(index)
                assertEquals("CANCELLED_EXPECTED", item.getString("status"))
                assertEquals(2, item.getInt("arm"))
                assertProfile(report, item, comparison)
                assertEquals(if (index == 0) "NATIVE_DECODE" else "R7_FOUNDATION", item.getString("cancelBoundary"))
                assertTrue(item.getBoolean("ownerClosed"))
                assertTrue(item.getBoolean("noCompletedResult"))
                assertTrue(item.getBoolean("partialJpegAbsent"))
                assertTrue(item.getBoolean("sourceUnchanged"))
                assertFalse(item.has("renderAndCloseNanos"))
            }
        }
    }
    private fun assertProfile(report: JSONObject, run: JSONObject, comparison: DiagnosticComparison) {
        val profile = comparison.profile(run.getInt("arm"))
        assertEquals(2, run.getInt("foundationWorkers"))
        assertEquals(4, report.getInt("ortonWorkers"))
        assertEquals(2, report.getInt("characterWorkers"))
        assertEquals(profile.glow, run.getInt("glowWorkers"))
        assertEquals(profile.mist, run.getInt("mistWorkers"))
        assertEquals(profile.tone, run.getInt("toneWorkers"))
        assertEquals(profile.fuseColor, run.getBoolean("fuseR7ColorRows"))
    }
    @Test fun safetyTimingAndUnsupportedCancellationFailBeforeImport() {
        val runner = RawDiagnosticRunner(context)
        assertThrows(IllegalArgumentException::class.java) {
            runner.run(uri(), false, false, DiagnosticComparison.SAVE_SAFETY) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            runner.runCancellation(uri(), DiagnosticComparison.TONE_ONLY) {}
        }
        assertTrue(runner.resourcesQuiescent)
    }
    @Test fun safetyButtonsIgnoreLegacyControlsAndKeepProfileAcrossSessions() {
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val picker = instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri())), true)
        try {
            ActivityScenario.launch(RawDiagnosticActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val all = views(activity.window.decorView)
                    all.filterIsInstance<CheckBox>().forEach { it.isChecked = it.text.startsWith("Reverse") }
                    all.filterIsInstance<Spinner>().single().setSelection(
                        DiagnosticComparison.timingComparisons.indexOf(DiagnosticComparison.COLOR_FUSION_ONLY))
                }
                for (cancel in listOf(false, true)) {
                    click(scenario, if (cancel) "Save safety: 2 cancellation checks" else "Save safety: Memory A/B (2 attempts)")
                    val report = JSONObject(awaitStatus(scenario) { it.startsWith("{") })
                    assertEquals("SUCCESS", report.getString("status"))
                    assertTrue(report.getBoolean("complete"))
                    assertEquals("SAVE_SAFETY", report.getString("comparison"))
                    assertEquals(if (cancel) "BB" else "AB", report.getString("order"))
                    assertEquals(if (cancel) "CANCELLATION_BOUNDARIES_NOT_TIMING" else "MEMORY_PERIODIC_NOT_TIMING", report.getString("mode"))
                    assertEquals("NOT_TIMING", report.getString("performanceDecision"))
                    val runs = report.getJSONArray("runs")
                    assertEquals(2, runs.length())
                    for (index in 0 until runs.length()) assertProfile(report, runs.getJSONObject(index), DiagnosticComparison.SAVE_SAFETY)
                }
                click(scenario, "Select DNG for 2 cancellation checks")
                val legacy = JSONObject(awaitStatus(scenario) { it.startsWith("{") })
                assertEquals("RAW4", legacy.getString("comparison"))
                assertTrue(legacy.getBoolean("complete"))
                assertTrue(legacy.getJSONArray("runs").getJSONObject(0).getBoolean("fuseR7ColorRows"))
                assertEquals(3, picker.hits)
            }
        } finally { instrumentation.removeMonitor(picker) }
    }
    @Test fun singleMemoryArmsKeepSamplingAndDoNotClaimCrossArmJpegEquality() {
        for (arm in 1..2) {
            val report = JSONObject(RawDiagnosticRunner(context).runSingleMemory(uri(), arm) {})
            assertEquals("SUCCESS", report.getString("status"))
            assertTrue(report.getBoolean("complete")) // Only this one attempt, not an A/B safety pass.
            assertEquals("MEMORY_SINGLE_ARM_NOT_TIMING", report.getString("mode"))
            assertEquals(if (arm == 1) "A" else "B", report.getString("order"))
            assertEquals("NOT_AVAILABLE_SINGLE_ARM", report.getString("crossArmJpegComparison"))
            assertEquals("REQUIRES_MATCHED_CONDITIONS_NOT_AUTOMATIC_PASS", report.getString("memoryComparisonDecision"))
            assertEquals(if (arm == 1) 1 else 0, report.getInt("measuredSuccessesA"))
            assertEquals(if (arm == 2) 1 else 0, report.getInt("measuredSuccessesB"))
            assertTrue(report.getBoolean("selectedOriginalUnchanged"))
            assertTrue(report.getBoolean("resourcesQuiescent")); assertTrue(report.getBoolean("privateInputRemoved"))
            val runs = report.getJSONArray("runs"); assertEquals(1, runs.length())
            val run = runs.getJSONObject(0); assertEquals(arm, run.getInt("arm"))
            assertProfile(report, run, DiagnosticComparison.SAVE_SAFETY)
            assertFalse(run.getBoolean("excludedWarmup")); assertFalse(run.has("renderAndCloseNanos"))
            assertTrue(run.has("memoryBefore")); assertTrue(run.has("memoryAfter"))
            val sampled = run.getJSONObject("memoryObserved")
            assertTrue(sampled.getBoolean("quiescent")); assertFalse(sampled.getBoolean("failed"))
            assertTrue(sampled.getInt("sampleCount") > 0)
            assertEquals(100_000_000L, sampled.getLong("requestedIntervalNanos"))
        }
    }
    @Test fun singleMemoryUiFreezesSelectedArmAndIgnoresLegacySettings() {
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val picker = instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri())), true)
        try {
            ActivityScenario.launch(RawDiagnosticActivity::class.java).use { scenario ->
                for (arm in 1..2) {
                    val label = if (arm == 1) "A" else "B"
                    click(scenario, "Save safety: Memory $label (1 attempt)")
                    val report = JSONObject(awaitStatus(scenario) { it.startsWith("{") && it.contains("MEMORY_SINGLE_ARM_NOT_TIMING") })
                    assertEquals(label, report.getString("order"))
                    assertEquals("SAVE_SAFETY", report.getString("comparison"))
                    assertTrue(report.getBoolean("complete"))
                }
                assertEquals(2, picker.hits)
            }
        } finally { instrumentation.removeMonitor(picker) }
    }
    @Test fun manualReceiptAtStageEntryRecordsLatenciesWithoutClaimingBoundarySuccess() {
        // Synthetic receipt at the trace entry, not a physical tap or a worker-in-flight speed measurement.
        for (plan in ManualCancellationPlan.entries) {
            val target = plan.targetStage.name
            val runner = RawDiagnosticRunner(context)
            val report = JSONObject(runner.runManualCancellation(uri(), plan) { message ->
                if (message.startsWith("Running $target;")) runner.cancelFromUser()
            })
            assertEquals("STOPPED", report.getString("status"))
            assertFalse(report.getBoolean("complete"))
            assertEquals("MANUAL_CANCELLATION_LATENCY_NOT_TIMING", report.getString("mode"))
            assertEquals("SAVE_SAFETY", report.getString("comparison"))
            assertEquals(plan.armLabel, report.getString("order"))
            assertEquals(target, report.getString("intendedCancellationStage"))
            assertEquals("NOT_TIMING", report.getString("performanceDecision"))
            assertTrue(report.getBoolean("resourcesQuiescent"))
            assertTrue(report.getBoolean("privateInputRemoved"))
            val runs = report.getJSONArray("runs")
            assertEquals(1, runs.length())
            val run = runs.getJSONObject(0)
            assertProfile(report, run, DiagnosticComparison.SAVE_SAFETY)
            assertFalse(run.getBoolean("excludedWarmup"))
            assertFalse(run.has("renderAndCloseNanos"))
            assertTrue(run.getBoolean("ownerClosed"))
            assertTrue(run.getBoolean("noCompletedResult"))
            assertTrue(run.getBoolean("outputAbsentAtAttemptScopeExit"))
            val observed = report.getJSONObject("cancellationObservation")
            assertEquals("OBSERVED_TARGET_STAGE_CANCEL", observed.getString("status"))
            assertEquals("USER_BUTTON", observed.getString("requestOrigin"))
            assertEquals(target, observed.getString("stageAtRequest"))
            assertTrue(observed.getLong("stageElapsedAtRequestNanos") >= 0)
            assertEquals("CANCELLED", observed.getString("renderOutcome"))
            val render = observed.getLong("requestToRenderExitNanos")
            val scope = observed.getLong("requestToAttemptScopeExitNanos")
            val owners = observed.getLong("requestToOwnersReleasedNanos")
            val harness = observed.getLong("requestToHarnessQuiescentNanos")
            assertTrue(render >= 0); assertTrue(scope >= render)
            assertEquals(scope, owners); assertTrue(harness >= owners)
            assertFalse(report.has("selectedOriginalUnchanged")) // Interrupted sessions do not claim a final source recheck.
        }
    }
    @Test fun manualNoPressAndLifecycleCancellationRemainDistinct() {
        val completed = JSONObject(RawDiagnosticRunner(context).runManualCancellation(uri(), ManualCancellationPlan.B_GLOW) {})
        assertEquals("FINISHED_WITHOUT_CANCELLATION", completed.getString("status"))
        assertFalse(completed.getBoolean("complete"))
        val noPress = completed.getJSONObject("cancellationObservation")
        assertEquals("NO_REQUEST", noPress.getString("status"))
        assertEquals("SUCCESS", noPress.getString("renderOutcome"))
        assertTrue(noPress.isNull("requestToRenderExitNanos"))
        val runner = RawDiagnosticRunner(context)
        val background = JSONObject(runner.runManualCancellation(uri(), ManualCancellationPlan.B_GLOW) { message ->
            if (message.startsWith("Running GLOW;")) runner.cancel()
        })
        assertEquals("STOPPED", background.getString("status"))
        assertEquals("NON_USER_REQUEST", background.getJSONObject("cancellationObservation").getString("status"))
        assertEquals("LIFECYCLE", background.getJSONObject("cancellationObservation").getString("requestOrigin"))
    }
    @Test fun manualUiCancelUsesButtonReceiptWhileImportIsStillRunning() {
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") }
        val picker = instrumentation.addMonitor(filter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri(true))), true)
        try {
            ActivityScenario.launch(RawDiagnosticActivity::class.java).use { scenario ->
                click(scenario, ManualCancellationPlan.A_MIST.label)
                awaitStatus(scenario) { it.startsWith("Preparing") }
                click(scenario, "Cancel")
                val report = JSONObject(awaitStatus(scenario) { it.startsWith("{") })
                assertEquals("MANUAL_CANCELLATION_LATENCY_NOT_TIMING", report.getString("mode"))
                assertEquals("STOPPED", report.getString("status"))
                assertEquals("A", report.getString("order"))
                assertEquals("MIST", report.getString("intendedCancellationStage"))
                val observed = report.getJSONObject("cancellationObservation")
                assertEquals("USER_BUTTON", observed.getString("requestOrigin"))
                assertEquals("OUTSIDE_GLOW_MIST", observed.getString("status"))
                assertTrue(observed.isNull("requestToRenderExitNanos"))
                assertTrue(report.getBoolean("resourcesQuiescent"))
                click(scenario, "Select DNG for 2 cancellation checks")
                val legacy = JSONObject(awaitStatus(scenario) {
                    it.startsWith("{") && it.contains("CANCELLATION_BOUNDARIES_NOT_TIMING")
                })
                assertEquals("RAW4", legacy.getString("comparison"))
                assertTrue(legacy.getBoolean("complete"))
                assertFalse(legacy.has("cancellationObservation"))
            }
        } finally { instrumentation.removeMonitor(picker) }
    }
    private fun views(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun click(scenario: ActivityScenario<RawDiagnosticActivity>, label: String) {
        scenario.onActivity { activity ->
            views(activity.window.decorView).filterIsInstance<Button>().single { it.text == label }.performClick()
        }
    }
    private fun awaitStatus(scenario: ActivityScenario<RawDiagnosticActivity>, predicate: (String) -> Boolean): String {
        val deadline = SystemClock.uptimeMillis() + 30000
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
                    val deadline = SystemClock.uptimeMillis() + 30000
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
