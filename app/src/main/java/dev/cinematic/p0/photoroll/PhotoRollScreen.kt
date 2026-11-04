package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.cinematic.p0.rawcapture.DngContentChanged
import dev.cinematic.p0.rawcapture.DngVerificationStopped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Process scoped so Activity recreation cannot overlap non-cooperative decoder calls. */
private val photoRollWork = Semaphore(1)

@Composable
internal fun PhotoRollScreen(repository: PhotoRollRepository, renderer: PhotoRollRenderer, foreground: Boolean, close: () -> Unit) {
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var fromFiles by rememberSaveable { mutableStateOf(false) }
    var pickerPending by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var selection by remember { mutableStateOf(PhotoRollSelection()) }
    var listing by remember { mutableStateOf(PhotoRollListing(emptyList())) }
    var loadingTicket by remember { mutableStateOf<Any?>(null) }
    val loading = loadingTicket != null
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val cancellationOwner = remember { PhotoRollWorkOwner<CancellationSignal> { it.cancel() } }

    fun backToList() { cancellationOwner.cancel(); selectedUri = null; preview = null; selection = selection.clear(); retry++ }
    BackHandler { if (selectedUri != null) backToList() else close() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerPending = false
        if (uri != null) { cancellationOwner.cancel(); fromFiles = true; selectedUri = uri.toString(); retry++ }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { cancellationOwner.cancel(); preview = null; selection = selection.pause() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { cancellationOwner.cancel(); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(selectedUri, retry, lifecycleOwner) {
      lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        preview = null
        val source = selectedUri
        if (source == null) {
            val ticket = Any()
            loadingTicket = ticket
            try {
                listing = withContext(Dispatchers.IO) {
                    photoRollWork.withPermit { repository.list(CancellationSignal()) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { listing = PhotoRollListing(emptyList(), "一覧を取得できません。Filesから端末内DNGを選んでください") }
            finally { if (loadingTicket === ticket) loadingTicket = null }
            return@repeatOnLifecycle
        }
        selection = selection.select(source)
        val ticket = selection.ticket!!
        val cancellation = CancellationSignal()
        cancellationOwner.begin(cancellation)
        val job = currentCoroutineContext()
        val shouldStop = { !job.isActive || cancellation.isCanceled }
        try {
            val result = withContext(Dispatchers.IO) {
                photoRollWork.withPermit {
                    val original = repository.check(Uri.parse(source), fromFiles, shouldStop, renderer.maxSourceBytes)
                    withContext(Dispatchers.Main) { selection = selection.rendering(ticket) }
                    val request = PhotoRollRenderRequest(original, mode = renderer.mode)
                    val rendered = renderer.render(request, cancellation)
                    if (rendered is PhotoRollRenderResult.Preview) {
                        val bitmap = rendered.bitmap
                        if (rendered.mode != request.mode || bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE ||
                            bitmap.width !in 1..request.maxEdge || bitmap.height !in 1..request.maxEdge ||
                            bitmap.colorSpace?.isSrgb != true) {
                            throw PhotoRollSourceFailure("表示用画像の寸法・色空間を確認できません。現像を停止しました")
                        }
                        repository.verify(original, shouldStop)
                    }
                    rendered
                }
            }
            if (selection.accepts(ticket)) when (result) {
                is PhotoRollRenderResult.Preview -> {
                    preview = result.bitmap
                    selection = selection.finish(ticket, true, if (result.mode == PhotoRollPreviewMode.DNG_TRIAL)
                        "DNGの試験表示です。ルック・顔検出は未適用で、色や明るさは暫定です。JPEG保存はまだ利用できません"
                        else "Stage Clear v1・強度0.5・表示用。JPEG書き出しは未接続です")
                }
                is PhotoRollRenderResult.Unavailable -> selection = selection.finish(ticket, false, result.reason)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val message = when (failure) {
                is PhotoRollSourceFailure -> failure.userMessage
                is DngContentChanged -> "原本の内容が変わったため停止しました。Filesから選び直してください"
                is DngVerificationStopped -> "原本の確認を中断しました"
                is SecurityException -> "原本の読み取り権限がありません。Filesから選び直してください"
                else -> "原本を読み取れないか、現像できませんでした。Filesから選び直してください"
            }
            selection = selection.finish(ticket, false, message)
        } finally {
            cancellationOwner.finish(cancellation)
        }
      }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (selectedUri != null) backToList() else close() }) { Text(if (selectedUri == null) "撮影へ" else "一覧へ") }
                Text(if (selectedUri == null) "写真 · DNG" else if (renderer.mode == PhotoRollPreviewMode.DNG_TRIAL) "DNG試験表示" else "DNG現像", style = MaterialTheme.typography.titleLarge)
            }
            if (selectedUri == null) {
                Text("端末内のDNG原本を選んでください", style = MaterialTheme.typography.titleMedium)
                Text("Filesでは「内部ストレージ」から選択してください", style = MaterialTheme.typography.bodySmall)
                Text(if (renderer.mode == PhotoRollPreviewMode.DNG_TRIAL) "DNGを縮小して試験表示します。ルック適用・編集調整・JPEG保存は準備中です"
                    else "外部SDからの取り込み・編集調整・JPEG書き出しは準備中です", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = foreground && !pickerPending, onClick = {
                    if (!pickerPending) try { pickerPending = true; picker.launch(arrayOf("*/*")) }
                    catch (_: Exception) { pickerPending = false; notice = "Filesを開けませんでした" }
                }) { Text("Filesから端末内DNGを選ぶ") }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                listing.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!loading && listing.items.isEmpty()) Text("アクセス可能なDNGはまだありません", Modifier.padding(vertical = 24.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(listing.items, key = { it.uri }) { item ->
                        ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text("DNG候補 · 選択後に原本を確認") },
                            modifier = Modifier.clickable(enabled = foreground) { cancellationOwner.cancel(); fromFiles = false; selectedUri = item.uri })
                    }
                }
            } else {
                val status = when (selection.phase) {
                    PhotoRollPhase.CHECKING -> "原本を確認中…"
                    PhotoRollPhase.RENDERING -> if (renderer.mode == PhotoRollPreviewMode.DNG_TRIAL) "DNGを処理中…" else "現像処理を確認中…"
                    PhotoRollPhase.DISPLAYED -> if (renderer.mode == PhotoRollPreviewMode.DNG_TRIAL) "試験表示 · ルック未適用" else "表示用の現像結果"
                    PhotoRollPhase.BLOCKED -> "現像を開始・完了できません"
                    PhotoRollPhase.PAUSED -> "処理を中断しました"
                    PhotoRollPhase.EMPTY -> "原本の確認待ち"
                }
                Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
                if (selection.phase in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                selection.message?.let { Text(it, Modifier.padding(vertical = 12.dp)) }
                val image = preview
                if (image != null && selection.phase == PhotoRollPhase.DISPLAYED) Image(image.asImageBitmap(), if (renderer.mode == PhotoRollPreviewMode.DNG_TRIAL) "DNGの試験表示（ルック未適用）" else "現像結果（表示用）",
                    Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                else Spacer(Modifier.weight(1f))
                Text("原本は読み取り専用で参照します。コピー・上書き・公開は行いません", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = foreground && selection.phase !in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING),
                    onClick = { retry++ }) { Text("原本を確認して再試行") }
            }
        }
    }
}
