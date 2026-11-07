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
import androidx.compose.foundation.verticalScroll
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
import dev.cinematic.p0.photoedit.PhotoEditValues
import dev.cinematic.p0.photoedit.PhotoEditRestoration
import dev.cinematic.p0.photoedit.AndroidPhotoEditRecipes
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.raw.PhotoAdjustments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun PhotoRollScreen(repository: PhotoRollRepository, renderer: PhotoRollRenderer, foreground: Boolean, close: () -> Unit) {
    var selectedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var fromFiles by rememberSaveable { mutableStateOf(false) }
    var pickerPending by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var explicitRetry by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(PhotoRollSelection()) }
    var savedOriginalIdentity by rememberSaveable { mutableStateOf<String?>(null) }
    var savedProcessingContract by rememberSaveable { mutableStateOf<String?>(null) }
    var skipDurableRecipe by rememberSaveable { mutableStateOf(false) }
    var recipeRecoveryNeeded by remember { mutableStateOf(false) }
    var recipeLoaded by rememberSaveable { mutableStateOf(false) }
    var editSavePending by remember { mutableStateOf(false) }
    var savedStrength by rememberSaveable { mutableDoubleStateOf(.5) }
    var savedWarmth by rememberSaveable { mutableDoubleStateOf(0.0) }
    var savedExposureEv by rememberSaveable { mutableDoubleStateOf(0.0) }
    val manualTrial = renderer.mode == PhotoRollPreviewMode.MANUAL_TRIAL
    var trialEditor by remember { mutableStateOf(TrialStrengthEditor.empty(
        if (manualTrial) PhotoEditValues(savedStrength, savedWarmth, savedExposureEv) else PhotoEditValues(savedStrength))) }
    var listing by remember { mutableStateOf(PhotoRollListing(emptyList())) }
    var listedQuery by remember { mutableStateOf<PhotoRollQuery?>(null) }
    var listedAccessEpoch by remember { mutableIntStateOf(-1) }
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
    var jpegSave by remember { mutableStateOf<PhotoRollJpegSaveOwner?>(null) }
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
    val editRecipes = remember(context.applicationContext) { AndroidPhotoEditRecipes(context) }
    val currentForeground by rememberUpdatedState(foreground)
    val access = remember(accessEpoch, foreground) { currentCameraRollAccess(context) }
    val currentQuery by rememberUpdatedState(PhotoRollQuery(folderKey, kindFilter, after = pageCursor,
        includeFolders = pageCursor == null))
    val cancellationOwner = remember { PhotoRollWorkOwner<CancellationSignal> { it.cancel() } }
    val navigation = remember { CameraRollNavigation() }
    val listingWork = remember { CameraRollListingWork() }
    val strengthTicket = trialEditor.ticket
    val strengthEditable = renderer.mode in setOf(PhotoRollPreviewMode.PRIVATE_TRIAL,
        PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.MANUAL_TRIAL)
    val saveableMode = renderer.mode in setOf(PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.MANUAL_TRIAL)

    fun updateTrialEditor(next: TrialStrengthEditor) {
        val changesRequest = next.ticket != trialEditor.ticket
        trialEditor = next
        savedStrength = next.requestedStrength
        savedWarmth = next.requestedValues.temperature ?: 0.0
        savedExposureEv = next.requestedValues.brightness ?: 0.0
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
    fun closeTrialEdit(token: PhotoEditInputToken) {
        if (editSavePending) return
        val current = trialEditor
        val accepted = current.close(token)
        if (accepted === current) return
        if (!manualTrial) { trialEditor = accepted; return }
        val original = current.original ?: return
        val ticket = current.ticket
        // Queue admission is synchronous with the accepted UI action, even across recreation.
        val write = editRecipes.save(original, accepted.requestedValues)
        editSavePending = true
        notice = null
        saveScope.launch {
            try {
                // Closing accepts this exact recipe. Finish its small atomic write even if the
                // Activity leaves; cancellation must not report an unknown commit as a failure.
                withContext(NonCancellable + Dispatchers.IO) { write.get() }
                if (trialEditor.accepts(ticket) && trialEditor.session?.inputToken == token) {
                    trialEditor = accepted
                    notice = "調整設定をこの端末に保持しました。JPEGの保存とは別です"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (trialEditor.accepts(ticket)) notice = "調整設定の保持を確認できません。調整を閉じる操作を再試行してください"
            } finally { editSavePending = false }
        }
    }
    fun chooseDng(uri: String, files: Boolean) {
        if (editSavePending) return
        recipeLoaded = false; recipeRecoveryNeeded = false; skipDurableRecipe = false
        savedOriginalIdentity = null; savedProcessingContract = null
        renderer.discardTransientInputs()
        cancellationOwner.cancel(); explicitRetry = false; fromFiles = files
        savedStrength = .5
        savedWarmth = 0.0; savedExposureEv = 0.0
        trialEditor = TrialStrengthEditor.empty(if (manualTrial) PhotoAdjustmentPolicy.defaults else PhotoEditValues(.5))
        preview = null; comparison = null; comparisonLabel = null
        selection = selection.clear(); pageCursor = null; selectedUri = uri; retry++
    }

    fun closeRoll() { if (editSavePending) return; navigation.exit(); cancellationOwner.cancel(); renderer.discardTransientInputs(); close() }
    fun backToList() { if (editSavePending) return; recipeLoaded = false; recipeRecoveryNeeded = false; skipDurableRecipe = false; savedOriginalIdentity = null; savedProcessingContract = null; cancellationOwner.cancel(); renderer.discardTransientInputs(); explicitRetry = false; savedStrength = .5; savedWarmth = 0.0; savedExposureEv = 0.0; trialEditor = TrialStrengthEditor.empty(if (manualTrial) PhotoAdjustmentPolicy.defaults else PhotoEditValues(.5)); selectedUri = null; preview = null; comparison = null; comparisonLabel = null; pageCursor = null; selection = selection.clear(); retry++ }
    BackHandler {
        if (editSavePending) return@BackHandler
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
                trialEditor = trialEditor.suspendForLifecycle().also {
                    savedStrength = it.requestedStrength
                    savedWarmth = it.requestedValues.temperature ?: 0.0
                    savedExposureEv = it.requestedValues.brightness ?: 0.0
                }
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
        val restoreRecipeThisRequest = manualTrial && !recipeLoaded
        val skipRecipeThisRequest = skipDurableRecipe
        val previousIdentity = savedOriginalIdentity
        val previousContract = savedProcessingContract
        val retryThisRequest = explicitRetry
        explicitRetry = false
        if (source == null) {
            val query = currentQuery
            val previous = listing
            if (query.after == null && !canKeepCameraRollTiles(listedQuery, listedAccessEpoch, query, accessEpoch))
                listing = PhotoRollListing(emptyList())
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
                    listedQuery = query
                    listedAccessEpoch = accessEpoch
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
                    val restore = if (manualTrial) try {
                        if (restoreRecipeThisRequest) {
                            if (skipRecipeThisRequest) null else editRecipes.read(original.reference)
                        } else {
                            PhotoEditRestoration.requireMatches(original.reference, previousIdentity, previousContract)
                            null
                        }
                    } catch (failure: Exception) { throw PhotoEditRestoreFailure(failure) } else null
                    withContext(Dispatchers.Main) {
                        if (!selection.accepts(ticket) || !trialEditor.accepts(strengthTicket)) throw CancellationException("Photo selection changed")
                        if (manualTrial && !recipeLoaded) {
                            recipeLoaded = true
                            savedOriginalIdentity = PhotoEditRestoration.identity(original.reference)
                            savedProcessingContract = PhotoEditRestoration.contract()
                            val restoredValues = restore ?: PhotoAdjustmentPolicy.defaults
                            if (restoredValues != trialEditor.requestedValues) {
                                updateTrialEditor(TrialStrengthEditor.empty(restoredValues))
                                throw CancellationException("Restored edit recipe requires a new render ticket")
                            }
                        }
                        if (strengthEditable) {
                            trialEditor = trialEditor.bind(original.reference)
                                ?: throw PhotoRollSourceFailure("原本の内容や参照が変わりました。Filesから選び直してください")
                        }
                        selection = selection.rendering(ticket)
                    }
                    val adjustments = if (manualTrial) PhotoAdjustmentPolicy.adjustments(strengthTicket.values) else PhotoAdjustments()
                    val request = PhotoRollRenderRequest(original, strength = strengthTicket.strength, adjustments = adjustments,
                        mode = renderer.mode, maxEdge = renderer.maxEdge,
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
                        if (rendered.mode !in setOf(PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.MANUAL_TRIAL) &&
                            rendered.jpegSave != null) throw PhotoRollSourceFailure("試験表示に保存対象が混入したため停止しました")
                        if (rendered.mode in setOf(PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.MANUAL_TRIAL) && rendered.jpegSave == null)
                            throw PhotoRollSourceFailure("JPEGの保存対象を確認できないため中止しました")
                        if (rendered.mode == PhotoRollPreviewMode.MANUAL_TRIAL && rendered.jpegSave != null) {
                            val outputWidth = rendered.jpegSave.width
                            val outputHeight = rendered.jpegSave.height
                            if (outputWidth < 0 || outputHeight < 0 || (outputWidth == 0) != (outputHeight == 0) ||
                                outputWidth.toLong() * outputHeight > 24_000_000L)
                                throw PhotoRollSourceFailure("24MPを超える、または寸法不明の試験JPEGは保存できません")
                        }
                        if (rendered.mode == PhotoRollPreviewMode.MANUAL_TRIAL &&
                            (rendered.jpegSave?.appliedStrength != request.strength ||
                                rendered.jpegSave?.appliedAdjustments != request.adjustments ||
                                rendered.appliedAdjustments != request.adjustments))
                            throw PhotoRollSourceFailure("保存対象の強度が要求と一致しないため中止しました")
                        if (rendered.mode in setOf(PhotoRollPreviewMode.STAGE_CLEAR, PhotoRollPreviewMode.MANUAL_TRIAL) &&
                            rendered.appliedStrength != request.strength)
                            throw PhotoRollSourceFailure("現像結果の強度が要求と一致しないため中止しました")
                        if (rendered.mode == PhotoRollPreviewMode.PRIVATE_TRIAL &&
                            (rendered.trialStrength != request.strength || rendered.trialAdjustments != request.adjustments))
                            throw PhotoRollSourceFailure("試験画像の強度が要求と一致しないため停止しました")
                    }
                    rendered
                }
            }
            if (selection.accepts(ticket) && trialEditor.accepts(strengthTicket)) when (result) {
                is PhotoRollRenderResult.Preview -> {
                    if (strengthEditable) trialEditor = trialEditor.shown(strengthTicket,
                        strengthTicket.values.copy(strength = requireNotNull(
                            if (result.mode == PhotoRollPreviewMode.PRIVATE_TRIAL) result.trialStrength else result.appliedStrength)))
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
                        PhotoRollPreviewMode.MANUAL_TRIAL -> "強度${trialStrengthLabel(requireNotNull(result.appliedStrength))}の縮小プレビューです。原寸の試験品質JPEGは手動保存時に生成します。顔保護なし・診断露出・暫定色です"
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
            if (failure is PhotoEditRestoreFailure && selection.accepts(ticket) && trialEditor.accepts(strengthTicket))
                recipeRecoveryNeeded = true
            val message = when (failure) {
                is PhotoEditRestoreFailure -> "保持した調整設定を確認できません。原本は変更していません。設定を使わずに開くか、再試行してください"
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
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Process save status survives selection/lifecycle reloads; cancel must not wait for preview IO.
            ForegroundSaveNotice(jpegSave, foreground)
            if (selectedUri == null) {
                CameraRollBrowser(listing, access, kindFilter, folderKey, loading, viewerPending, notice, renderer.mode,
                    foreground && !viewerPending && !permissionPending,
                    foreground && !loading && !viewerPending && !permissionPending,
                    foreground && access.supported && !permissionPending && !pickerPending && !viewerPending,
                    foreground && !pickerPending && !viewerPending && !permissionPending,
                    folderMenu = folderMenu, setFolderMenu = { folderMenu = it }, close = ::closeRoll,
                    refresh = { pageCursor = null; retry++ },
                    chooseFolder = { key -> folderKey = key; pageCursor = null; listing = PhotoRollListing(emptyList()); retry++ },
                    chooseFilter = { filter -> kindFilterName = filter.name; pageCursor = null; listing = PhotoRollListing(emptyList()); retry++ },
                    permission = {
                        if (navigation.canOpenViewer(currentForeground, false)) try {
                            permissionPending = true
                            permissions.launch(cameraRollReadPermissions(Build.VERSION.SDK_INT))
                        } catch (_: Exception) { permissionPending = false; notice = "アクセス許可を開けませんでした。設定から確認してください" }
                    },
                    settings = {
                        if (navigation.canOpenViewer(currentForeground, false)) try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        } catch (_: Exception) { notice = "アプリのアクセス設定を開けませんでした" }
                    },
                    files = {
                        if (!pickerPending && navigation.canOpenViewer(currentForeground, false)) try { pickerPending = true; picker.launch(arrayOf("*/*")) }
                        catch (_: Exception) { pickerPending = false; notice = "Filesを開けませんでした" }
                    },
                    more = { pageCursor = listing.nextCursor }, modifier = Modifier.weight(1f)) { item ->
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

            } else {
                val owner = jpegSave
                val saved = saveSnapshot
                val saveDescription = if (owner == null) unavailableJpegSaveMessage(renderer.mode)
                        else if (renderer.mode == PhotoRollPreviewMode.MANUAL_TRIAL) when {
                            savePending || saved?.phase == PhotoRollSavePhase.SAVING -> "試験品質JPEGの手動保存を確認中…"
                            saved?.phase == PhotoRollSavePhase.READY -> "試験品質の原寸JPEGを生成して手動保存します。顔保護なし・診断露出・暫定色です。24MP以下の対応DNGのみ利用できます"
                            saved?.phase == PhotoRollSavePhase.PUBLISHED -> "試験品質JPEGの保存を確認しました。開く前に再確認します"
                            saved?.phase == PhotoRollSavePhase.UNCONFIRMED -> "保存結果を確認できません。同じ保存操作を再照合します。別のJPEGは作りません"
                            else -> "試験品質JPEGの保存状態が不明なため停止しました。保存済みとは表示しません"
                        }
                        else if (savePending) "同じJPEG保存操作を確認中…" else when (saved?.phase) {
                        PhotoRollSavePhase.READY -> "完成JPEG ${owner.width}×${owner.height} を共有写真へ保存できます"
                        PhotoRollSavePhase.SAVING -> "同じJPEG保存操作を確認中…"
                        PhotoRollSavePhase.PUBLISHED -> "前回のJPEG公開を確認しました。開く前にもう一度照合します"
                        PhotoRollSavePhase.UNCONFIRMED -> "保存結果を確認できません。同じ保存操作を再照合します。別のJPEGは作り直しません"
                        PhotoRollSavePhase.HOLD -> "保存先の状態を判断できないため停止しました。保存済みとは扱いません"
                        null -> "JPEG保存の準備を確認できません"
                    }
                PhotoRollDevelopView(
                    name = listing.items.firstOrNull { it.uri == selectedUri }?.name ?: "DNG（名前未取得）",
                    mode = renderer.mode, state = trialEditor, readState = { trialEditor }, phase = selection.phase,
                    stage = renderStage, message = notice ?: selection.message, preview = preview, comparison = comparison,
                    comparisonLabel = comparisonLabel, foreground = foreground && !editSavePending, savePhase = saved?.phase, savePending = savePending || editSavePending,
                    saveEnabled = foreground && !editSavePending && trialEditor.session == null && selection.phase == PhotoRollPhase.DISPLAYED && !savePending && owner != null &&
                        saved?.phase in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.UNCONFIRMED),
                    beginEnabled = !editSavePending && strengthEditable && selection.phase == PhotoRollPhase.DISPLAYED && canBeginStrengthEdit(renderer.mode, foreground, savePending, saved?.phase),
                    begin = { if (!editSavePending) trialEditor = trialEditor.begin(tapStep = .01, adjustments = manualTrial) },
                    back = ::backToList, retry = { if (!editSavePending) { explicitRetry = true; retry++ } },
                    change = { token, parameter, value -> if (!editSavePending) trialEditor = trialEditor.change(token, parameter, value) },
                    reset = { token -> if (!editSavePending) resetTrialEdit(token) }, apply = { token -> if (!editSavePending) updateTrialEditor(trialEditor.apply(token)) },
                    cancel = { token -> if (!editSavePending) cancelTrialEdit(token) }, close = ::closeTrialEdit,
                    modifier = Modifier.weight(1f), save = saveAction@{
                            if (!currentForeground || editSavePending || trialEditor.session != null || selection.phase != PhotoRollPhase.DISPLAYED ||
                                savePending || saveSnapshot?.phase !in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED,
                                    PhotoRollSavePhase.UNCONFIRMED)) return@saveAction
                            val operation = jpegSave ?: return@saveAction
                            val ticket = selection.ticket ?: return@saveAction
                            val openAfterCheck = saveSnapshot?.phase == PhotoRollSavePhase.PUBLISHED
                            if (!openAfterCheck && operation.startForegroundSave(context)) return@saveAction
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
                    }) {
                    if (recipeRecoveryNeeded) TextButton(enabled = foreground && !editSavePending, onClick = {
                        recipeLoaded = false; recipeRecoveryNeeded = false; skipDurableRecipe = true
                        savedOriginalIdentity = null; savedProcessingContract = null
                        updateTrialEditor(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults))
                        retry++
                    }) { Text("保持した設定を使わずに開く") }
                    if (editSavePending) Text("調整設定を保持しています…", style = MaterialTheme.typography.bodySmall)
                    Text(saveDescription, style = MaterialTheme.typography.bodySmall)
                    Text(if (manualTrial) "試験品質です。顔保護なし・診断露出・暫定色です。24MP以下の対応DNGのみ、表示した調整値の原寸JPEGを手動保存します。"
                        else if (renderer.mode == PhotoRollPreviewMode.PRIVATE_TRIAL) "最大1024px・顔保護なし・診断露出・暫定色の試験表示です。共有保存はできません。"
                        else "表示と保存の確認は別です。調整はこの画面内で一時的です。", style = MaterialTheme.typography.bodySmall)
                    if (manualTrial) Text("調整を閉じると設定を端末内に保持します。同じ原本を再選択して再利用できます。データ消去・再インストール後は復元できません。", style = MaterialTheme.typography.bodySmall)
                    trialEditor.original?.let { Text("原本確認済み: ${it.fingerprint.bytes} bytes（保存時は再確認）", style = MaterialTheme.typography.bodySmall) }
                    Text("左右タップは1段、ドラッグは連続値、離して画像を更新します。強度0とRAW基準表示は別です。調整を閉じてから保存します。", style = MaterialTheme.typography.bodySmall)
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(enabled = foreground && !editSavePending && selection.phase !in setOf(PhotoRollPhase.CHECKING, PhotoRollPhase.RENDERING),
                        onClick = { explicitRetry = true; retry++ }) { Text("原本を確認して再試行") }
                }
            }
        }
    }
}

private class PhotoEditRestoreFailure(cause: Exception) : java.io.IOException("Edit recipe restoration failed", cause)
