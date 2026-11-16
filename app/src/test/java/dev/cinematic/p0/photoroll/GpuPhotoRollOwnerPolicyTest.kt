package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class GpuPhotoRollOwnerPolicyTest {
    @Test fun ordinaryNavigationRetainsOnlyContext() {
        assertFalse(destroyPreviewContext(GpuPreviewExit.PHOTO))
        assertFalse(destroyPreviewContext(GpuPreviewExit.BACKGROUND))
    }
    @Test fun memoryAndActivityExitDestroyContext() {
        assertTrue(destroyPreviewContext(GpuPreviewExit.MEMORY_PRESSURE))
        assertTrue(destroyPreviewContext(GpuPreviewExit.ACTIVITY_DESTROYED))
    }
}
