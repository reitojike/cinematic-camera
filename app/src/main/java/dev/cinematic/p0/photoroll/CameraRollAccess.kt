package dev.cinematic.p0.photoroll

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal fun cameraRollReadPermissions(api: Int): Array<String> = when {
    api >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    api >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    api >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> emptyArray()
}

/** Current OS grants only, never persisted as authority over a URI. */
internal data class CameraRollAccess(val supported: Boolean, val images: Boolean, val videos: Boolean, val selected: Boolean) {
    val description get() = when {
        !supported -> "撮影物の一覧はAndroid 10以降で利用できます"
        images && videos -> "写真・動画へのアクセスを許可済みです"
        selected -> when {
            images -> "写真は許可済みです。動画は選択した項目だけ表示します"
            videos -> "動画は許可済みです。写真は選択した項目だけ表示します"
            else -> "選択した写真・動画だけを表示します。追加や変更は「アクセスを許可・選び直す」から行えます"
        }
        images -> "写真は許可済みです。動画はアクセス可能な項目だけ表示します"
        videos -> "動画は許可済みです。写真はアクセス可能な項目だけ表示します"
        else -> "他アプリの写真・動画へのアクセスは未許可です。現在読める撮影物だけ表示します"
    }
}

internal fun currentCameraRollAccess(context: Context): CameraRollAccess {
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val api = Build.VERSION.SDK_INT
    val legacy = api in 29..32 && granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    return CameraRollAccess(api >= 29,
        legacy || (api >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES)),
        legacy || (api >= 33 && granted(Manifest.permission.READ_MEDIA_VIDEO)),
        api >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
}
