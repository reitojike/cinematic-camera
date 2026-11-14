package dev.cinematic.p0.gpu

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Argument checks use simulated hardware strings and never open a GPU or a physical device. */
@RunWith(AndroidJUnit4::class)
class GpuTimingOptionsAndroidTest {
    private fun args() = Bundle().apply {
        putString("gpuPerformance", "true")
        putString("isolated", "true")
    }

    @Test fun defaultAndFrameBounds() {
        assertEquals(GpuTimingOptions(false, 3, true), GpuTimingOptions.parse(args(), "ranchu"))
        for (frames in listOf(1, 2, 3, 30)) {
            assertEquals(frames, GpuTimingOptions.parse(args().apply {
                putString("measuredFrames", frames.toString())
            }, "goldfish").measuredFrames)
        }
        for (value in listOf("", "0", "-1", "31", "1.5", "abc", " 3", "3 ", "03", "2147483648")) {
            assertThrows(IllegalStateException::class.java) {
                GpuTimingOptions.parse(args().apply { putString("measuredFrames", value) }, "ranchu")
            }
        }
    }

    @Test fun deviceOptInIsExplicitAndDoesNotBypassOtherGuards() {
        for (hardware in listOf("physical-test", "", "unknown")) {
            assertThrows(IllegalStateException::class.java) { GpuTimingOptions.parse(args(), hardware) }
            assertThrows(IllegalStateException::class.java) {
                GpuTimingOptions.parse(args().apply { putString("allowDevice", "false") }, hardware)
            }
            val accepted = GpuTimingOptions.parse(args().apply { putString("allowDevice", "true") }, hardware)
            assertTrue(accepted.allowDevice)
            assertFalse(accepted.emulator)
        }
        for (value in listOf("", "TRUE", "1", " true")) {
            assertThrows(IllegalStateException::class.java) {
                GpuTimingOptions.parse(args().apply { putString("allowDevice", value) }, "ranchu")
            }
        }
        for (key in listOf("gpuPerformance", "isolated")) {
            assertThrows(IllegalStateException::class.java) {
                GpuTimingOptions.parse(args().apply { putString("allowDevice", "true"); remove(key) }, "physical-test")
            }
        }
    }
}
