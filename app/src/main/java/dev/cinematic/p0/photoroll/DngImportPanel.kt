package dev.cinematic.p0.photoroll

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.cinematic.p0.*
import dev.cinematic.p0.rawcapture.AndroidDngImports
import dev.cinematic.p0.rawcapture.AutoDevelopConditions

/** Separate from direct on-device selection. Only a confirmed shared URI reaches the editor. */
@Composable
internal fun DngImportPanel(foreground: Boolean, conditions: () -> AutoDevelopConditions,
    busyChanged: (Boolean) -> Unit, publications: kotlinx.coroutines.flow.StateFlow<dev.cinematic.p0.gpu.preview.GpuInputPublication>?,
    open: (String) -> Unit,
    content: @Composable (entry: @Composable () -> Unit) -> Unit) {
    val app = LocalContext.current.applicationContext
    val imports = remember(app) { AndroidDngImports.get(app) }
    val state by imports.state.collectAsState()
    val currentConditions by rememberUpdatedState(conditions)
    var dialog by rememberSaveable { mutableStateOf(false) }
    var pickerPending by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFlags by rememberSaveable { mutableIntStateOf(0) }
    var selectionRevision by remember { mutableLongStateOf(0) }
    fun clearSelection() { selectionRevision++; selected = null }
    val currentForeground by rememberUpdatedState(foreground)
    var preparationNotice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerPending = false
        if (result.resultCode == Activity.RESULT_OK) {
            selectionRevision++
            selected = result.data?.data?.toString()
            selectedFlags = result.data?.flags ?: 0
        }
    }
    LaunchedEffect(Unit) { imports.refresh() }
    LaunchedEffect(state.busy, pickerPending, selected) { busyChanged(state.busy || pickerPending || selected != null) }
    DisposableEffect(imports) { onDispose { imports.pause(); busyChanged(false) } }
    LaunchedEffect(foreground) { if (!foreground) imports.pause() }
    LaunchedEffect(foreground, selected, selectionRevision, state.busy) {
        val uri = selected
        val revision = selectionRevision
        if (foreground && uri != null && !state.busy) {
            preparationNotice = null
            try {
                if (publications == null) {
                    preparationNotice = "取り込みの受付状態を確認できません。DNGを選び直してください"
                    return@LaunchedEffect
                }
                if (awaitDngImportObservation(publications, stillSelected = {
                    selected == uri && selectionRevision == revision && currentForeground && !imports.state.value.busy
                })) imports.select(android.net.Uri.parse(uri), selectedFlags) { currentConditions() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                preparationNotice = "取り込みの受付状態を確認できません。DNGを選び直してください"
            } finally {
                // Cancellation/close/background must not dispatch a stale selected source later.
                if (selected == uri && selectionRevision == revision) clearSelection()
            }
        }
    }
    LaunchedEffect(foreground, state.busy, state.readyUri) {
        val uri = state.readyUri
        if (foreground && !state.busy && uri != null) {
            if (imports.consumeReady(uri)) open(uri)
        }
    }
    content {
        CaptureTextAction(MessageControl(MessageAction.CONFIRM, "取り込む", foreground && !pickerPending),
            { dialog = true; imports.refresh() })
    }
    fun closeDialog() { clearSelection(); dialog = false }
    fun selectDng() {
        preparationNotice = null
        pickerPending = true
        try {
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        } catch (_: Exception) { pickerPending = false }
    }
    if (dialog) ImportDialogView(state, preparationNotice, selected != null, pickerPending, foreground,
        close = ::closeDialog, select = ::selectDng, cancelPreparation = ::clearSelection,
        cancel = { imports.cancel(null) { currentConditions() } },
        resume = { id -> imports.resume(id) { currentConditions() } },
        cancelItem = { id -> imports.cancel(id) { currentConditions() } })
}
