package dev.cinematic.p0.photoroll

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Optional notification permission is separate from the save request and cannot restart it. */
@Composable
internal fun ForegroundSaveNotice(owner: PhotoRollJpegSaveOwner?, foreground: Boolean) {
    val context = LocalContext.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionEpoch++ }
    val notificationsAllowed = remember(permissionEpoch, foreground) {
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    if (owner?.supportsForegroundSave != true) return
    Text("保存はアプリを離れても継続します。撮影開始時は中断し、自動再開しません。")
    if (owner.canCancelForegroundSave) {
        TextButton(onClick = { owner.cancelForegroundSave() }, enabled = foreground) { Text("保存を取り消す") }
    }
    if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
        Text("保存通知は無効です。保存の取り消しはこの画面から行えます。")
        TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, enabled = foreground) {
            Text("保存通知を許可")
        }
    }
}
