package dev.cinematic.p0.photoroll

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// Lazy visible cells only; at most two standard-provider thumbnail requests across screen recreation.
private val thumbnailSlots = Semaphore(2)

@Composable
internal fun CameraRollGrid(items: List<PhotoRollItem>, foreground: Boolean, modifier: Modifier,
    select: (PhotoRollItem) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Adaptive(104.dp), modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { it.uri }) { item ->
            Column(Modifier.clickable(enabled = foreground, role = Role.Button,
                onClickLabel = if (item.kind == CameraRollKind.DNG) "DNGを現像" else "${item.kind.label}を開く") { select(item) }) {
                CameraRollThumbnail(item, foreground)
                Text(item.name, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                Text(if (item.kind == CameraRollKind.DNG) "DNG · タップで現像" else item.kind.label,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
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
        bitmap = loadCameraRollThumbnail(resolver, Uri.parse(item.uri), signal)
        finished = true
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(if (finished) "${item.kind.label}\nプレビューなし" else item.kind.label,
            style = MaterialTheme.typography.labelMedium)
        if (item.kind == CameraRollKind.VIDEO) Text(
            "▶ " + (item.durationMillis?.let(::cameraRollDuration) ?: "動画"),
            Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.surface).padding(4.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

private suspend fun loadCameraRollThumbnail(resolver: ContentResolver, uri: Uri, signal: CancellationSignal): Bitmap? =
    withContext(Dispatchers.IO) {
        thumbnailSlots.withPermit {
            try {
                signal.throwIfCanceled()
                if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(uri, Size(256, 256), signal) else null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { null }
        }
    }
