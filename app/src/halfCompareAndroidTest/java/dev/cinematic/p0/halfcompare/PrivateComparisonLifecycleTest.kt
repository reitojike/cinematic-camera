package dev.cinematic.p0.halfcompare

import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateComparisonLifecycleTest {
    @Before fun emulatorOnly() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("privateHalfSynthetic") == "true")
    }
    @Test fun selectedExposureSurvivesRecreation() {
        ActivityScenario.launch(PreviewCompareActivity::class.java).use { scenario ->
            val field = PreviewCompareActivity::class.java.getDeclaredField("selectedEv").apply { isAccessible = true }
            scenario.onActivity { field.setDouble(it, -2.0) }
            scenario.recreate()
            scenario.onActivity { assertEquals(-2.0, field.getDouble(it), 0.0) }
        }
    }
    @Test fun backgroundInvalidatesGenerationAndReleasesDisplayedPair() {
        val a = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        lateinit var generation: CompareGeneration
        var ticket = 0L
        ActivityScenario.launch(PreviewCompareActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val field = PreviewCompareActivity::class.java.getDeclaredField("pair").apply { isAccessible = true }
                field.set(activity, PrivatePhotoComparison.Pair(a, b))
                val image = PreviewCompareActivity::class.java.getDeclaredField("image").apply { isAccessible = true }
                    .get(activity) as android.widget.ImageView
                image.setImageBitmap(a)
                generation = PreviewCompareActivity::class.java.getDeclaredField("generation").apply { isAccessible = true }
                    .get(activity) as CompareGeneration
                ticket = generation.next()
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(generation.accepts(ticket))
            assertTrue(a.isRecycled)
            assertTrue(b.isRecycled)
        }
    }
}
