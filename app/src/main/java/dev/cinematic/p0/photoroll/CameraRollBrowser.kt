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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.settingsOutline

@Composable
internal fun CameraRollBrowser(listing: PhotoRollListing, access: CameraRollAccess, filter: PhotoRollKindFilter,
    folder: String?, loading: Boolean, opening: Boolean, notice: String?, mode: PhotoRollPreviewMode,
    enabled: Boolean, refreshEnabled: Boolean, permissionEnabled: Boolean, filesEnabled: Boolean,
    folderMenu: Boolean, setFolderMenu: (Boolean) -> Unit, close: () -> Unit, refresh: () -> Unit, chooseFolder: (String?) -> Unit, chooseFilter: (PhotoRollKindFilter) -> Unit,
    permission: () -> Unit, settings: () -> Unit, files: () -> Unit, more: () -> Unit,
    modifier: Modifier = Modifier, select: (PhotoRollItem) -> Unit) {
    var detail by remember { mutableStateOf(false) }
    val trial = when (mode) {
        PhotoRollPreviewMode.MANUAL_TRIAL -> "試験品質 · 手動保存 · 原本は変更しません"
        PhotoRollPreviewMode.PRIVATE_TRIAL -> "縮小試験 · 共有写真へは保存しません"
        else -> null
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val gridHeight = maxHeight.coerceAtLeast(240.dp)
    val compact = maxHeight < (520 * LocalDensity.current.fontScale).dp || maxWidth < (360 * LocalDensity.current.fontScale).dp
    Column(Modifier.fillMaxSize().background(CaptureDesign.Ink).then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close, shape = RectangleShape) { Text("‹ 撮影") }
            Text("カメラロール", Modifier.weight(1f), color = CaptureDesign.Text)
            TextButton(onClick = refresh, enabled = refreshEnabled, modifier = Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics { contentDescription = "更新" }, shape = RectangleShape) { Text("↻") }
        }
        trial?.let { TextButton(onClick = { detail = !detail }, shape = RectangleShape) {
            Text(it + if (detail) " ▴" else " ▾", style = CaptureDesign.Explanation, color = CaptureDesign.Amber)
        } }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            PhotoRollKindFilter.entries.forEach { candidate ->
                TextButton(onClick = { chooseFilter(candidate) }, enabled = enabled, shape = RectangleShape,
                    modifier = Modifier.semantics { selected = filter == candidate }
                        .background(if (filter == candidate) CaptureDesign.Inverted else CaptureDesign.Ink),
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
        CameraRollGrid(listing.items, enabled, if (compact) Modifier.height(gridHeight) else Modifier.weight(1f), header = {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detail) Text(if (mode == PhotoRollPreviewMode.MANUAL_TRIAL)
                    "DNGは最大24MPの試験品質で現像します。顔保護なし・診断露出・暫定色です。JPEG保存は手動のみで、原本は変更しません。"
                    else "DNGは最大1024pxの試験現像です。顔保護なし・暫定色で、共有写真へは保存しません", color = CaptureDesign.Amber, style = CaptureDesign.Explanation)
                Column(Modifier.fillMaxWidth().settingsOutline(access.selected && !(access.images && access.videos)).padding(8.dp)) {
                    Text(if (access.selected && !access.images && !access.videos) "選択した写真・動画だけにアクセスできます" else access.description,
                        style = CaptureDesign.Explanation, color = CaptureDesign.Muted)
                    Row {
                        TextButton(onClick = permission, enabled = permissionEnabled, shape = RectangleShape) { Text("アクセス許可・選び直す", style = CaptureDesign.Explanation) }
                        TextButton(onClick = settings, enabled = enabled, shape = RectangleShape) { Text("設定", style = CaptureDesign.Explanation) }
                    }
                }
                notice?.let { Text(it, color = CaptureDesign.Failed, style = CaptureDesign.Explanation) }
                listing.message?.let { Text(it, color = CaptureDesign.Muted, style = CaptureDesign.Explanation) }
                if (!loading && listing.items.isEmpty()) Text("該当する項目がありません。アクセス許可や絞り込みを確認してください。DNGはFilesの「内部ストレージ」からも選べます",
                    color = CaptureDesign.Text, style = CaptureDesign.Explanation)
            }
        }, footer = {
            if (listing.hasMore) TextButton(onClick = more, enabled = refreshEnabled, shape = RectangleShape) { Text("さらに読み込む") }
        }, select = select)
        Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).border(1.dp, CaptureDesign.Line).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(CameraRollKind.entries.joinToString(" · ") { kind -> "${kind.label} ${listing.items.count { it.kind == kind }}" } + if (listing.hasMore) "（読込済み）" else "",
                style = CaptureDesign.Label, color = CaptureDesign.Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DNGはタップで現像\n他は標準ビューア", Modifier.weight(1f), style = CaptureDesign.Explanation, color = CaptureDesign.Muted)
                TextButton(onClick = files, enabled = filesEnabled, shape = RectangleShape) { Text("Filesから DNG…", style = CaptureDesign.Explanation) }
            }
        }
    }
}
}
