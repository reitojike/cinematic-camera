package dev.cinematic.p0.photoroll

import org.junit.Assert.*
import org.junit.Test

class CameraRollAccessTest {
    @Test fun requestsOnlyVersionAppropriateVisualReadAccess() {
        assertTrue(cameraRollReadPermissions(28).isEmpty())
        assertArrayEquals(arrayOf("android.permission.READ_EXTERNAL_STORAGE"), cameraRollReadPermissions(32))
        assertArrayEquals(arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"), cameraRollReadPermissions(33))
        assertArrayEquals(arrayOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"), cameraRollReadPermissions(34))
        for (api in listOf(29, 33, 34, 37)) assertFalse(cameraRollReadPermissions(api).any {
            it.contains("WRITE") || it.contains("AUDIO") || it.contains("LOCATION") || it.contains("MANAGE")
        })
    }

    @Test fun neverDescribesSelectedOrOneTypeGrantAsFullAccess() {
        assertTrue(CameraRollAccess(true, false, false, true).description.contains("選択した"))
        assertTrue(CameraRollAccess(true, true, false, true).description.contains("動画は選択した"))
        assertTrue(CameraRollAccess(true, false, true, false).description.contains("写真はアクセス可能"))
        assertTrue(CameraRollAccess(true, false, false, false).description.contains("未許可"))
        assertTrue(CameraRollAccess(false, false, false, false).description.contains("Android 10"))
    }
}
