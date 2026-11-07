package dev.cinematic.p0.photoroll

import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Synthetic Android cancellation boundary. Does not open a photo or use the physical device. */
class PreviewRequestGateAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()

    @Test fun composeReplacementSkipsIntermediateSyntheticRequests() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val requested = androidx.compose.runtime.mutableStateOf(0)
        val displayed = androidx.compose.runtime.mutableStateOf(-1)
        val gate = PreviewRequestGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latest = CountDownLatch(1)
        val computed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val failures = AtomicReference<Throwable?>()
        fixture.content = {
            val revision = requested.value
            androidx.compose.runtime.LaunchedEffect(revision) {
                try {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        gate.run(true) {
                            computed += revision
                            if (revision == 0) {
                                entered.countDown()
                                check(release.await(15, TimeUnit.SECONDS))
                            }
                            revision * revision
                        }
                    }
                    displayed.value = result
                    if (revision == 20) latest.countDown()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) { failures.set(failure); latest.countDown() }
            }
            dev.cinematic.p0.CaptureTheme { androidx.compose.material3.Text("synthetic=${displayed.value}") }
        }
        androidx.test.core.app.ActivityScenario.launch(PhotoRollActivity::class.java).use {
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                for (revision in 1..20) {
                    instrumentation.runOnMainSync { requested.value = revision }
                    instrumentation.waitForIdleSync()
                    android.os.SystemClock.sleep(30)
                }
                assertEquals(listOf(0), computed.toList())
            } finally { release.countDown() }
            assertTrue(latest.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertNull(failures.get())
            instrumentation.runOnMainSync { assertEquals(400, displayed.value) }
            assertEquals(listOf(0, 20), computed.toList())
            fun containsLatest(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean =
                node != null && (node.text?.toString() == "synthetic=400" ||
                    (0 until node.childCount).any { containsLatest(node.getChild(it)) })
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            while (!containsLatest(fixture.fixtureRoot()) && android.os.SystemClock.uptimeMillis() < deadline) {
                android.os.SystemClock.sleep(50)
                instrumentation.waitForIdleSync()
            }
            assertTrue("Latest synthetic result is visible", containsLatest(fixture.fixtureRoot()))
        }
    }
    @Test fun cancelledAwaitKeepsOwnerUntilProducerCompletes() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val signal = CancellationSignal()
        val future = CompletableFuture<Int>()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val outcome = AtomicReference<Throwable?>()
        val worker = Thread {
            started.countDown()
            try { awaitTrialPreview(future, signal, awaitCancelledOwner = true) }
            catch (failure: Throwable) { outcome.set(failure) }
            finally { finished.countDown() }
        }
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            signal.cancel()
            assertFalse(finished.await(400, TimeUnit.MILLISECONDS))
            future.completeExceptionally(IllegalStateException("synthetic cancelled producer"))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(outcome.get() is OperationCanceledException)
        } finally { future.complete(1); worker.join(5000) }
        assertFalse(worker.isAlive)
    }
}
