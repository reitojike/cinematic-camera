package dev.cinematic.p0.photoroll

import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import dev.cinematic.p0.gpu.*
import dev.cinematic.p0.gpu.preview.*
import dev.cinematic.p0.photoedit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/** Product editor ownership for the opt-in GPU path. The CPU editor and its draft actor are unchanged.
 * Input open and committed save-generation preparation are separate from the latest-value GL mailbox. */
@Composable internal fun GpuPhotoRollEditor(repository: PhotoRollRepository, renderer: ManualTrialPhotoRollRenderer,
    source: String, fromFiles: Boolean, name: String, foreground: Boolean,
    publications: StateFlow<GpuInputPublication>, trimEpoch: Int, back: () -> Unit, modifier: Modifier = Modifier,
    createSession: (android.content.res.AssetManager) -> GpuPhotoRollSession = { GpuPhotoRollSession(it) },
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val recipes = remember(context) { AndroidPhotoEditRecipes(context) }
    val scope = rememberCoroutineScope()
    var retry by remember { mutableIntStateOf(0) }
    var trimBlocked by remember { mutableStateOf(false) }
    var observedTrim by remember { mutableIntStateOf(trimEpoch) }
    var savedStrength by rememberSaveable { mutableDoubleStateOf(.5) }
    var savedWarmth by rememberSaveable { mutableDoubleStateOf(0.0) }
    var savedExposure by rememberSaveable { mutableDoubleStateOf(0.0) }
    var savedIdentity by rememberSaveable { mutableStateOf<String?>(null) }
    var savedContract by rememberSaveable { mutableStateOf<String?>(null) }
    var recipeLoaded by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf(TrialStrengthEditor.empty(PhotoEditValues(savedStrength, savedWarmth, savedExposure))) }
    var input by remember { mutableStateOf<ManualTrialPhotoRollRenderer.GpuInput?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var savingRecipe by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf<PhotoRollJpegSaveOwner?>(null) }
    var saveState by remember { mutableStateOf<PhotoRollSaveSnapshot?>(null) }
    val active = foreground && !trimBlocked
    val session = remember(source, retry, active) { if (active) createSession(context.assets) else null }
    val cancellation = remember(session) { CancellationSignal() }
    DisposableEffect(session) {
        onDispose { cancellation.cancel(); session?.close(); renderer.discardTransientInputs() }
    }
    // UI_HIDDEN and more severe trims release only this preview owner, never foreground export.
    LaunchedEffect(trimEpoch) {
        if (trimEpoch != observedTrim) {
            observedTrim = trimEpoch
            if (foreground) trimBlocked = true
        }
    }
    LaunchedEffect(foreground) { if (!foreground) trimBlocked = false }
    LaunchedEffect(session) {
        input = null; save = null; saveState = null; error = null; notice = null
        editor = editor.suspendForLifecycle()
        if (session == null) return@LaunchedEffect
        val savedValues = PhotoEditValues(savedStrength, savedWarmth, savedExposure)
        val restore = !recipeLoaded
        val identity = savedIdentity
        val contract = savedContract
        try {
            withContext(Dispatchers.IO) { GpuPhotoRollRetiredOwners.releaseBeforeOpen() }
            val deadline = GpuInputRequestDeadline(nowMillis = SystemClock::elapsedRealtime)
            var loading: Deferred<Result<ManualTrialPhotoRollRenderer.GpuInput>>? = null
            coroutineScope {
                try {
                awaitGpuInputReadiness(publications, session.renderer.surfaceAttached, deadline) {
                    val admitted = CompletableDeferred<GpuInputAdmissionResult>()
                    loading = async(Dispatchers.IO) {
                        runCatching {
                            renderer.openGpu(repository, Uri.parse(source), fromFiles, cancellation, deadline,
                                { admitted.complete(GpuInputAdmissionResult(it)) }) { original ->
                                if (restore) recipes.read(original.reference) ?: PhotoAdjustmentPolicy.defaults
                                else {
                                    PhotoEditRestoration.requireMatches(original.reference, identity, contract)
                                    savedValues
                                }
                            }
                        }.also { result ->
                            result.exceptionOrNull()?.let { admitted.complete(GpuInputAdmissionResult(it)) }
                        }
                    }
                    admitted
                }
                } catch (cancelled: CancellationException) {
                    cancellation.cancel()
                    throw cancelled
                } catch (failure: Exception) {
                    // The timeout is visible now, even while structured cancellation is waiting
                    // for a blocked native/queue owner to acknowledge release. Never admit later.
                    cancellation.cancel()
                    currentCoroutineContext().ensureActive()
                    error = failure
                    throw failure
                }
                val loaded = checkNotNull(loading).await().getOrThrow()
                ensureActive(); cancellation.throwIfCanceled()
                input = loaded
                recipeLoaded = true
                savedIdentity = PhotoEditRestoration.identity(loaded.original.reference)
                savedContract = PhotoEditRestoration.contract()
                savedStrength = loaded.values.strength
                savedWarmth = loaded.values.temperature ?: 0.0
                savedExposure = loaded.values.brightness ?: 0.0
                editor = checkNotNull(TrialStrengthEditor.empty(loaded.values).bind(loaded.original.reference))
                save = loaded.save; saveState = loaded.save.snapshot
                session.submit(loaded.values)
                session.renderer.openPhoto(loaded.input)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { currentCoroutineContext().ensureActive(); error = failure }
    }
    val failure by session?.failure?.collectAsState() ?: remember { mutableStateOf<Throwable?>(null) }
    val presented by session?.presented?.collectAsState() ?: remember { mutableStateOf<PhotoEditValues?>(null) }
    val ticket = editor.ticket
    LaunchedEffect(presented, ticket) {
        editor = gpuPresentedEditor(editor, presented, error != null || failure != null)
    }
    // No original read, bitmap, coarse pass or save generation is performed for pointer ticks.
    // Only an accepted release/reset/cancel changes the immutable save-generation request.
    LaunchedEffect(ticket, input, session) {
        val loaded = input ?: return@LaunchedEffect
        if (session == null || (save?.appliedStrength == ticket.values.strength &&
            save?.appliedAdjustments == PhotoAdjustmentPolicy.adjustments(ticket.values))) return@LaunchedEffect
        save = null; saveState = null
        try {
            val prepared = withContext(Dispatchers.IO) { renderer.prepareGpuSave(loaded.original, ticket.values, cancellation) }
            ensureActive()
            if (editor.accepts(ticket)) { save = prepared; saveState = prepared.snapshot }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            if (editor.accepts(ticket)) error = failure
        }
    }
    LaunchedEffect(save, foreground) {
        val owner = save ?: return@LaunchedEffect
        if (foreground) owner.snapshots.collect { saveState = it }
    }
    fun commit(next: TrialStrengthEditor) {
        editor = next
        savedStrength = next.requestedStrength
        savedWarmth = next.requestedValues.temperature ?: 0.0
        savedExposure = next.requestedValues.brightness ?: 0.0
    }
    fun cancelEdit(token: PhotoEditInputToken) { commit(editor.cancel(token)) }
    BackHandler {
        if (!savingRecipe) editor.session?.let { cancelEdit(it.inputToken) } ?: back()
    }
    val problem = failure ?: error
    val gpuFailure = problem?.let { gpuFailurePolicy(it) }
    val ready = active && problem == null && input != null && presented != null
    val phase = when {
        problem != null -> PhotoRollPhase.BLOCKED
        !active -> PhotoRollPhase.PAUSED
        ready -> PhotoRollPhase.DISPLAYED
        else -> PhotoRollPhase.CHECKING
    }
    val problemMessage = when (problem) {
        null -> notice
        is GpuInputReadinessTimeout -> when (problem.reason) {
            GpuInputWaitReason.CAMERA_IN_USE -> "カメラが使用中のため読み込めません。再試行してください"
            GpuInputWaitReason.CAMERA_UNKNOWN -> "カメラの状態を確認できません。再試行してください"
            GpuInputWaitReason.OTHER_PREPARATION -> "入力の準備を確認できません。再試行してください"
        }
        is PhotoRollSourceFailure -> problem.userMessage
        is dev.cinematic.p0.rawcapture.DngContentChanged -> "原本の内容が変わりました。Filesから選び直してください"
        is SecurityException -> "原本の読み取り権限がありません。Filesから選び直してください"
        else -> gpuFailure?.reason ?: "現像を完了できません。原本は変更していません。再試行してください"
    }
    val canSave = ready && !saving && !savingRecipe && editor.session == null &&
        presented == editor.requestedValues && editor.displayedValues == editor.requestedValues && save != null &&
        saveState?.phase in setOf(PhotoRollSavePhase.READY, PhotoRollSavePhase.PUBLISHED, PhotoRollSavePhase.UNCONFIRMED)
    PhotoRollDevelopView(name, PhotoRollPreviewMode.MANUAL_TRIAL, editor, { editor }, phase,
        stage = null, message = problemMessage, preview = null, comparison = null, comparisonLabel = null,
        foreground = foreground && !savingRecipe, savePhase = saveState?.phase, savePending = saving || savingRecipe,
        saveEnabled = canSave, beginEnabled = ready && !saving && !savingRecipe &&
            canBeginStrengthEdit(PhotoRollPreviewMode.MANUAL_TRIAL, foreground, saving, saveState?.phase),
        save = saveAction@{
            if (!canSave) return@saveAction
            val owner = save ?: return@saveAction
            if (saveState?.phase != PhotoRollSavePhase.PUBLISHED && owner.startForegroundSave(context)) return@saveAction
            saving = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { owner.advance { cancellation.isCanceled } }
                    ensureActive()
                    if (save === owner) {
                        saveState = result
                        if (result.phase == PhotoRollSavePhase.PUBLISHED && result.uri != null) try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(result.uri), "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: Exception) { notice = "保存したJPEGを開けませんでした。対応する写真アプリを確認してください" }
                    }
                } finally { saving = false }
            }
        },
        begin = { editor = editor.begin(.01, adjustments = true) },
        back = { if (!savingRecipe) back() },
        retry = { if (!savingRecipe) { trimBlocked = false; retry++ } },
        change = { token, parameter, value -> if (!savingRecipe) editor = editor.change(token, parameter, value) },
        reset = { token -> if (!savingRecipe) {
            val next = editor.reset(token)
            commit(next.session?.let { next.apply(it.inputToken) } ?: next)
        } },
        apply = { token -> if (!savingRecipe) commit(editor.apply(token)) },
        cancel = { token -> if (!savingRecipe) cancelEdit(token) },
        close = closeAction@{ token ->
            if (savingRecipe || presented != editor.requestedValues || problem != null) return@closeAction
            val next = editor.close(token)
            if (next === editor) return@closeAction
            val original = editor.original ?: return@closeAction
            val current = editor.ticket
            val write = recipes.save(original, next.requestedValues)
            savingRecipe = true
            scope.launch {
                try {
                    withContext(NonCancellable + Dispatchers.IO) { write.get() }
                    if (editor.accepts(current)) {
                        editor = next; notice = "調整設定をこの端末に保持しました。JPEGの保存とは別です"
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "調整設定の保持を確認できません。閉じる操作を再試行してください" }
                finally { savingRecipe = false }
            }
        },
        gpuSession = session, gpuReady = ready, gpuFailure = gpuFailure,
        modifier = modifier,
        statusOverlay = { ForegroundSaveNotice(save, foreground, activeOnly = true) },
        info = { Text("GPU の診断用プレビューです。通常版の既定は CPU です。原本と保存前後の照合は変更しません。") })
}
