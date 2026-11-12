package dev.cinematic.p0.photoroll

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.rawcapture.AndroidDngImports
import dev.cinematic.p0.rawcapture.AutoDevelopConditions

/** Separate from direct on-device selection. Only a confirmed shared URI reaches the editor. */
@Composable
internal fun DngImportPanel(foreground: Boolean, conditions: () -> AutoDevelopConditions,
    busyChanged: (Boolean) -> Unit, publications: kotlinx.coroutines.flow.StateFlow<dev.cinematic.p0.gpu.preview.GpuInputPublication>?,
    open: (String) -> Unit) {
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
    TextButton(onClick = { dialog = true; imports.refresh() }, enabled = foreground && !pickerPending) {
        Text(if (state.busy) "DNG取り込み中…" else "外部DNGを取り込む")
    }
    if (dialog) AlertDialog(
        onDismissRequest = { if (!state.busy && !pickerPending) { clearSelection(); dialog = false } },
        title = { Text("DNGを端末に取り込む") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("64MiB以下のDNGを共有フォルダへ変更せずコピーします。完了するまでSDを抜かないでください。選択元は変更しません。")
                Text("Google Photos等の設定によりバックアップされる場合があります。現像できないDNGも取り込み済み原本は保持します。")
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                preparationNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (selected != null && !state.busy) {
                    Text("取り込みの受付状態を確認中…")
                    TextButton(onClick = { clearSelection() }) { Text("取り込みを取り消す") }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { imports.cancel(null) { currentConditions() } }) { Text("取り込みを取り消す") }
                }
                state.items.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("取り込み ${index + 1}", modifier = Modifier.weight(1f))
                        if (!item.cancelled) TextButton(enabled = foreground && !state.busy, onClick = { imports.resume(item.id) { currentConditions() } }) {
                            Text(if (item.ready) "編集へ" else "再開")
                        }
                        if (item.grantPending || !item.ready) TextButton(enabled = foreground && !state.busy,
                            onClick = { imports.cancel(item.id) { currentConditions() } }) {
                            Text(if (item.grantPending) "権限を解放" else "取消")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = foreground && !state.busy && !pickerPending && selected == null, onClick = {
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
            }) { Text("DNGを選ぶ・選び直す") }
        },
        dismissButton = { TextButton(enabled = !state.busy && !pickerPending, onClick = { clearSelection(); dialog = false }) { Text("閉じる") } },
    )
}
