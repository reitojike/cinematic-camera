package dev.cinematic.p0.photoroll

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.cinematic.p0.MessageAction

internal data class ForegroundSaveMessageBinding(
    val state: ForegroundSaveMessageState,
    val onAction: (MessageAction) -> Unit,
)

/** The original process subscription, permission refresh and identity check, isolated from drawing. */
@Composable
internal fun rememberForegroundSaveMessages(
    owner: PhotoRollJpegSaveOwner?, foreground: Boolean,
): ForegroundSaveMessageBinding {
    if (Build.VERSION.SDK_INT < 29) return ForegroundSaveMessageBinding(
        ForegroundSaveMessageState(false, false, false, false, foreground, true, false), {},
    )
    val epoch by ForegroundExports.changes.collectAsState()
    val active = remember(epoch) { ForegroundExports.gate.active() }
    val context = LocalContext.current
    var permissionEpoch by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionEpoch++ }
    val notificationsAllowed = remember(permissionEpoch, foreground) {
        Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    return ForegroundSaveMessageBinding(
        ForegroundSaveMessageState(owner?.supportsForegroundSave == true, active != null,
            active?.held == true, active?.stopRequested == true, foreground, notificationsAllowed,
            Build.VERSION.SDK_INT >= 33),
    ) { action ->
        when (action) {
            MessageAction.CANCEL_SAVE -> if (active != null && ForegroundExports.gate.active() === active) {
                ForegroundExports.cancel(active.value)
            }
            MessageAction.ALLOW_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> Unit
        }
    }
}
