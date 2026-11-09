package dev.cinematic.p0.jpegrender.original

import org.junit.Assert.*
import org.junit.Test

class Original24MemoryPolicyTest {
    @Test fun boundedWorkDoesNotRequireTheFullOriginalAllowance() {
        for (mib in listOf(256L, 383L, 384L, 511L, 512L, 767L)) {
            assertTrue(Original24MemoryPolicy.permits(Original24MemoryStage.BOUNDED_PREVIEW, false, mib * 1024 * 1024))
            assertFalse(Original24MemoryPolicy.permits(Original24MemoryStage.ORIGINAL_DECODE, false, mib * 1024 * 1024))
            assertFalse(Original24MemoryPolicy.permits(Original24MemoryStage.ORIGINAL_SAVE, false, mib * 1024 * 1024))
        }
    }

    @Test fun fullDecodeAndSaveKeepTheirExistingBoundary() {
        for (stage in listOf(Original24MemoryStage.ORIGINAL_DECODE, Original24MemoryStage.ORIGINAL_SAVE)) {
            assertFalse(Original24MemoryPolicy.permits(stage, false, Original24MemoryPolicy.ORIGINAL_REQUIRED_BYTES - 1))
            assertTrue(Original24MemoryPolicy.permits(stage, false, Original24MemoryPolicy.ORIGINAL_REQUIRED_BYTES))
        }
    }

    @Test fun lowOrUnknownMemoryCannotAdmitAnyStage() {
        for (stage in Original24MemoryStage.entries) {
            for (low in listOf(true, null)) assertFalse(Original24MemoryPolicy.permits(stage, low, Long.MAX_VALUE))
            for (bytes in listOf(null, 0L, -1L)) assertFalse(Original24MemoryPolicy.permits(stage, false, bytes))
        }
    }
}
