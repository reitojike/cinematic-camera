package dev.cinematic.p0.raw

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** 指定しなければskip。合成比較のみで写真・実機固有情報は扱わない。 */
class PreviewParallelDiagnosticTest {
    @Before fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("previewParallelProbe") == "true")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
    }
    @Test fun smallStagesMatchInBothOrders() {
        val forward = PreviewParallelDiagnostic.compare(65, 49)
        val reverse = PreviewParallelDiagnostic.compare(65, 49, reverse = true)
        assertEquals(6, forward.samples.size)
        for (stage in listOf("Foundation", "Orton", "Character")) {
            val a = forward.samples.filter { it.stage == stage }
            val b = reverse.samples.filter { it.stage == stage }
            assertEquals(listOf(1, 2), a.map { it.workers })
            assertEquals(listOf(2, 1), b.map { it.workers })
            assertEquals(1, (a + b).map { it.sha256 }.distinct().size)
            assertTrue((a + b).all { it.nanos > 0 })
        }
    }
    @Test fun cancellationAndRetry() {
        val calls = AtomicInteger()
        try {
            PreviewParallelDiagnostic.compare(65, 49, reverse = true, shouldStop = { calls.incrementAndGet() > 8 })
            fail("取消されること")
        } catch (_: CancellationException) { }
        assertEquals(6, PreviewParallelDiagnostic.compare(65, 49).samples.size)
    }
    @Test fun entryDoesNotStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PreviewParallelDiagnosticActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            assertFalse(PreviewParallelDiagnosticActivity.active())
        } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
    }
    @Test fun full1024StagesMatch() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("previewParallelLoad") == "true")
        assertEquals(6, PreviewParallelDiagnostic.compare().samples.size)
    }

    @Test fun explicitStartCanCancelAndReleaseGate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PreviewParallelDiagnosticActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun buttons(view: android.view.View): List<android.widget.Button> =
            if (view is android.widget.Button) listOf(view)
            else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
            else emptyList()
        try {
            instrumentation.runOnMainSync {
                buttons(activity.window.decorView).first { it.text.toString().contains("1→2") }.performClick()
                assertTrue(PreviewParallelDiagnosticActivity.active())
                PreviewParallelDiagnosticActivity.cancel()
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (PreviewParallelDiagnosticActivity.active() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertFalse("worker終了後に入口を解放する", PreviewParallelDiagnosticActivity.active())
        } finally {
            PreviewParallelDiagnosticActivity.cancel()
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

}
