package dev.cinematic.p0.photoroll

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundExportTypeTest {
    @Test fun runtimeTypeUsesOnlySupportedMediaCategory() {
        for (api in listOf(29, 33, 34)) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foregroundExportType(api))
        for (api in listOf(35, 36, 37)) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, foregroundExportType(api))
    }
}
