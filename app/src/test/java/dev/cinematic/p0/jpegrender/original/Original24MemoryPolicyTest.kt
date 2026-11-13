package dev.cinematic.p0.jpegrender.original

import org.junit.Assert.*
import org.junit.Test

class Original24MemoryPolicyTest {
    @Test fun onlyConfirmedOsPressureControlsAdmissionNotFixedBytes() {
        for (stage in Original24MemoryStage.entries) {
            for (bytes in listOf(null, -1L, 0L, 1L, 128L * 1024 * 1024,
                768L * 1024 * 1024 - 1, 768L * 1024 * 1024, Long.MAX_VALUE)) {
                assertTrue(Original24MemoryPolicy.permits(stage, false, bytes))
                assertFalse(Original24MemoryPolicy.permits(stage, true, bytes))
                assertFalse(Original24MemoryPolicy.permits(stage, null, bytes))
            }
        }
    }
}
