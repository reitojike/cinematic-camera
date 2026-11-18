package dev.cinematic.p0.halfcompare

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.rawcapture.DngContentVerifier
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PerformanceComparisonAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun guards() {
        check(BuildConfig.DEBUG && BuildConfig.PRIVATE_HALF_COMPARE)
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("privateHalfSynthetic") == "true")
    }
    private fun fixture(width: Int = 2048, height: Int = 1536): File =
        File.createTempFile("performance-fixture-", ".dng", context.cacheDir).also {
            it.outputStream().use { out -> writeSyntheticTrialDng(out, width, height, 1) }
        }
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun start(activity: PerformanceCompareActivity) {
        descendants(activity.window.decorView).filterIsInstance<CheckBox>().single().isChecked = true
        PerformanceCompareActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(activity, 1, Activity.RESULT_OK, Intent().setData(Uri.parse("content://example.test/synthetic")))
    }
    private fun status(activity: PerformanceCompareActivity) = descendants(activity.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }.firstOrNull { it.startsWith("完了・") || it.startsWith("計測を完了できません") }
    private fun awaitIdle() {
        val end = System.nanoTime() + 40_000_000_000L
        while (System.nanoTime() < end) {
            try { ComparisonLease.acquire().close(); return } catch (_: IllegalStateException) { Thread.sleep(50) }
        }
        fail("Comparison owner did not release")
    }
    private fun assertClean() = assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("half-performance-") })

    @Test fun rejectsUnknownProvidersBeforeReading() {
        assertFalse(PerformanceInput.admits(Uri.parse("content://unknown.example/photo")))
        assertFalse(PerformanceInput.admits(Uri.parse("file:///synthetic.dng")))
        assertTrue(PerformanceInput.admits(Uri.parse("content://com.android.externalstorage.documents/document/primary:test.dng")))
        assertThrows(IllegalStateException::class.java) {
            PerformanceInput.prepare(context, Uri.parse("content://unknown.example/photo"), ComparisonStop())
        }
        assertClean()
    }
    @Test fun immutableCopyAndCancellationRelease() {
        val file = fixture()
        try {
            val fingerprint = DngContentVerifier.identify(file.length(), { file.inputStream() })
            ComparisonLease.acquire().use {
                PerformanceInput.prepare(context, Uri.EMPTY, ComparisonStop()) { file.inputStream() }.use { input ->
                    assertEquals(fingerprint, input.reference.fingerprint)
                    input.verifyOriginal()
                }
            }
            val stop = ComparisonStop().apply { cancel() }
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                ComparisonLease.acquire().use { PerformanceInput.prepare(context, Uri.EMPTY, stop) { file.inputStream() } }
            }
            DngContentVerifier.verify(fingerprint, { file.inputStream() })
            assertClean()
        } finally { assertTrue(file.delete()) }
    }
    @Test fun abbaTwelveAndTwentyFourMegapixelsThroughNormalSwap() {
        val sizes = if (InstrumentationRegistry.getArguments().getString("privateHalfMegapixels") == "12")
            listOf(4000 to 3000) else listOf(4000 to 3000, 6000 to 4000)
        for ((width, height) in sizes) {
            val file = fixture(width, height)
            val factory = PerformanceCompareActivity.createInput
            val observations = java.util.concurrent.CopyOnWriteArrayList<String>()
            PerformanceCompareActivity.observeSynthetic = { observations.add(it) }
            try {
                val fingerprint = DngContentVerifier.identify(file.length(), { file.inputStream() })
                PerformanceCompareActivity.createInput = { app, _, stop -> PerformanceInput.prepare(app, Uri.EMPTY, stop) { file.inputStream() } }
                ActivityScenario.launch(PerformanceCompareActivity::class.java).use { scenario ->
                    scenario.onActivity(::start)
                    val end = System.nanoTime() + 240_000_000_000L
                    var result: String? = null
                    while (result == null && System.nanoTime() < end) {
                        scenario.onActivity { result = status(it) }; Thread.sleep(100)
                    }
                    assertNotNull("No completion", result)
                    assertTrue(observations.joinToString(" | "), result!!.startsWith("完了・"))
                    assertEquals(4, Regex("OPEN: .* PRESENTED").findAll(result!!).count())
                    assertEquals(4, Regex("GPU_FIRST_FRAME: .* COMPLETED").findAll(result!!).count())
                    assertEquals(2, Regex("HALF_CANDIDATE thermal").findAll(result!!).count())
                    assertEquals(2, Regex("FULL thermal").findAll(result!!).count())
                }
                awaitIdle(); assertClean()
                assertEquals(listOf("EFFECT_FULL", "EFFECT_HALF_CANDIDATE", "EFFECT_HALF_CANDIDATE", "EFFECT_FULL"),
                    observations.filter { it.startsWith("EFFECT_") })
                DngContentVerifier.verify(fingerprint, { file.inputStream() })
            } finally { PerformanceCompareActivity.observeSynthetic = null; PerformanceCompareActivity.createInput = factory; assertTrue(file.delete()) }
        }
    }
    @Test fun backgroundWhileNativeRasterIsOwnedDrainsBeforeRetry() {
        val file = fixture(6000, 4000)
        val factory = PerformanceCompareActivity.createInput
        try {
            val fingerprint = DngContentVerifier.identify(file.length(), { file.inputStream() })
            PerformanceCompareActivity.createInput = { app, _, stop -> PerformanceInput.prepare(app, Uri.EMPTY, stop) { file.inputStream() } }
            ActivityScenario.launch(PerformanceCompareActivity::class.java).use { scenario ->
                scenario.onActivity(::start)
                val end = System.nanoTime() + 60_000_000_000L
                var owned = false
                while (!owned && System.nanoTime() < end) {
                    owned = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("half-performance-") }
                        .any { File(it, "session/source-linear.rgb16").exists() }
                    if (!owned) Thread.sleep(10)
                }
                assertTrue("Native raster ownership was not observed", owned)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                awaitIdle(); assertClean()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { assertNull(status(it)) }
            }
            DngContentVerifier.verify(fingerprint, { file.inputStream() })
        } finally { PerformanceCompareActivity.createInput = factory; assertTrue(file.delete()) }
    }

    @Test fun processDeathKnownFilesAreCleanedBeforeNextInput() {
        val source = fixture()
        val root = File(context.cacheDir, "half-performance-00000000-0000-0000-0000-000000000000")
        try {
            assertTrue(root.mkdir())
            val session = File(root, "session").apply { assertTrue(mkdir()) }
            File(root, "source.dng").writeText("synthetic")
            File(session, "source-snapshot.dng").writeText("synthetic")
            File(session, "source-linear.rgb16").writeText("synthetic")
            ComparisonLease.acquire().use {
                PerformanceInput.prepare(context, Uri.EMPTY, ComparisonStop()) { source.inputStream() }.use {
                    assertFalse(root.exists())
                }
            }
            assertClean()
        } finally { assertTrue(source.delete()) }
    }

    @Test fun backgroundDuringPreparationDrainsAndRejectsStaleResults() {
        val file = fixture()
        val factory = PerformanceCompareActivity.createInput
        val entered = java.util.concurrent.CountDownLatch(1)
        try {
            PerformanceCompareActivity.createInput = { app, _, stop ->
                entered.countDown()
                while (!stop.isStopped()) Thread.sleep(10)
                PerformanceInput.prepare(app, Uri.EMPTY, stop) { file.inputStream() }
            }
            ActivityScenario.launch(PerformanceCompareActivity::class.java).use { scenario ->
                scenario.onActivity(::start)
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                awaitIdle(); assertClean()
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                scenario.onActivity { assertNull(status(it)) }
            }
        } finally { PerformanceCompareActivity.createInput = factory; assertTrue(file.delete()) }
    }
}