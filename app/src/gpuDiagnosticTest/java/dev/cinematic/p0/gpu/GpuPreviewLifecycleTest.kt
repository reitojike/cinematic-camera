package dev.cinematic.p0.gpu

import android.content.ComponentCallbacks2
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in diagnostic variant only; exercises the real Compose surface/lifecycle observer. */
@RunWith(AndroidJUnit4::class)
class GpuPreviewLifecycleTest {
    @Before fun syntheticEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Synthetic emulator only" }
    }

    @Suppress("DEPRECATION")
    @Test fun composeStopStartTrimAndTenActivityRecreationsRenderAgain() {
        ActivityScenario.launch(GpuPreviewDiagnosticActivity::class.java).use { scenario ->
            repeat(10) { cycle ->
                val currentRenderer = renderer(scenario)
                val active = awaitSnapshot(currentRenderer, "cycle $cycle initial render") {
                    it.drawCount > 0 && it.windowCount == 1 && it.allocatedBytes > 0
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                val stopped = awaitSnapshot(currentRenderer, "cycle $cycle stop releases EGL window") { it.windowCount == 0 }
                scenario.onActivity { it.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
                val trimmed = awaitSnapshot(currentRenderer, "cycle $cycle trim releases GPU input") { it.allocatedBytes == 0L }
                assertEquals(stopped.drawCount, trimmed.drawCount)
                assertEquals(0, trimmed.windowCount)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val resumed = awaitSnapshot(currentRenderer, "cycle $cycle start reloads retained input") {
                    it.drawCount > trimmed.drawCount && it.windowCount == 1 && it.allocatedBytes > 0
                }
                assertEquals(active.allocatedBytes, resumed.allocatedBytes)
                scenario.recreate()
                val replacement = renderer(scenario)
                assertNotSame("cycle $cycle owns a fresh renderer", currentRenderer, replacement)
                awaitSnapshot(replacement, "cycle $cycle recreated Compose surface renders") {
                    it.drawCount > 0 && it.windowCount == 1 && it.allocatedBytes > 0
                }
            }
        }
    }

    private fun renderer(scenario: ActivityScenario<GpuPreviewDiagnosticActivity>): GpuPreviewRenderer {
        val value = AtomicReference<GpuPreviewRenderer>()
        scenario.onActivity { value.set(it.renderer) }
        return checkNotNull(value.get())
    }

    private fun awaitSnapshot(renderer: GpuPreviewRenderer, label: String,
        predicate: (GpuPreviewSnapshot) -> Boolean): GpuPreviewSnapshot {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var latest: GpuPreviewSnapshot? = null
        do {
            val ready = CountDownLatch(1)
            val value = AtomicReference<GpuPreviewSnapshot>()
            renderer.snapshot { value.set(it); ready.countDown() }
            assertTrue("$label: snapshot callback timed out", ready.await(5, TimeUnit.SECONDS))
            latest = checkNotNull(value.get())
            if (predicate(latest)) return latest
            SystemClock.sleep(25)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("$label: condition timed out; last=$latest")
    }
}
