package dev.cinematic.p0.metrics

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test

/** Debug-only, explicitly selected single-test emulator run. Never a full-suite test. */
class SaveTimingLifecycleAndroidTest {
    @Test fun realCallbacksRecordForegroundStartAndBackgroundEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(arguments.getString("isolated") == "true")
        check(arguments.getString("timingLifecycle") == "true")
        val selector = javaClass.name
        check(arguments.getString("class") in setOf(selector,
            "$selector#realCallbacksRecordForegroundStartAndBackgroundEnd")) {
            "Run this lifecycle test alone with its exact class or method selector"
        }
        val app = instrumentation.targetContext.applicationContext
        val probeName = "dev.cinematic.p0.metrics.TimingLifecycleProbeActivity"
        fun assertVisibility(expected: SaveVisibility) {
            instrumentation.waitForIdleSync()
            // Reject interference instead of interpreting another target Activity as background.
            // Framework helpers in the test APK have a different Application and are excluded.
            instrumentation.runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                val foreign = Stage.values().filter { it != Stage.DESTROYED }.flatMap {
                    monitor.getActivitiesInStage(it)
                }.any { it.application === app && it.javaClass.name != probeName }
                assertFalse("Another target Activity invalidates this isolated lifecycle run", foreign)
            }
            val actual = AndroidSaveTimingEnvironment.snapshot(app)
            assertEquals(expected, actual.visibility)
            assertTrue(actual.thermal == null || actual.thermal in 0..6)
        }
        // No manual install(): exercises registration through the real startup provider.
        assertVisibility(SaveVisibility.BACKGROUND)
        val intent = Intent().setClassName(app.packageName, probeName)
        ActivityScenario.launch<Activity>(intent).use { scenario ->
            assertVisibility(SaveVisibility.FOREGROUND)
            scenario.moveToState(Lifecycle.State.STARTED)
            assertVisibility(SaveVisibility.FOREGROUND)
            scenario.moveToState(Lifecycle.State.RESUMED)
            val records = mutableListOf<SaveTimingRecord>()
            val attempt = requireNotNull(SaveTiming.startObserved(22, SaveTimingKind.SAVE,
                records::addAll, { AndroidSaveTimingEnvironment.snapshot(app) }))
            scenario.moveToState(Lifecycle.State.CREATED)
            assertVisibility(SaveVisibility.BACKGROUND)
            attempt.finish(SaveTimingOutcome.COMPLETED)
            val environment = records.filter { it.environment != null }
            assertEquals(listOf(SaveTimingStage.ENVIRONMENT_START, SaveTimingStage.ENVIRONMENT_END),
                environment.map { it.stage })
            assertEquals(listOf(SaveVisibility.FOREGROUND, SaveVisibility.BACKGROUND),
                environment.map { requireNotNull(it.environment).visibility })
            assertTrue(environment.all { SaveTimingRecord.valid(it.line().trimEnd()) })
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertVisibility(SaveVisibility.FOREGROUND)
            scenario.recreate()
            assertVisibility(SaveVisibility.FOREGROUND)
        }
        assertVisibility(SaveVisibility.BACKGROUND)
    }
}
