package dev.cinematic.p0.halfcompare

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import java.io.File
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

/** Run alone in a fresh instrumentation process: a real blocked lease must not be reset for a test. */
class PerformanceCleanupFailureAndroidTest {
    @Test fun preparationCleanupFailureRetainsUnknownFileAndBlocksBothEntrypoints() {
        check(BuildConfig.DEBUG && BuildConfig.PRIVATE_HALF_COMPARE)
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("privateHalfSynthetic") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        var obstacle: File? = null
        try {
            assertThrows(IllegalStateException::class.java) {
                ComparisonLease.acquire().use {
                    PerformanceInput.prepare(app, Uri.EMPTY, ComparisonStop()) {
                        val root = app.cacheDir.listFiles()!!.single { it.name.startsWith("half-performance-") }
                        obstacle = File(root, "test-owned-obstacle").also { it.writeText("synthetic") }
                        object : InputStream() { override fun read(): Int = throw java.io.IOException("synthetic read failure") }
                    }
                }
            }
            assertTrue(ComparisonLease.blocked)
            assertTrue(obstacle!!.exists())
            assertThrows(IllegalStateException::class.java) { ComparisonLease.acquire() }
            assertThrows(IllegalStateException::class.java) {
                PrivatePhotoComparison(app).compare(Uri.EMPTY, ComparisonStop(), 0.0) {}
            }
            assertTrue(obstacle!!.exists())
        } finally {
            obstacle?.let { assertTrue(it.delete()); assertTrue(it.parentFile!!.delete()) }
        }
    }
}