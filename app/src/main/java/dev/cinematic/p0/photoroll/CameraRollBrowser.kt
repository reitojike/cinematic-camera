package dev.cinematic.p0.photoroll

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.*

@Composable
internal fun CameraRollBrowser(listing: PhotoRollListing, access: CameraRollAccess, filter: PhotoRollKindFilter,
    folder: String?, loading: Boolean, opening: Boolean, notice: String?, mode: PhotoRollPreviewMode,
    enabled: Boolean, refreshEnabled: Boolean, permissionEnabled: Boolean, filesEnabled: Boolean,
    folderMenu: Boolean, setFolderMenu: (Boolean) -> Unit, close: () -> Unit, refresh: () -> Unit, chooseFolder: (String?) -> Unit, chooseFilter: (PhotoRollKindFilter) -> Unit,
    permission: () -> Unit, settings: () -> Unit, files: () -> Unit, more: () -> Unit,
    modifier: Modifier = Modifier, saveMessages: List<UiMessage> = emptyList(),
    saveAction: (MessageAction) -> Unit = {}, importEntry: (@Composable () -> Unit)? = null, select: (PhotoRollItem) -> Unit) {
    val messages = libraryMessages(access, notice, listing.message, !loading && listing.items.isEmpty(),
        permissionEnabled, filesEnabled, enabled, saveMessages)
    val accessActions = libraryAccessActions(access, permissionEnabled, enabled, messages.banner?.fact)
    val onMessageAction: (MessageAction) -> Unit = { action ->
        when (action) {
            MessageAction.GRANT_ACCESS, MessageAction.CHOOSE_AGAIN -> permission()
            MessageAction.OPEN_SETTINGS -> settings()
            MessageAction.OPEN_FILES -> files()
            else -> saveAction(action)
        }
    }
    var detail by remember { mutableStateOf(false) }
    val trial = when (mode) {
        PhotoRollPreviewMode.MANUAL_TRIAL -> "試験品質 · 手動保存 · 原本は変更しません"
        PhotoRollPreviewMode.PRIVATE_TRIAL -> "縮小試験 · 共有写真へは保存しません"
        else -> null
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val gridHeight = maxHeight.coerceAtLeast(240.dp)
    val compact = captureNeedsFullScroll(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale, 520f)
    Column(Modifier.fillMaxSize().background(CaptureDesign.Ink).then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CaptureTextAction(MessageControl(MessageAction.CLOSE, "‹ 撮影"), { close() })
            Text("カメラロール", Modifier.weight(1f), color = CaptureDesign.Text)
            CaptureTextAction(MessageControl(MessageAction.RETRY, "↻", refreshEnabled, description = "更新"), { refresh() })
        }
        trial?.let { CaptureTextAction(MessageControl(MessageAction.CONFIRM, it + if (detail) " ▴" else " ▾"),
            { detail = !detail }, tone = CaptureChromeTone.WARNING) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            PhotoRollKindFilter.entries.forEach { candidate ->
                TextButton(onClick = { chooseFilter(candidate) }, enabled = enabled, shape = RectangleShape,
                    modifier = Modifier.semantics { selected = filter == candidate }
                        .captureChoiceSurface(filter == candidate, unselectedBackground = CaptureDesign.Ink),
                    contentPadding = PaddingValues(horizontal = 10.dp), colors = ButtonDefaults.textButtonColors(
                        contentColor = if (filter == candidate) CaptureDesign.Ink else CaptureDesign.Muted)) {
                    Text(when (candidate) { PhotoRollKindFilter.ALL -> "すべて"; PhotoRollKindFilter.IMAGES -> "画像"
                        PhotoRollKindFilter.RAW -> "RAW"; PhotoRollKindFilter.VIDEOS -> "動画" }, style = CaptureDesign.Label)
                }
            }
            Box {
                TextButton(onClick = { setFolderMenu(true) }, enabled = enabled, shape = RectangleShape) {
                    Text((listing.folders.firstOrNull { it.key == folder }?.label ?: if (folder == null) "全フォルダ" else "選択フォルダ（確認中）") + " ▾",
                        style = CaptureDesign.Label)
                }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { setFolderMenu(false) }, modifier = Modifier.heightIn(max = 360.dp)) {
                    DropdownMenuItem(text = { Text("全フォルダ") }, onClick = { setFolderMenu(false); chooseFolder(null) })
                    listing.folders.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.label) }, onClick = { setFolderMenu(false); chooseFolder(candidate.key) }) }
                }
            }
        }
        if (loading || opening) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = CaptureDesign.Amber, trackColor = CaptureDesign.Line)
        val header: @Composable () -> Unit = {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detail) Text(if (mode == PhotoRollPreviewMode.MANUAL_TRIAL)
                    "DNGは最大24MPの試験品質で現像します。顔保護なし・診断露出・暫定色です。JPEG保存は手動のみで、原本は変更しません。"
                    else "DNGは最大1024pxの試験現像です。顔保護なし・暫定色で、共有写真へは保存しません", color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
                messages.banner?.let { CaptureBanner(it, onMessageAction, Modifier.fillMaxWidth()) }
                messages.inline.forEach { CaptureInlineNote(it, onMessageAction, Modifier.fillMaxWidth()) }
            }
        }
        val moreItems: @Composable () -> Unit = {
            if (listing.hasMore) CaptureTextAction(MessageControl(MessageAction.RETRY, "さらに読み込む", refreshEnabled), { more() })
        }
        val contentModifier = if (compact) Modifier.height(gridHeight) else Modifier.weight(1f)
        if (!loading && listing.items.isEmpty()) {
            BoxWithConstraints(contentModifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
                    header()
                    Box(Modifier.weight(1f).fillMaxWidth().heightIn(min = 240.dp).padding(24.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            messages.center?.let { CaptureCenter(it, onMessageAction) }
                            importEntry?.invoke()
                        }
                    }
                    moreItems()
                }
            }
        } else {
            CameraRollGrid(listing.items, enabled, contentModifier, header = header, footer = moreItems, select = select)
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).border(1.dp, CaptureDesign.Line).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(CameraRollKind.entries.joinToString(" · ") { kind -> "${kind.label} ${listing.items.count { it.kind == kind }}" } + if (listing.hasMore) "（読込済み）" else "",
                style = CaptureDesign.Label, color = CaptureDesign.Muted)
            Text("DNGはタップで現像 · 他は標準ビューア", style = CaptureDesign.Explanation, color = CaptureDesign.Muted)
            FlowRow {
                CaptureTextAction(MessageControl(MessageAction.OPEN_FILES, "Filesで開く", filesEnabled), onMessageAction)
                importEntry?.invoke()
                accessActions.forEach { CaptureTextAction(it, onMessageAction) }
            }
        }
    }
}
}
