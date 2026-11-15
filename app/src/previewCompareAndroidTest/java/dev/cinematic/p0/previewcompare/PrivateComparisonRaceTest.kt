package dev.cinematic.p0.previewcompare

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PrivateComparisonRaceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun emulatorOnly() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("privateCompareSynthetic") == "true")
    }
    private fun source(name: String, width: Int = 1024, height: Int = 768): File =
        File(app.cacheDir, name).also { it.outputStream().use { out -> writeSyntheticTrialDng(out, width, height, 1) } }
    private fun field(activity: Activity, name: String): Any? =
        PreviewCompareActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun select(activity: PreviewCompareActivity, file: File) =
        PreviewCompareActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, 1, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
    private fun await(scenario: ActivityScenario<PreviewCompareActivity>, condition: (PreviewCompareActivity) -> Boolean) {
        repeat(300) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            Thread.sleep(200)
        }
        fail("Comparison condition was not reached")
    }
    private fun assertReleased() {
        assertTrue(app.cacheDir.listFiles().orEmpty().none { it.name.startsWith("private-compare-") })
        val open = File("/proc/self/fd").listFiles().orEmpty().count {
            runCatching { android.system.Os.readlink(it.path).contains("private-compare-") }.getOrDefault(false)
        }
        assertEquals("No open private raster/snapshot descriptors", 0, open)
    }
    @Test fun cancellationInsideGpuPipelineReleasesCopiesAndOwners() {
        val file = source("cancel-mid-gpu.dng")
        val original = file.readBytes()
        val stop = ComparisonStop()
        val reached = AtomicBoolean()
        try {
            assertThrows(CancellationException::class.java) {
                PrivatePhotoComparison(app, onGpuStage = { point ->
                    if (point == GpuStageTap.Point.R7_FINAL) { reached.set(true); stop.cancel() }
                }).compare(Uri.fromFile(file), stop, 0.0) {}
            }
            assertTrue("Actual GPU R7 stage must have completed", reached.get())
            assertArrayEquals(original, file.readBytes())
            assertReleased()
        } finally { assertTrue(file.delete()) }
    }
    @Test fun nextSelectionCannotPublishPausedOldResult() {
        val first = source("selection-old.dng")
        val second = source("selection-new.dng", 768, 1024)
        val beforeA = first.readBytes()
        val beforeB = second.readBytes()
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val calls = AtomicInteger()
        val once = AtomicBoolean()
        PreviewCompareActivity.createComparison = { context ->
            val index = calls.incrementAndGet()
            PrivatePhotoComparison(context, onGpuStage = { point ->
                if (index == 1 && point == GpuStageTap.Point.R7_FINAL && once.compareAndSet(false, true)) {
                    paused.countDown()
                    check(resume.await(30, TimeUnit.SECONDS))
                }
            })
        }
        try {
            ActivityScenario.launch(PreviewCompareActivity::class.java).use { scenario ->
                scenario.onActivity { select(it, first) }
                assertTrue(paused.await(60, TimeUnit.SECONDS))
                scenario.onActivity { select(it, second) }
                resume.countDown()
                await(scenario) { field(it, "pair") != null }
                scenario.onActivity {
                    val pair = field(it, "pair") as PrivatePhotoComparison.Pair
                    assertEquals(768, pair.ahd.width)
                    assertEquals(1024, pair.ahd.height)
                    assertEquals(768, pair.ppg.width)
                    assertEquals(1024, pair.ppg.height)
                }
                assertEquals(2, calls.get())
                assertReleased()
            }
            assertArrayEquals(beforeA, first.readBytes())
            assertArrayEquals(beforeB, second.readBytes())
        } finally {
            resume.countDown()
            PreviewCompareActivity.createComparison = { PrivatePhotoComparison(it) }
            first.delete(); second.delete()
        }
    }
    @Test fun permissionLossDuringFinalVerificationPublishesNoImage() {
        val file = source("permission-loss.dng")
        val original = file.readBytes()
        val opens = AtomicInteger()
        PreviewCompareActivity.createComparison = { context ->
            PrivatePhotoComparison(context, openRead = {
                if (opens.incrementAndGet() == 2) throw SecurityException("synthetic revoked read permission")
                file.inputStream()
            })
        }
        try {
            ActivityScenario.launch(PreviewCompareActivity::class.java).use { scenario ->
                scenario.onActivity { select(it, file) }
                await(scenario) { (field(it, "status") as TextView).text.contains("続けられません") }
                assertEquals("Read and failed re-verification must both occur", 2, opens.get())
                scenario.onActivity {
                    assertNull(field(it, "pair"))
                    assertNull((field(it, "image") as ImageView).drawable)
                }
                assertReleased()
            }
            assertArrayEquals(original, file.readBytes())
        } finally {
            PreviewCompareActivity.createComparison = { PrivatePhotoComparison(it) }
            assertTrue(file.delete())
        }
    }
}
