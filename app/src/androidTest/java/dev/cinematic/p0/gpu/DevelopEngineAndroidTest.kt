package dev.cinematic.p0.gpu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.photoroll.PhotoRollActivity
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 合成の能力/失敗だけを使う。EGL・実カメラ・実写真・共有保存は起動しない。 */
class DevelopEngineAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun isolated() = check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var passed = false
            instrumentation.runOnMainSync { passed = condition() }
            if (passed) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Synthetic GPU UI condition did not become true")
    }

    @Test fun devicePreferenceSurvivesSettingRecreation() {
        isolated()
        assumeTrue(BuildConfig.BUILD_TYPE == "gpuDiagnostic")
        val base = instrumentation.targetContext
        val name = "develop-engine-synthetic-${java.util.UUID.randomUUID()}"
        // Test-only preference file; never modify the installed app's capture-probe preferences.
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(name, mode)
        }
        try {
            val first = DevelopEngineSetting(context)
            assertTrue(first.visible)
            assertEquals(DevelopEngine.CPU, first.current())
            assertTrue(first.select(DevelopEngine.GPU, GpuAvailability.Available))
            val next = DevelopEngineSetting(context)
            assertEquals(DevelopEngine.GPU, next.current())
            val activeExport = next.beginExport()
            assertTrue(first.select(DevelopEngine.CPU, GpuAvailability.Available))
            assertEquals(DevelopEngine.GPU, activeExport)
            assertEquals(DevelopEngine.CPU, next.current())
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun ordinaryContextIgnoresAllDiagnosticPreferencesWithoutMigration() {
        isolated()
        assumeTrue(BuildConfig.BUILD_TYPE in setOf("debug", "release"))
        val base = instrumentation.targetContext
        val name = "develop-engine-ordinary-${java.util.UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(name, mode)
        }
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            for (value in listOf(null, "CPU", "GPU", "future-value")) {
                val edit = preferences.edit()
                if (value == null) edit.remove("develop/engine") else edit.putString("develop/engine", value)
                assertTrue(edit.commit())
                val setting = DevelopEngineSetting(context)
                assertFalse(setting.visible)
                assertEquals(DevelopEngine.GPU, setting.current())
                assertEquals(DevelopEngine.GPU, setting.beginExport())
                for (engine in DevelopEngine.entries) assertFalse(setting.select(engine, GpuAvailability.Available))
                assertEquals(value, preferences.getString("develop/engine", null))
            }
        } finally { base.deleteSharedPreferences(name) }
    }

    @Test fun unavailableCapabilityDisablesGpuAndShowsReasonThenRetryCanRecover() {
        isolated()
        var value = "CPU"
        val setting = DevelopEngineSetting({ value }, { value = it }, visible = true)
        var available = false
        lateinit var view: DevelopEngineSettingView
        val reason = "合成: 必須の float 描画に非対応"
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = DevelopEngineSettingView(activity, setting) {
                    if (available) GpuAvailability.Available else GpuAvailability.Unavailable(reason)
                }
                activity.setContentView(view)
            }
            awaitUi { descendants(view).filterIsInstance<TextView>().any { it.text.contains(reason) } }
            scenario.onActivity {
                val gpu = descendants(view).filterIsInstance<Button>().single { it.text == "GPU" }
                assertFalse(gpu.isEnabled)
                assertEquals(DevelopEngine.CPU, setting.current())
                available = true
                descendants(view).filterIsInstance<Button>().single { it.text == "GPU 能力を再確認" }.performClick()
            }
            awaitUi { descendants(view).filterIsInstance<Button>().single { it.text == "GPU" }.isEnabled }
            scenario.onActivity {
                descendants(view).filterIsInstance<Button>().single { it.text == "GPU" }.performClick()
                assertEquals(DevelopEngine.GPU, setting.current())
                assertEquals(DevelopEngine.GPU, setting.beginExport())
            }
        }
    }

    @Test fun checkingAndDisabledEditingNeverEnableGpu() {
        isolated()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        lateinit var view: DevelopEngineSettingView
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    view = DevelopEngineSettingView(activity, DevelopEngineSetting({ "CPU" }, {}, true)) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        GpuAvailability.Available
                    }
                    activity.setContentView(view)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    assertFalse(descendants(view).filterIsInstance<Button>().single { it.text == "GPU" }.isEnabled)
                    view.setEditingEnabled(false)
                }
                release.countDown()
                awaitUi { descendants(view).filterIsInstance<TextView>().any { it.text.contains("必須能力を確認しました") } }
                scenario.onActivity { assertTrue(descendants(view).filterIsInstance<Button>().none { it.isEnabled }) }
            } finally { release.countDown() }
        }
    }

    @Test fun releasePolicyHasNoSettingAndDoesNotProbeGpu() {
        isolated()
        var inspected = false
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val setting = DevelopEngineSetting({ error("Ordinary policy must not read") },
                    { fail("Release must not write") }, false, DevelopEngine.GPU)
                val view = DevelopEngineSettingView(activity, setting) { inspected = true; GpuAvailability.Available }
                activity.setContentView(view)
                assertEquals(View.GONE, view.visibility)
                assertEquals(DevelopEngine.GPU, setting.current())
            }
            instrumentation.waitForIdleSync()
            assertFalse(inspected)
        }
    }

    @Test fun detachedProbeCannotOverwriteTheNewAttachment() {
        isolated()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val firstExited = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        lateinit var view: DevelopEngineSettingView
        lateinit var panel: LinearLayout
        val freshReason = "合成: 再接続後は非対応"
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    view = DevelopEngineSettingView(activity, DevelopEngineSetting({ "CPU" }, {}, true)) {
                        if (calls.incrementAndGet() == 1) {
                            firstEntered.countDown()
                            try {
                                check(releaseFirst.await(10, TimeUnit.SECONDS))
                                GpuAvailability.Available
                            } finally { firstExited.countDown() }
                        } else GpuAvailability.Unavailable(freshReason)
                    }
                    panel = LinearLayout(activity).apply { addView(view) }
                    activity.setContentView(panel)
                }
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                scenario.onActivity {
                    panel.removeView(view)
                    panel.addView(view)
                }
                // 取消済みの検査の解放後に次の照会へ進む。同時にGL資源を作らない。
                releaseFirst.countDown()
                assertTrue(firstExited.await(5, TimeUnit.SECONDS))
                awaitUi { descendants(view).filterIsInstance<TextView>().any { it.text.contains(freshReason) } }
                // Give any incorrectly retained continuation a chance to update the UI.
                repeat(10) {
                    SystemClock.sleep(25)
                    instrumentation.waitForIdleSync()
                    scenario.onActivity {
                        assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.contains(freshReason) })
                        assertFalse(descendants(view).filterIsInstance<Button>().single { it.text == "GPU" }.isEnabled)
                    }
                }
                assertEquals(2, calls.get())
            } finally { releaseFirst.countDown() }
        }
    }
    @Test fun recreatedSettingsViewUsesTheSharedCapabilityResult() {
        isolated()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val cache = DevelopGpuAvailabilityCache {
            calls.incrementAndGet()
            GpuAvailability.Available
        }
        lateinit var view: DevelopEngineSettingView
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            repeat(2) {
                scenario.onActivity { activity ->
                    view = DevelopEngineSettingView(activity, DevelopEngineSetting({ "CPU" }, {}, true), cache)
                    activity.setContentView(view)
                }
                awaitUi { descendants(view).filterIsInstance<Button>().single { it.text == "GPU" }.isEnabled }
                assertEquals(1, calls.get())
            }
        }
    }

    @Test fun contextLossShowsFailureAndRetryRecoversWithoutChangingEngine() {
        isolated()
        var value = "GPU"
        val setting = DevelopEngineSetting({ value }, { value = it }, true)
        var attempts = 0
        lateinit var panel: LinearLayout
        ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                fun render() {
                    panel.removeAllViews()
                    try {
                        attempts++
                        if (attempts == 1) throw GpuContextLostException("synthetic context loss")
                        panel.addView(TextView(activity).apply { text = "GPU preview recovered" })
                    } catch (failure: GpuContextLostException) {
                        panel.addView(GpuFailureView(activity, requireNotNull(gpuFailurePolicy(failure)), ::render))
                    }
                }
                render()
                activity.setContentView(panel)
            }
            awaitUi { descendants(panel).filterIsInstance<TextView>().any { it.text == "GPU を使えません" } }
            scenario.onActivity {
                assertEquals(DevelopEngine.GPU, setting.current())
                descendants(panel).filterIsInstance<Button>().single { it.text == "再試行" }.performClick()
            }
            awaitUi { descendants(panel).filterIsInstance<TextView>().any { it.text == "GPU preview recovered" } }
            assertEquals(2, attempts)
            assertEquals("GPU", value)
        }
    }
}
