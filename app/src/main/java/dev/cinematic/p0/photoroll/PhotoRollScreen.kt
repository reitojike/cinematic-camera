package dev.cinematic.p0.photoroll

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import dev.cinematic.p0.photoedit.PhotoEditInputToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PhotoRollScreen(repository: PhotoRollRepository, renderer: PhotoRollRenderer, foreground: Boolean, close: () -> Unit) {
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var fromFiles by rememberSaveable { mutableStateOf(false) }
    var pickerPending by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var explicitRetry by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(PhotoRollSelection()) }
    var trialEditor by remember { mutableStateOf(TrialStrengthEditor.empty()) }
    var listing by remember { mutableStateOf(PhotoRollListing(emptyList())) }
    var loadingTicket by remember { mutableStateOf<Any?>(null) }
    val loading = loadingTicket != null
    var notice by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var comparison by remember { mutableStateOf<Bitmap?>(null) }
    // A displayed copy is released only after Compose removes its old image node.
    DisposableEffect(preview) {
        val owned = preview
        onDispose { if (owned != null && !owned.isRecycled) owned.recycle() }
    }
    DisposableEffect(comparison) {
        val owned = comparison
        onDispose { if (owned != null && !owned.isRecycled) owned.recycle() }
    }
    var comparisonLabel by remember { mutableStateOf<String?>(null) }
    var renderStage by remember { mutableStateOf<PhotoRollRenderStage?>(null) }
    var jpegSave by remember { mutableStateOf<PhotoRollJpegSave?>(null) }
    var saveSnapshot by remember { mutableStateOf<PhotoRollSaveSnapshot?>(null) }
    var savePending by remember { mutableStateOf(false) }
    var viewerPending by remember { mutableStateOf(false) }
    var permissionPending by rememberSaveable { mutableStateOf(false) }
    var accessEpoch by remember { mutableIntStateOf(0) }
    var folderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var kindFilterName by rememberSaveable { mutableStateOf(PhotoRollKindFilter.ALL.name) }
    val kindFilter = PhotoRollKindFilter.entries.firstOrNull { it.name == kindFilterName } ?: PhotoRollKindFilter.ALL
    var pageCursor by remember { mutableStateOf<PhotoRollPageCursor?>(null) }
    var folderMenu by remember { mutableStateOf(false) }
    val saveScope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentForeground by rememberUpdatedState(foreground)
    val access = remember(accessEpoch, foreground) { currentCameraRollAccess(context) }
    val currentQuery by rememberUpdatedState(PhotoRollQuery(folderKey, kindFilter, after = pageCursor,
        includeFolders = pageCursor == null))
    val cancellationOwner = remember { PhotoRollWorkOwner<CancellationSignal> { it.cancel() } }
    val navigation = remember { CameraRollNavigation() }
    val listingWork = remember { CameraRollListingWork() }
    val strengthTicket = trialEditor.ticket
    val strengthEditable = renderer.mode in setOf(PhotoRollPreviewMode.PRIVATE_TRIAL, PhotoRollPreviewMode.STAGE_CLEAR)

    fun updateTrialEditor(next: TrialStrengthEditor) {
        val changesRequest = next.ticket != trialEditor.ticket
        trialEditor = next
        if (changesRequest) {
            cancellationOwner.cancel()
            preview = null; comparison = null; comparisonLabel = null
            jpegSave = null; saveSnapshot = null; explicitRetry = false
            selection = selection.pause()
        }
    }
    fun cancelTrialEdit(token: PhotoEditInputToken) = updateTrialEditor(trialEditor.cancel(token))
    fun resetTrialEdit(token: PhotoEditInputToken) {
        val reset = trialEditor.reset(token)
        if (reset === trialEditor) return
        val nextToken = reset.session?.inputToken ?: return
        updateTrialEditor(reset.apply(nextToken))
    }
    fun chooseDng(uri: String, files: Boolean) {
        renderer.discardTransientInputs()
        cancellationOwner.cancel(); explicitRetry = false; fromFiles = files
        trialEditor = TrialStrengthEditor.empty()
        preview = null; comparison = null; comparisonLabel = null
        selection = selection.clear(); pageCursor = null; selectedUri = uri; retry++
    }

    fun closeRoll() { navigation.exit(); cancellationOwner.cancel(); renderer.discardTransientInputs(); close() }
    fun backToList() { cancellationOwner.cancel(); renderer.discardTransientInputs(); explicitRetry = false; trialEditor = TrialStrengthEditor.empty(); selectedUri = null; preview = null; comparison = null; comparisonLabel = null; pageCursor = null; selection = selection.clear(); retry++ }
    BackHandler {
        val edit = trialEditor.session
        if (edit != null) cancelTrialEdit(edit.inputToken) else if (selectedUri != null) backToList() else closeRoll()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionPending = false; pageCursor = null; listing = PhotoRollListing(emptyList()); accessEpoch++; retry++
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerPending = false
        if (uri != null) chooseDng(uri.toString(), true)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(jpegSave, selection.ticket, lifecycleOwner) {
        val operation = jpegSave ?: return@LaunchedEffect
        val ticket = selection.ticket ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            operation.snapshots.collect { value ->
                if (selectedUri == ticket.source && selection.ticket == ticket &&
                    selection.phase == PhotoRollPhase.DISPLAYED && jpegSave === operation) saveSnapshot = value
            }
        }
    }
    DisposableEffect(lifecycleOwner, renderer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) accessEpoch++
            if (event == Lifecycle.Event.ON_PAUSE) {
                renderer.discardTransientInputs()
                cancellationOwner.cancel(); preview = null; comparison = null; comparisonLabel = null
                trialEditor = trialEditor.pause()
                listing = PhotoRollListing(emptyList()); pageCursor = null; folderMenu = false
                selection = selection.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { cancellationOwner.cancel(); renderer.discardTransientInputs(); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(selectedUri, retry, lifecycleOwner, currentQuery, accessEpoch, strengthTicket) {
      lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        preview = null
        comparison = null
        comparisonLabel = null
        jpegSave = null
        saveSnapshot = null
        savePending = false
        val source = selectedUri
        val selectedFromFiles = fromFiles
        val retryThisRequest = explicitRetry
        explicitRetry = false
        if (source == null) {
            val query = currentQuery
            val previous = listing
            if (query.after == null) listing = PhotoRollListing(emptyList())
            val ticket = listingWork.begin(query, accessEpoch)
            val cancellation = CancellationSignal()
            cancellationOwner.begin(cancellation)
            loadingTicket = ticket
            try {
                val page = withContext(Dispatchers.IO) {
                    repository.list(cancellation, query)
                }
                listingWork.commit(ticket, currentQuery, accessEpoch,
                    selectedUri == null && navigation.canOpenViewer(currentForeground, cancellation.isCanceled), cancellation.isCanceled) {
                    listing = if (query.after == null) page else page.copy(
                        items = (previous.items + page.items).distinctBy { it.uri }, folders = previous.folders)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                listingWork.commit(ticket, currentQuery, accessEpoch,
                    selectedUri == null && navigation.canOpenViewer(currentForeground, cancellation.isCanceled), cancellation.isCanceled) {
                    listing = PhotoRollListing(emptyList(), "一覧を取得できません。撮影へ戻り、再度開いてください。端末内DNGはFilesからも選べます")
                }
            }
            finally { listingWork.finish(ticket); cancellationOwner.finish(cancellation); if (loadingTicket === ticket) loadingTicket = null }
            return@repeatOnLifecycle
        }
        selection = selection.select(source)
        notice = null
        renderStage = null
        val ticket = selection.ticket!!
        val cancellation = CancellationSignal()
        cancellationOwner.begin(cancellation)
        val job = currentCoroutineContext()
        val shouldStop = { !job.isActive || cancellation.isCanceled }
        val requestScope = this
        // WithContext may discard a completed result when Main resumes into cancellation.
        // Until the current ticket adopts it, this request still owns its display copies.
        var pendingPreview: PhotoRollRenderResult.Preview? = null
        try {
            val result = withContext(Dispatchers.IO) {
                run {
                    val original = renderer.checkOriginal(repository, Uri.parse(source), selectedFromFiles, cancellation)
                    withContext(Dispatchers.Main) {
                        if (!selection.accepts(ticket) || !trialEditor.accepts(strengthTicket)) throw CancellationException("Photo selection changed")
                        if (strengthEditable) {
                            trialEditor = trialEditor.bind(original.reference)
                                ?: throw PhotoRollSourceFailure("原本の内容や参照が変わりました。Filesから選び直してください")
                        }
                        selection = selection.rendering(ticket)
                    }
                    val request = PhotoRollRenderRequest(original, strength = strengthTicket.strength, mode = renderer.mode, maxEdge = renderer.maxEdge,
                        explicitRetry = retryThisRequest, onStage = { stage -> requestScope.launch(Dispatchers.Main) {
                            if (selection.accepts(ticket) && trialEditor.accepts(strengthTicket)) renderStage = stage
                        } })
                    val rendered = renderer.render(request, cancellation)
                    if (rendered is PhotoRollRenderResult.Preview) {
                        pendingPreview = rendered
                        val bitmap = rendered.bitmap
                        if (rendered.mode != request.mode || bitmap.isRecycled || bitmap.config == Bitmap.Config.HARDWARE ||
                            bitmap.width !in 1..request.maxEdge || bitmap.height !in 1..request.maxEdge ||
                            bitmap.colorSpace?.isSrgb != true) {
                            throw PhotoRollSourceFailure("表示用画像の寸法・色空間を確認できません。現像を停止しました")
                        }
                        if (rendered.mode != PhotoRollPreviewMode.PRIVATE_TRIAL) repository.verify(original, shouldStop)
                        if (rendered.jpegSave?.matches(original.reference, rendered.mode) == false) {
                            throw PhotoRollSourceFailure("保存結果が選択した原本と一致しないため停止しました")
                        }
                        if (rendered.mode == PhotoRollPreviewMode.STAGE_CLEAR && rendered.jpegSave == null)
                            throw PhotoRollSourceFailure("完成JPEGの保存を確認できないため中止しました")
                        if (rendered.mode == PhotoRollPreviewMode.STAGE_CLEAR && rendered.appliedStrength != request.strength)
                            throw PhotoRollSourceFailure("現像結果の強度が要求と一致しないため中止しました")
                        if (rendered.mode == PhotoRollPreviewMode.PRIVATE_TRIAL && rendered.trialStrength != request.strength)
                            throw PhotoRollSourceFailure("試験画像の強度が要求と一致しないため停止しました")
                    }
                    rendered
                }
            }
            if (selection.accepts(ticket) && trialEditor.accepts(strengthTicket)) when (result) {
                is PhotoRollRenderResult.Preview -> {
                    if (strengthEditable)
                        trialEditor = trialEditor.shown(strengthTicket,
                            requireNotNull(if (result.mode == PhotoRollPreviewMode.PRIVATE_TRIAL) result.trialStrength else result.appliedStrength))
                    preview = result.bitmap
                    comparison = result.comparisonBitmap?.takeIf { before ->
                        strengthEditable && result.comparisonLabel != null && before !== result.bitmap && !before.isRecycled &&
                            before.config != Bitmap.Config.HARDWARE && before.colorSpace?.isSrgb == true &&
                            before.width == result.bitmap.width && before.height == result.bitmap.height
                    }
                    result.comparisonBitmap?.let { before ->
                        if (comparison !== before && before !== result.bitmap && !before.isRecycled) before.recycle()
                    }
                    comparisonLabel = result.comparisonLabel.takeIf { comparison != null }
                    jpegSave = result.jpegSave
                    saveSnapshot = result.jpegSave?.snapshot
                    selection = selection.finish(ticket, true, when (result.mode) {
                        PhotoRollPreviewMode.DNG_TRIAL -> "DNGの試験表示です。ルック・顔検出は未適用で、色や明るさは暫定です。JPEG保存はまだ利用できません"
                        PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "Stage Clear v1・強度0.5の試験表示です。撮影時WBと診断用の明るさを使用。顔検出・顔保護、W1とPixel固有の色変換は未対応で、色や明るさは暫定です。JPEG保存はまだ利用できません"
                        PhotoRollPreviewMode.PRIVATE_TRIAL -> "${result.bitmap.width}×${result.bitmap.height}・強度${trialStrengthLabel(requireNotNull(result.trialStrength))}の縮小試験JPEGです。顔保護なし・診断露出・撮影時WB/暫定色。共有写真へは保存しません"
                        PhotoRollPreviewMode.STAGE_CLEAR -> "Stage Clear v1・強度${trialStrengthLabel(requireNotNull(result.appliedStrength))}・表示用。完成JPEGは保存操作で確認できます"
                    })
                    pendingPreview = null
                }
                is PhotoRollRenderResult.Unavailable -> selection = selection.finish(ticket, false, result.reason)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (selection.accepts(ticket) && trialEditor.accepts(strengthTicket) &&
                failure !is android.os.OperationCanceledException && failure !is DngVerificationStopped) {
                renderer.discardTransientInputs()
            }
            val message = when (failure) {
                is PhotoRollSourceFailure -> failure.userMessage
                is DngContentChanged -> "原本の内容が変わったため停止しました。Filesから選び直してください"
                is DngVerificationStopped -> "原本の確認を中断しました"
                is android.os.OperationCanceledException -> "現像を中断しました。原本を確認して再試行してください"
                is SecurityException -> "原本の読み取り権限がありません。Filesから選び直してください"
                else -> "原本を読み取れないか、現像できませんでした。Filesから選び直してください"
            }
            selection = selection.finish(ticket, false, message)
        } finally {
            pendingPreview?.recycleDisplayPixels()
            cancellationOwner.finish(cancellation)
        }
      }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (selectedUri != null) backToList() else closeRoll() }) { Text(if (selectedUri == null) "撮影へ" else "一覧へ") }
                Text(if (selectedUri == null) "カメラロール" else when (renderer.mode) {
                    PhotoRollPreviewMode.DNG_TRIAL -> "DNG試験表示"
                    PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "Stage Clear試験"
                    PhotoRollPreviewMode.PRIVATE_TRIAL -> "縮小試験JPEG"
                    PhotoRollPreviewMode.STAGE_CLEAR -> "DNG現像"
                }, style = MaterialTheme.typography.titleLarge)
            }
            if (selectedUri == null) {
                CameraRollGrid(listing.items, foreground && !viewerPending && !permissionPending, Modifier.weight(1f), header = {
                    Column {
                        Text("写真・動画・DNG以外のRAWは標準ビューアで開きます。DNGはタップして試験現像できます", style = MaterialTheme.typography.bodySmall)
                        if (renderer.mode == PhotoRollPreviewMode.PRIVATE_TRIAL) Text(
                            "DNGは最大1024pxの試験現像です。顔保護なし・暫定色で、共有写真へは保存しません",
                            style = MaterialTheme.typography.bodySmall)
                        Text(access.description, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = foreground && access.supported && !permissionPending && !pickerPending && !viewerPending,
                                onClick = {
                                    if (navigation.canOpenViewer(currentForeground, false)) try {
                                        permissionPending = true
                                        permissions.launch(cameraRollReadPermissions(Build.VERSION.SDK_INT))
                                    } catch (_: Exception) { permissionPending = false; notice = "アクセス許可を開けませんでした。設定から確認してください" }
                                }) { Text("アクセスを許可・選び直す") }
                            TextButton(enabled = foreground && !permissionPending && !viewerPending, onClick = {
                                if (navigation.canOpenViewer(currentForeground, false)) try {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                                } catch (_: Exception) { notice = "アプリのアクセス設定を開けませんでした" }
                            }) { Text("設定") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(enabled = foreground && !viewerPending && !permissionPending, onClick = { folderMenu = true }) {
                                    Text(listing.folders.firstOrNull { it.key == folderKey }?.label
                                        ?: if (folderKey == null) "すべてのフォルダ" else "選択したフォルダ（アクセス確認中）", maxLines = 2)
                                }
                                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                                    DropdownMenuItem(text = { Text("すべてのフォルダ") }, onClick = {
                                        folderMenu = false; folderKey = null; pageCursor = null; listing = PhotoRollListing(emptyList()); retry++
                                    })
                                    listing.folders.forEach { folder -> DropdownMenuItem(text = { Text(folder.label) }, onClick = {
                                        folderMenu = false; folderKey = folder.key; pageCursor = null; listing = PhotoRollListing(emptyList()); retry++
                                    }) }
                                }
                            }
                            TextButton(enabled = foreground && !loading && !viewerPending && !permissionPending, onClick = {
                                pageCursor = null; retry++
                            }) { Text("更新") }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhotoRollKindFilter.entries.forEach { filter -> FilterChip(
                                selected = filter == kindFilter, enabled = foreground && !viewerPending && !permissionPending,
                                onClick = { kindFilterName = filter.name; pageCursor = null; listing = PhotoRollListing(emptyList()); retry++ },
                                label = { Text(when (filter) {
                                    PhotoRollKindFilter.ALL -> "すべて"
                                    PhotoRollKindFilter.IMAGES -> "画像"
                                    PhotoRollKindFilter.RAW -> "RAW"
                                    PhotoRollKindFilter.VIDEOS -> "動画"
                                }) })
                            }
                        }
                        TextButton(enabled = foreground && !pickerPending && !viewerPending && !permissionPending, onClick = {
                            if (!pickerPending && navigation.canOpenViewer(currentForeground, false)) try { pickerPending = true; picker.launch(arrayOf("*/*")) }
                            catch (_: Exception) { pickerPending = false; notice = "Filesを開けませんでした" }
                        }) { Text("Filesから端末内DNGを選ぶ") }
                        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        listing.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (viewerPending) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (!loading && listing.items.isEmpty()) Text("該当する項目がありません。アクセス許可や絞り込みを確認してください。DNGはFilesの「内部ストレージ」からも選べます", Modifier.padding(vertical = 12.dp))
                    }
                }, footer = {
                    if (listing.hasMore) TextButton(enabled = foreground && !loading && !viewerPending && !permissionPending,
                        onClick = { pageCursor = listing.nextCursor }) { Text("さらに読み込む") }
                }) { item ->
                    if (item.kind == CameraRollKind.DNG) {
                        chooseDng(item.uri, false)
                    } else if (!viewerPending) {
                        val cancellation = CancellationSignal()
                        cancellationOwner.begin(cancellation)
                        viewerPending = true
                        notice = null
                        saveScope.launch {
                            try {
                                val intent = withContext(Dispatchers.IO) { repository.viewIntent(item, cancellation) }
                                if (navigation.canOpenViewer(currentForeground, cancellation.isCanceled)) context.startActivity(intent)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                if (!cancellation.isCanceled) notice = (failure as? PhotoRollSourceFailure)?.userMessage
                                    ?: "項目を開けませんでした。対応する標準ビューアとアクセス権を確認してください"
                            } finally { viewerPending = false; cancellationOwner.finish(cancellation) }
                        }
                    }
                }

            } else if (strengthEditable && trialEditor.session != null) {
                TrialStrengthEditorPane(trialEditor, { trialEditor }, foreground, selection.phase, renderStage, selection.message,
                    preview, comparison, comparisonLabel,
                    change = { token, value -> trialEditor = trialEditor.change(token, value) },
                    reset = ::resetTrialEdit,
                    apply = { token -> updateTrialEditor(trialEditor.apply(token)) },
                    cancel = ::cancelTrialEdit,
                    close = { token -> trialEditor = trialEditor.close(token) },
                    retry = { explicitRetry = true; retry++ },
                    completed = renderer.mode == PhotoRollPreviewMode.STAGE_CLEAR,
                    modifier = Modifier.weight(1f))
            } else {
                val status = when (selection.phase) {
                    PhotoRollPhase.CHECKING -> "原本を確認中…"
                    PhotoRollPhase.RENDERING -> renderStage?.label ?: "DNGを処理中…"
                    PhotoRollPhase.DISPLAYED -> when (renderer.mode) {
                        PhotoRollPreviewMode.DNG_TRIAL -> "試験表示 · ルック未適用"
                        PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "Stage Clear試験 · 顔保護なし"
                        PhotoRollPreviewMode.PRIVATE_TRIAL -> "縮小試験JPEG · 顔保護なし"
                        PhotoRollPreviewMode.STAGE_CLEAR -> "表示用の現像結果"
                    }
                    PhotoRollPhase.BLOCKED -> "現像を開始・完了できません"
                    PhotoRollPhase.PAUSED -> "処理を中断しました"
                    PhotoRollPhase.EMPTY -> "原本の確認待ち"
                }
                Text(status, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
                if (selection.phase in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                selection.message?.let { Text(it, Modifier.padding(vertical = 12.dp)) }
                val image = preview
                if (image != null && selection.phase == PhotoRollPhase.DISPLAYED && strengthEditable)
                    PhotoRollComparisonImage(image, comparison, foreground,
                        if (renderer.mode == PhotoRollPreviewMode.STAGE_CLEAR) "現像結果（表示用）"
                        else "縮小試験JPEG（顔保護なし、診断露出・暫定色）", comparisonLabel,
                        Modifier.fillMaxWidth().weight(1f))
                else if (image != null && selection.phase == PhotoRollPhase.DISPLAYED) Image(image.asImageBitmap(), when (renderer.mode) {
                    PhotoRollPreviewMode.DNG_TRIAL -> "DNGの試験表示（ルック未適用）"
                    PhotoRollPreviewMode.STAGE_CLEAR_TRIAL -> "Stage Clearの試験表示（顔保護なし、色と明るさは暫定）"
                    PhotoRollPreviewMode.PRIVATE_TRIAL -> "縮小試験JPEG（顔保護なし、診断露出・暫定色）"
                    PhotoRollPreviewMode.STAGE_CLEAR -> "現像結果（表示用）"
                },
                    Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                else Spacer(Modifier.weight(1f))
                if (selection.phase == PhotoRollPhase.DISPLAYED) {
                    if (strengthEditable) TextButton(enabled = canBeginStrengthEdit(renderer.mode, foreground, savePending, saveSnapshot?.phase),
                        onClick = { trialEditor = trialEditor.begin(tapStep = .01) }) { Text(if (renderer.mode == PhotoRollPreviewMode.STAGE_CLEAR) "強度を調整" else "強度を試す") }
                    val owner = jpegSave
                    val saved = saveSnapshot
                    Text(if (owner == null) unavailableJpegSaveMessage(renderer.mode)
                        else if (savePending) "同じJPEG保存操作を確認中…" else when (saved?.phase) {
                        PhotoRollSavePhase.READY -> "完成JPEG ${owner.width}×${owner.height} を共有写真へ保存できます"
                        PhotoRollSavePhase.SAVING -> "同じJPEG保存操作を確認中…"
                        PhotoRollSavePhase.PUBLISHED -> "前回のJPEG公開を確認しました。開く前にもう一度照合します"
                        PhotoRollSavePhase.UNCONFIRMED -> "保存結果を確認できません。同じ保存操作を再照合します。別のJPEGは作り直しません"
                        PhotoRollSavePhase.HOLD -> "保存先の状態を判断できないため停止しました。保存済みとは扱いません"
                        null -> "JPEG保存の準備を確認できません"
                    }, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = foreground && !savePending && owner != null && saved?.phase in setOf(
                        PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.UNCONFIRMED),
                        onClick = {
                            if (savePending || saveSnapshot?.phase !in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED,
                                    PhotoRollSavePhase.UNCONFIRMED)) return@TextButton
                            val operation = jpegSave ?: return@TextButton
                            val ticket = selection.ticket ?: return@TextButton
                            val openAfterCheck = saveSnapshot?.phase == PhotoRollSavePhase.PUBLISHED
                            val cancellation = CancellationSignal()
                            cancellationOwner.begin(cancellation)
                            savePending = true
                            saveScope.launch {
                                val job = currentCoroutineContext()
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        operation.advance { cancellation.isCanceled || !job.isActive }
                                    }
                                    if (!cancellation.isCanceled && job.isActive && selectedUri == ticket.source &&
                                        selection.ticket == ticket && selection.phase == PhotoRollPhase.DISPLAYED && jpegSave === operation) {
                                        if (openAfterCheck && result.phase == PhotoRollSavePhase.PUBLISHED && result.uri != null) {
                                            try {
                                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(result.uri), "image/jpeg")
                                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                            } catch (_: Exception) { notice = "保存したJPEGを開けませんでした。対応する写真アプリを確認してください" }
                                        }
                                    }
                                } finally {
                                    if (selection.ticket == ticket && jpegSave === operation) savePending = false
                                    cancellationOwner.finish(cancellation)
                                }
                            }
                        }) { Text(if (savePending) "保存確認中…" else when (saved?.phase) {
                            PhotoRollSavePhase.PUBLISHED -> "保存したJPEGを開く"
                            PhotoRollSavePhase.UNCONFIRMED -> "同じ保存を確認"
                            PhotoRollSavePhase.SAVING -> "保存確認中…"
                            else -> if (renderer.mode == PhotoRollPreviewMode.PRIVATE_TRIAL) "試験JPEG・共有保存なし"
                                else if (owner == null) "JPEG保存は準備中" else "完成JPEGを保存"
                        }) }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Text("原本は読み取り専用で参照します。コピー・上書き・公開は行いません", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = foreground && selection.phase !in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING),
                    onClick = { explicitRetry = true; retry++ }) { Text("原本を確認して再試行") }
            }
        }
    }
}
