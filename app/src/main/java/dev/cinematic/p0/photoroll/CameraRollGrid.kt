package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import dev.cinematic.p0.CaptureDesign
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// 可視セルだけ読み込み、画面再生成をまたいでもサムネイル取得は最大2件にする。
private val thumbnailSlots = Semaphore(2)

@Composable
internal fun CameraRollGrid(items: List<PhotoRollItem>, foreground: Boolean, modifier: Modifier,
    header: @Composable () -> Unit = {}, footer: @Composable () -> Unit = {},
    select: (PhotoRollItem) -> Unit) {
    val groups = remember(items) { cameraRollDateGroups(items) }
    val latest = items.maxByOrNull { it.addedSeconds }?.takeIf { it.addedSeconds > 0 }?.uri
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item(key = "controls", span = { GridItemSpan(maxLineSpan) }) { header() }
        groups.forEach { group ->
            item(key = "date:${group.label}", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(group.label, Modifier.weight(1f), style = CaptureDesign.Label, color = CaptureDesign.Muted)
                    Text("${group.items.size}", style = CaptureDesign.Label, color = CaptureDesign.Muted)
                }
            }
            items(group.items, key = { it.uri }) { item ->
                Box(Modifier.semantics { contentDescription = item.name }.clickable(enabled = foreground, role = Role.Button,
                    onClickLabel = if (item.kind == CameraRollKind.DNG) "DNGを現像" else "${item.kind.label}を開く") { select(item) }) {
                    CameraRollThumbnail(item, foreground)
                    if (item.uri == latest) Box(Modifier.matchParentSize().padding(2.dp).border(1.dp, CaptureDesign.Accepted))
                }
            }
        }
        item(key = "more", span = { GridItemSpan(maxLineSpan) }) { footer() }
    }
}

@Composable
private fun CameraRollThumbnail(item: PhotoRollItem, foreground: Boolean) {
    val resolver = LocalContext.current.contentResolver
    val signal = remember(item.uri, foreground) { CancellationSignal() }
    var bitmap by remember(item.uri, foreground) { mutableStateOf<Bitmap?>(null) }
    var finished by remember(item.uri, foreground) { mutableStateOf(false) }
    DisposableEffect(signal) { onDispose { signal.cancel() } }
    LaunchedEffect(signal) {
        if (!foreground || Build.VERSION.SDK_INT < 29) return@LaunchedEffect
        bitmap = loadCameraRollThumbnail(resolver, Uri.parse(item.uri), item.kind, signal)
        finished = true
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(CaptureDesign.Panel),
        contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (finished) "${item.kind.label}\nプレビューなし" else item.kind.label,
            style = CaptureDesign.Label, color = CaptureDesign.Muted)
        val tag = when (item.kind) {
            CameraRollKind.DNG -> "DNG"
            CameraRollKind.RAW -> "RAW ↗"
            CameraRollKind.VIDEO -> "▶ " + (item.durationMillis?.let(::cameraRollDuration) ?: "動画")
            CameraRollKind.PHOTO -> null
        }
        tag?.let { Text(it, Modifier.align(Alignment.BottomStart).padding(6.dp).background(CaptureDesign.Ink)
            .then(if (item.kind == CameraRollKind.DNG) Modifier.border(1.dp, CaptureDesign.Text) else Modifier).padding(4.dp),
            style = CaptureDesign.Label, color = if (item.kind == CameraRollKind.DNG) CaptureDesign.Text else CaptureDesign.Muted) }
    }
}

private suspend fun loadCameraRollThumbnail(resolver: ContentResolver, uri: Uri, kind: CameraRollKind, signal: CancellationSignal): Bitmap? =
    withContext(Dispatchers.IO) {
        thumbnailSlots.withPermit {
            try {
                signal.throwIfCanceled()
                val image = if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, Size(256, 256), signal) else null
                if (kind == CameraRollKind.DNG && image != null)
                    correctRollDngThumbnail(image, signal) { resolver.openFileDescriptor(uri, "r", signal) }
                else image
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { null }
        }
    }
