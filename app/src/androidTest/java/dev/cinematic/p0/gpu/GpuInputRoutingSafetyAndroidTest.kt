package dev.cinematic.p0.gpu

import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Ordinary routing and capability-cache contract; no diagnostic settings View or real GL probe. */
class GpuInputRoutingSafetyAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun ordinaryPolicyCannotReadOrWriteLegacySelectionOrAdmitAChoice() {
        requireGpuInputSafetyFixture()
        assertFalse(developEngineSettingVisible(debug = true, buildType = "debug"))
        assertFalse(developEngineSettingVisible(debug = false, buildType = "release"))
        for (buildType in listOf("debug", "release")) {
            val setting = DevelopEngineSetting({ error("Ordinary policy must not read legacy selection") },
                { error("Ordinary policy must not write legacy selection") },
                developEngineSettingVisible(buildType == "debug", buildType), defaultDevelopEngine(buildType))
            assertFalse(setting.visible)
            assertEquals(DevelopEngine.GPU, setting.current())
            assertEquals(DevelopEngine.GPU, setting.beginExport())
            for (availability in listOf(GpuAvailability.Checking, GpuAvailability.Available,
                GpuAvailability.Unavailable("synthetic"))) {
                for (engine in DevelopEngine.entries) assertFalse(setting.select(engine, availability))
            }
        }
    }

    @Test fun capabilityResultIsSharedAcrossConsumersUntilExplicitRefresh() = runBlocking {
        requireGpuInputSafetyFixture()
        var calls = 0
        val unavailable = GpuAvailability.Unavailable("synthetic unavailable")
        val cache = DevelopGpuAvailabilityCache {
            calls++
            if (calls == 1) GpuAvailability.Available else unavailable
        }
        assertSame(GpuAvailability.Available, cache.get())
        repeat(10) { assertSame(GpuAvailability.Available, cache.get()) }
        assertEquals(1, calls)
        assertSame(unavailable, cache.get(forceRefresh = true))
        repeat(10) { assertSame(unavailable, cache.get()) }
        assertEquals(2, calls)
    }
}
