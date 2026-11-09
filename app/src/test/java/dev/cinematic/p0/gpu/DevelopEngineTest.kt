package dev.cinematic.p0.gpu

import dev.cinematic.p0.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class DevelopEngineTest {
    private class Store(var value: String = "") {
        fun setting(visible: Boolean = true) = DevelopEngineSetting({ value }, { value = it }, visible)
    }

    @Test fun defaultsAndUnknownValuesRemainCpu() {
        for (value in listOf("", "CPU", "future-value")) {
            assertEquals(DevelopEngine.CPU, Store(value).setting().current())
        }
    }

    @Test fun previewAndExportReadTheSamePersistedSetting() {
        val store = Store()
        val preview = store.setting()
        val export = store.setting()
        assertEquals(DevelopEngine.CPU, preview.current())
        assertEquals(preview.current(), export.beginExport())
        assertTrue(preview.select(DevelopEngine.GPU, GpuAvailability.Available))
        assertEquals("GPU", store.value)
        assertEquals(DevelopEngine.GPU, preview.current())
        assertEquals(preview.current(), export.beginExport())
        assertEquals(DevelopEngine.GPU, store.setting().current())
    }

    @Test fun anExportKeepsItsStartValueAcrossSettingChanges() {
        val setting = Store().setting()
        val cpuExport = setting.beginExport()
        setting.select(DevelopEngine.GPU, GpuAvailability.Available)
        val gpuExport = setting.beginExport()
        setting.select(DevelopEngine.CPU, GpuAvailability.Available)
        assertEquals(DevelopEngine.CPU, cpuExport)
        assertEquals(DevelopEngine.GPU, gpuExport)
        assertEquals(DevelopEngine.CPU, setting.beginExport())
    }

    @Test fun checkingAndUnavailableCannotSelectGpuButCanSelectCpu() {
        for (availability in listOf(GpuAvailability.Checking, GpuAvailability.Unavailable("synthetic"))) {
            val store = Store()
            val setting = store.setting()
            assertFalse(setting.select(DevelopEngine.GPU, availability))
            assertEquals("", store.value)
            assertEquals(DevelopEngine.CPU, setting.current())
            assertTrue(setting.select(DevelopEngine.CPU, availability))
        }
    }

    @Test fun lossOfCapabilityDoesNotSilentlyRewriteAnExistingGpuChoice() {
        val store = Store("GPU")
        val setting = store.setting()
        assertFalse(setting.select(DevelopEngine.GPU, GpuAvailability.Unavailable("synthetic")))
        assertEquals("GPU", store.value)
        assertEquals(DevelopEngine.GPU, setting.current())
    }

    @Test fun releaseDoesNotExposeOrActivateAPersistedGpuChoice() {
        val store = Store("GPU")
        val setting = store.setting(visible = false)
        assertFalse(setting.visible)
        assertEquals(DevelopEngine.CPU, setting.current())
        assertEquals(DevelopEngine.CPU, setting.beginExport())
        assertFalse(setting.select(DevelopEngine.GPU, GpuAvailability.Available))
        assertFalse(setting.select(DevelopEngine.CPU, GpuAvailability.Available))
        assertEquals("GPU", store.value)
    }

    @Test fun settingIsRestrictedByBuildType() {
        assertTrue(developEngineSettingVisible(true, "debug"))
        assertTrue(developEngineSettingVisible(false, "gpuDiagnostic"))
        for (type in listOf("release", "rawDiagnostic", "previewDiagnostic")) {
            assertFalse(developEngineSettingVisible(false, type))
        }
        assertEquals(BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "gpuDiagnostic",
            developEngineSettingVisible(BuildConfig.DEBUG, BuildConfig.BUILD_TYPE))
    }
}
