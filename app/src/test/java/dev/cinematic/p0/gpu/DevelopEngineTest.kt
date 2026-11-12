package dev.cinematic.p0.gpu

import dev.cinematic.p0.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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

    @Test fun hiddenCpuDiagnosticDoesNotExposeOrActivateAPersistedGpuChoice() {
        val store = Store("GPU")
        val setting = store.setting(visible = false)
        assertFalse(setting.visible)
        assertEquals(DevelopEngine.CPU, setting.current())
        assertEquals(DevelopEngine.CPU, setting.beginExport())
        assertFalse(setting.select(DevelopEngine.GPU, GpuAvailability.Available))
        assertFalse(setting.select(DevelopEngine.CPU, GpuAvailability.Available))
        assertEquals("GPU", store.value)
    }

    @Test fun onlyOrdinaryBuildsDefaultToGpu() {
        for (type in listOf("debug", "release")) assertEquals(DevelopEngine.GPU, defaultDevelopEngine(type))
        for (type in listOf("gpuDiagnostic", "rawDiagnostic", "previewDiagnostic", "original24Probe", "future"))
            assertEquals(DevelopEngine.CPU, defaultDevelopEngine(type))
    }

    @Test fun ordinaryGpuPolicyNeverReadsOrWritesDiagnosticPreferences() {
        val setting = DevelopEngineSetting({ error("Must not read diagnostic preference") },
            { error("Must not write diagnostic preference") }, false, DevelopEngine.GPU)
        assertEquals(DevelopEngine.GPU, setting.current())
        assertEquals(DevelopEngine.GPU, setting.beginExport())
        assertFalse(setting.visible)
        for (engine in DevelopEngine.entries) assertFalse(setting.select(engine, GpuAvailability.Available))
    }

    @Test fun settingIsRestrictedByBuildType() {
        assertFalse(developEngineSettingVisible(true, "debug"))
        assertTrue(developEngineSettingVisible(false, "gpuDiagnostic"))
        for (type in listOf("release", "rawDiagnostic", "previewDiagnostic")) {
            assertFalse(developEngineSettingVisible(false, type))
        }
        assertEquals(BuildConfig.BUILD_TYPE == "gpuDiagnostic",
            developEngineSettingVisible(BuildConfig.DEBUG, BuildConfig.BUILD_TYPE))
    }

    @Test fun completedCapabilityIsSharedAcrossReaders() = runBlocking {
        var calls = 0
        val cache = DevelopGpuAvailabilityCache { calls++; GpuAvailability.Available }
        repeat(3) { assertEquals(GpuAvailability.Available, cache.get()) }
        assertEquals(1, calls)
    }

    @Test fun unavailableIsCachedUntilExplicitRetryReplacesIt() = runBlocking {
        var calls = 0
        val unavailable = GpuAvailability.Unavailable("synthetic unsupported")
        val cache = DevelopGpuAvailabilityCache {
            if (++calls == 1) unavailable else GpuAvailability.Available
        }
        assertEquals(unavailable, cache.get())
        assertEquals(unavailable, cache.get())
        assertEquals(1, calls)
        assertEquals(GpuAvailability.Available, cache.get(forceRefresh = true))
        assertEquals(GpuAvailability.Available, cache.get())
        assertEquals(2, calls)
    }

    @Test fun concurrentReadersRunOnlyOneInspection() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cache = DevelopGpuAvailabilityCache {
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            GpuAvailability.Available
        }
        val first = async(Dispatchers.Default) { cache.get() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { cache.get() }
            release.countDown()
            assertEquals(GpuAvailability.Available, first.await())
            assertEquals(GpuAvailability.Available, second.await())
            assertEquals(1, calls.get())
        } finally { release.countDown() }
    }

    @Test fun checkedAndRuntimeExceptionsBecomeSanitizedUnavailableResults() = runBlocking {
        for (failure in listOf(IOException("private-input"), IllegalStateException("private-input"))) {
            val cache = DevelopGpuAvailabilityCache { throw failure }
            val result = cache.get()
            assertTrue(result is GpuAvailability.Unavailable)
            assertEquals("GPU の能力を確認できません。再試行してください",
                (result as GpuAvailability.Unavailable).reason)
            assertFalse(result.reason.contains("private-input"))
            assertEquals(result, cache.get())
        }
    }

    @Test fun knownGpuFailureKeepsItsCommonPolicyReason() = runBlocking {
        val failure = GpuContextLostException("private-input")
        val result = DevelopGpuAvailabilityCache { throw failure }.get()
        assertEquals(GpuAvailability.Unavailable(requireNotNull(gpuFailurePolicy(failure)).reason), result)
    }

    @Test fun cancellationIsRethrownAndNeverCached() = runBlocking {
        var calls = 0
        val cancelled = CancellationException("synthetic cancellation")
        val cache = DevelopGpuAvailabilityCache {
            if (++calls == 1) throw cancelled else GpuAvailability.Available
        }
        try {
            cache.get()
            fail("Cancellation must not become unavailable")
        } catch (actual: CancellationException) {
            assertEquals(cancelled.javaClass, actual.javaClass)
            // Coroutine stacktrace recovery may copy the exception across withContext.
            // Require the original exception itself or its identity as the recovered copy's cause.
            assertSame(cancelled, if (actual === cancelled) actual else actual.cause)
        }
        assertEquals(GpuAvailability.Available, cache.get())
        assertEquals(2, calls)
    }

    @Test fun cancelledOwnerCannotCacheALateInspectionResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val fresh = GpuAvailability.Unavailable("fresh result")
        val cache = DevelopGpuAvailabilityCache {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                GpuAvailability.Available
            } else fresh
        }
        val owner = launch(Dispatchers.Default) { cache.get() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owner.cancel()
            release.countDown()
            owner.cancelAndJoin()
            assertEquals(fresh, cache.get())
            assertEquals(2, calls.get())
        } finally { release.countDown() }
    }

    @Test fun retryRecoversFromCheckedExceptionWithoutCachingTheException() = runBlocking {
        var calls = 0
        val cache = DevelopGpuAvailabilityCache {
            if (++calls == 1) throw IOException("private-input") else GpuAvailability.Available
        }
        assertTrue(cache.get() is GpuAvailability.Unavailable)
        assertEquals(GpuAvailability.Available, cache.get(forceRefresh = true))
        assertEquals(GpuAvailability.Available, cache.get())
        assertEquals(2, calls)
    }
}
