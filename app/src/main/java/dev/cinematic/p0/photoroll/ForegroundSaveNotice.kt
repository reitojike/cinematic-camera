package dev.cinematic.p0.photoroll

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Optional notification permission is separate from the save request and cannot restart it. */
@Composable
internal fun ForegroundSaveNotice(owner: PhotoRollJpegSaveOwner?, foreground: Boolean) {
    if (Build.VERSION.SDK_INT < 29) return
    val epoch by ForegroundExports.changes.collectAsState()
    val active = remember(epoch) { ForegroundExports.gate.active() }
    val context = LocalContext.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionEpoch++ }
    val notificationsAllowed = remember(permissionEpoch, foreground) {
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    if (owner?.supportsForegroundSave != true && active == null) return
    Column(Modifier.fillMaxWidth().heightIn(max = 144.dp).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text(when {
            active?.held == true -> "保存処理の終了を確認できないため停止中です。次の保存は開始しません。"
            active?.stopRequested == true -> "保存の停止処理中です。資源の解放を確認しています。"
            active != null -> "JPEGを保存中です。アプリを離れても継続します。"
            else -> "保存はアプリ切替後も継続します。撮影開始時は中断し、自動再開しません。"
        })
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            if (active != null) TextButton(onClick = {
                if (ForegroundExports.gate.active() === active) ForegroundExports.cancel(active.value)
            }, enabled = foreground && !active.stopRequested) { Text("保存を取り消す") }
            if (!notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, enabled = foreground) {
                    Text("保存通知を許可")
                }
            }
        }
        if (!notificationsAllowed) Text("保存通知は無効です。取消はこの画面から行えます。")
    }
}
