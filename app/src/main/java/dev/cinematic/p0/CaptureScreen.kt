package dev.cinematic.p0

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle

private val LocalCaptureAngle = staticCompositionLocalOf { 0f }
private val LocalCaptureHorizontal = staticCompositionLocalOf { false }
private fun Modifier.blockPanelBackground(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
}
@Composable private fun Modifier.sensorRotation(): Modifier {
    val angle = LocalCaptureAngle.current
    return graphicsLayer { rotationZ = angle }
}

private val Ink = Color(0xFF0A0B0C)
private val Sheet = Color(0xFF111315)
private val Foreground = Color(0xFFE4E6E8)
private val Muted = Color(0xFFA3AAB1)
private val Amber = Color(0xFFE6A35C)
private val Accepted = Color(0xFF7CC4A5)
private val Failed = Color(0xFFEF8A74)
private val Rec = Color(0xFFE5483B)

@Composable fun CaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Foreground, background = Ink, surface = Sheet,
        onSurface = Foreground, onBackground = Foreground, error = Failed), content = content)
}

@Composable
fun CaptureScreen(probe: CameraProbe, permission: Boolean, orientation: CaptureOrientation, requestPermission: () -> Unit,
    record: () -> Unit, export: () -> Unit, reportPending: Boolean, exit: () -> Unit) {
    var editor by remember { mutableStateOf(CaptureEditor(epoch = probe.currentEpoch)) }
    var panel by remember { mutableStateOf(CapturePanel.NONE) }
    var confirmReset by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(probe.exposureMigrationNotice) {
        probe.consumeExposureMigrationNotice()?.let { notice = it }
    }
    var backAt by remember { mutableLongStateOf(0L) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var measurementTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lifecycleOwner, probe.bound) {
        if (probe.bound) lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { measurementTick++; kotlinx.coroutines.delay(500) }
        }
    }
    // Tick invalidates stale values, but the actual clock is read after a new frame has arrived.
    val measurementNow = measurementTick.let { SystemClock.elapsedRealtime() }
    val stillExposure = probe.stillExposure
    val stillPresentation = stillExposure?.presentation()
    val ownsCapture = stillExposure?.ownsCapture == true
    val recoveryRequired = stillPresentation?.recoveryRequired == true
    val unlocked = !probe.busy && !probe.recordingBusy && !ownsCapture
    val exposureEditable = probe.canSelectExposure
    val animatedAngle by animateFloatAsState(orientation.uiAngle, tween(250), label = "capture-elements")
    val captureReady = unlocked && probe.bound && probe.controlReady && probe.sharedSaveSupported && (stillExposure == null || stillExposure.canCapture)
    val receipt = probe.controlReceipt
    val liveIntent = probe.liveControlIntent
    val exposurePhase = if (probe.exposureOperationPending) ControlPhase.PENDING else receipt.phase
    val requestedCapture = logicalCaptureTarget(probe.currentEpoch, receipt, probe.liveExposureTarget,
        probe.stillExposure?.target, probe.appliedExposure)
    val exposureFeedback = listOf(CaptureKey.ISO, CaptureKey.SHUTTER).associateWith {
        exposureKeyFeedback(it, requestedCapture, probe.appliedExposure, exposurePhase)
    }
    val focusFeedback = liveFocusPresentation(probe.overrides.focusDistance, liveIntent, receipt, probe.liveControlsPending)
    val wbFeedback = liveWhiteBalancePresentation(probe.overrides.whiteBalance, liveIntent, receipt, probe.liveControlsPending)
    val evFeedback = liveEvPresentation(probe.acceptedEv, probe.exposureCompensationStep, liveIntent, receipt, probe.liveControlsPending)
    val changingKeys = buildList {
        exposureFeedback.filterValues { it.affected && it.tone == SensorControlTone.PENDING }.keys.forEach { add(it.name) }
        if (focusFeedback.affected && focusFeedback.tone == SensorControlTone.PENDING) add("FOCUS")
        if (wbFeedback.affected && wbFeedback.tone == SensorControlTone.PENDING) add("WB")
        if (evFeedback.affected && evFeedback.tone == SensorControlTone.PENDING) add("EV")
    }
    val pendingNotice = if (receipt.phase == ControlPhase.PENDING || probe.exposureOperationPending || probe.liveControlsPending)
        if (changingKeys.isEmpty()) "設定変更中" else "${changingKeys.joinToString("・")}変更中" else null
    val focusAllowed by rememberUpdatedState(panel == CapturePanel.NONE && editor.submitting == null && !ownsCapture && !probe.busy)
    LaunchedEffect(probe.currentEpoch) { editor = editor.session(probe.currentEpoch) }
    LaunchedEffect(receipt) {
        val feedback = completeEditorReceipt(editor, receipt)
        editor = feedback.editor
        feedback.notice?.let { notice = it }
    }
    LaunchedEffect(panel, probe.recordingStage) { panel = recordingPanel(panel, probe.recordingStage) }
    var seenRecording by remember { mutableLongStateOf(probe.recordingStartSequence) }
    LaunchedEffect(probe.recordingStartSequence) {
        if (seenRecording != probe.recordingStartSequence) {
            editor = editor.recording(RecordingStage.STARTING); panel = CapturePanel.NONE
            seenRecording = probe.recordingStartSequence
        }
    }
    var seenOrientation by remember { mutableIntStateOf(orientation.sensorQuarter) }
    LaunchedEffect(orientation.sensorQuarter) {
        if (seenOrientation != orientation.sensorQuarter) {
            notice = if (probe.recordingBusy) "回転: 配置固定・文字のみ回転・録画継続" else "回転: 配置固定・文字のみ回転"
            seenOrientation = orientation.sensorQuarter
        }
    }
    LaunchedEffect(editor.note, editor.submitting) {
        if (editor.submitting == null) editor.note?.let { notice = it }
    }
    var seenSave by remember { mutableStateOf(probe.photoStage to probe.recordingSaveSucceeded) }
    LaunchedEffect(probe.photoStage, probe.recordingSaveSucceeded) {
        val current = probe.photoStage to probe.recordingSaveSucceeded
        if ((probe.mode == CaptureMode.STILL && current.first == PhotoStage.SAVED && seenSave.first != PhotoStage.SAVED) ||
            (probe.mode == CaptureMode.CINE && current.second == true && seenSave.second != true)) notice = "保存しました"
        seenSave = current
    }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(4000); notice = null } }
    BackHandler {
        if (ownsCapture) {
            notice = if (recoveryRequired) "状態不明です。停止・復旧を選んでください" else "撮影・プレビュー復帰の完了を待ってください"
            return@BackHandler
        }
        val decision = captureBack(panel, editor.selected != null, editor.submitting != null, probe.recordingBusy,
            SystemClock.elapsedRealtime() - backAt < 2000)
        panel = decision.panel
        if (decision.closeRuler) editor = editor.close()
        if (decision.exit) exit()
        decision.note?.let { notice = it }
        if (decision.armExit) backAt = SystemClock.elapsedRealtime()
        if (panel == CapturePanel.NONE) confirmReset = false
    }

    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("撮影制御をリセット") },
        text = { Text("露出・WB・MF・照明対策・ロック・EVを標準に戻します。露出だけAUTOとは別の操作です。") },
        confirmButton = { TextButton(onClick = { confirmReset = false; if (unlocked) { editor = editor.close(); probe.resetAllControls() } }) { Text("全リセット") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } })

    CompositionLocalProvider(LocalCaptureAngle provides animatedAngle, LocalCaptureHorizontal provides orientation.horizontal) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink).safeDrawingPadding()) {
        val dimensions = CaptureLayout.measure(maxHeight.value, panel)
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.height(dimensions.previewHeight.dp).fillMaxWidth().background(Color.Black)) {
                val previewAvailableHeight = maxHeight.value
                // Keep the same PreviewView mounted while panels/rulers change: no UI-induced camera rebind.
                val previewTop = CaptureLayout.previewTop(probe.mode).dp
                if (permission) AndroidView(factory = { context ->
                    PreviewView(context).also {
                        it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        it.scaleType = PreviewView.ScaleType.FIT_CENTER
                        it.installFocusTap(onCenter = { if (focusAllowed) probe.focusCenter() }) { x, y ->
                            if (focusAllowed) probe.focusAt(x, y)
                        }
                        probe.initialize(it)
                    }
                }, update = {
                    it.importantForAccessibility = if (panel == CapturePanel.NONE) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                    modifier = Modifier.fillMaxSize().padding(top = previewTop))
                if (probe.recordingStage == RecordingStage.RECORDING) Box(Modifier.fillMaxSize().border(1.dp, Rec))
                val feedback = probe.focusFeedback
                if (feedback != null && panel == CapturePanel.NONE && !probe.recordingBusy) {
                    val focusColor = when (feedback.phase) { FocusPhase.PENDING -> Amber; FocusPhase.FAILED -> Failed; FocusPhase.COMPLETED -> if (feedback.afSucceeded == true) Accepted else Muted }
                    Column(Modifier.offset(x = (maxWidth * feedback.point.first - 24.dp).coerceIn(0.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp)),
                        y = (previewTop + (maxHeight - previewTop) * feedback.point.second - 24.dp).coerceIn(0.dp, (maxHeight - 48.dp).coerceAtLeast(0.dp))).size(48.dp).sensorRotation(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(32.dp).border(1.dp, focusColor))
                        Mono(if (feedback.phase == FocusPhase.PENDING) "要求" else if (feedback.phase == FocusPhase.FAILED) "失敗" else "API完了", focusColor, rotate = false)
                    }
                }
                if (!permission) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("カメラの許可が必要です", color = Foreground)
                    Text("拒否後は端末のアプリ設定からも変更できます", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = requestPermission) { Text("カメラを許可") }
                }
                if (panel != CapturePanel.DIAGNOSTICS) {
                    CaptureStatusBar(chromeInput(probe, captureReady),
                        settingsEnabled = unlocked || recoveryRequired, settingsSelected = panel == CapturePanel.SETTINGS,
                        onSettings = { if (unlocked || recoveryRequired) { editor = editor.close(); panel = if (panel == CapturePanel.SETTINGS) CapturePanel.NONE else CapturePanel.SETTINGS } },
                        rotation = animatedAngle, horizontal = orientation.horizontal)
                    if (!probe.bound && permission) Text("接続待ち・プレビュー停止中", Modifier.align(Alignment.Center).sensorRotation().background(Ink.copy(alpha = .7f)).padding(12.dp), color = Foreground, fontSize = 13.sp)
                    if (probe.bound && !probe.controlReady && !probe.busy && !recoveryRequired) {
                        val recoveryEpoch = probe.currentEpoch
                        Column(Modifier.align(Alignment.Center).sensorRotation().background(Ink.copy(alpha = .8f)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("設定状態を確認・復旧してください", color = Failed, fontSize = 13.sp)
                            if (probe.canRecoverControls) TextButton(onClick = { probe.recoverControls(recoveryEpoch) }) { Text("再接続") }
                        }
                    }
                    val message = captureMessage(probe.sharedSaveSupported,
                        thermalWarning = if (ProbeControls.shouldStopRecording(probe.thermalSeverity)) "熱: OS ${ProbeControls.thermalName(probe.thermalSeverity)}・録画開始不可" else null,
                        microphoneWarning = if (probe.mode == CaptureMode.CINE && probe.audioWanted && probe.audioPermissionDenied) "マイク許可なし: 録画は未開始。権限を許可するか設定で音声OFFにしてください" else null,
                        failure = probe.lastFailure, recordingFailure = probe.recordingFailure.takeIf { probe.mode == CaptureMode.CINE },
                        notice = pendingNotice ?: notice, phase = null)
                    val banner = message?.text
                    val bannerColor = when (message?.tone) { FeedbackTone.FAILURE -> Failed; FeedbackTone.WARNING -> Amber; else -> Muted }
                    if (recoveryRequired) Column(Modifier.align(Alignment.Center).widthIn(max = 300.dp).sensorRotation().background(Ink.copy(alpha = .9f)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("撮影状態が不明です。停止して接続をやり直してください", color = Failed, fontSize = 12.sp)
                        TextButton(onClick = probe::stop) { Text("停止・復旧") }
                    } else if (!(probe.bound && !probe.controlReady && !probe.busy)) banner?.let { Text(it, Modifier.align(if (orientation.horizontal) Alignment.Center else Alignment.TopCenter)
                        .padding(top = if (orientation.horizontal) 0.dp else 48.dp).widthIn(max = if (orientation.horizontal) 220.dp else 380.dp)
                        .sensorRotation().background(Ink.copy(alpha = .8f)).padding(8.dp), color = bannerColor, fontSize = 12.sp) }
                    if (panel == CapturePanel.NONE) Column(Modifier.align(Alignment.BottomCenter)) {
                        if (editor.selected != null) Box(Modifier.weight(1f, fill = false)) {
                            Ruler(probe, editor, probe.canSelectLiveControls, exposureEditable,
                                change = { editor = it }, auto = { editor = editor.cancel(); probe.setExposureAuto() }, refreshGeneration = measurementTick)
                        }
                        Box(Modifier.fillMaxWidth().background(if (editor.selected == null) Ink.copy(alpha = .5f) else Color(0xF00D0F10))) {
                            Box(Modifier.matchParentSize().blockPanelBackground())
                            val rows = captureKeyRows()
                            val keyMaxHeight = captureKeyMaxHeight(previewAvailableHeight, rows.size, editor.selected != null).dp
                            Column(Modifier.fillMaxWidth()) {
                                rows.forEach { keys ->
                                  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                                   keys.forEach { key ->
                                    val selected = editor.selected == key
                                    val available = controlAvailability(key, CaptureAccess(probe.bound, probe.busy, probe.recordingBusy, ownsCapture,
                                        probe.controlReady, probe.exposureOperationPending, requestedExposureMode(probe) == true, probe.selectedReport?.manualBlockReason,
                                        probe.exposureCompensationSupported, probe.selectedReport?.overrides?.manualFocusMaximum?.let { it > 0f } == true,
                                        liveSelectable = probe.canSelectLiveControls, exposureSelectable = probe.canSelectExposure))
                                    if (key.group == CaptureKey.ISO) {
                                        val shown = exposureKeyValue(key, probe, measurementTick)
                                        val feedback = exposureFeedback.getValue(key)
                                        CaptureControlKey(key.name, shown.value, shown.qualifier.ifEmpty { "実測未確認" }, selected,
                                            enabled = available.enabled, onUnavailable = { notice = available.reason }, unavailableReason = available.reason,
                                            onClick = { editor = editor.select(key) }, modifier = Modifier.weight(1f).heightIn(max = keyMaxHeight),
                                            displayLabel = captureKeyVisualLabel(key), unit = shown.unit, rotation = animatedAngle, horizontal = orientation.horizontal,
                                            valueColor = if (feedback.affected) sensorControlColor(feedback.tone) else Foreground,
                                            statusDescription = shown.contentDescription,
                                            widthSamples = if (key == CaptureKey.ISO) probe.exposureChoices.iso.map { it.toString() }
                                                else probe.exposureChoices.shutter.map { it.label.removeSuffix(" s") })
                                    } else {
                                        val field = when (key) {
                                            CaptureKey.FOCUS -> focusFeedback
                                            CaptureKey.WB -> SensorControlValue(if (wbFeedback.hasSelection) wbFeedback.selectedLabel else "—",
                                                stateTag = wbFeedback.stateTag, tone = wbFeedback.tone, affected = wbFeedback.affected)
                                            CaptureKey.EV -> if (requestedExposureMode(probe) == true) SensorControlValue("—", stateTag = "AUTO専用") else evFeedback
                                            else -> SensorControlValue("STD", stateTag = "未実装")
                                        }
                                        val tag = if (key == CaptureKey.FOCUS) field.modeTag else if (key == CaptureKey.LOOK) "未実装" else ""
                                        CaptureControlKey(key.name, field.value, tag, selected, available.enabled,
                                            onClick = { editor = editor.select(key) }, modifier = Modifier.weight(1f).heightIn(max = keyMaxHeight), unit = field.unit,
                                            onUnavailable = { notice = available.reason }, unavailableReason = available.reason,
                                            rotation = animatedAngle, horizontal = orientation.horizontal, valueColor = sensorControlColor(field.tone),
                                            statusDescription = "最新の指定 ${field.stateTag}。API受付値と実測は診断で確認",
                                            widthSamples = if (key == CaptureKey.WB) whiteBalanceOptions(probe.selectedReport?.overrides?.whiteBalanceModes ?: emptySet()).map { it.shortLabel }
                                                else listOf(field.value))
                                    }
                                   }
                                  }
                                }
                            }
                        }
                    }
                }
                if (panel == CapturePanel.SETTINGS) Box(Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth().height(dimensions.settingsHeight.dp).background(Sheet)) {
                    // Sibling shield consumes blank space without cancelling child scroll gestures.
                    Box(Modifier.matchParentSize().blockPanelBackground())
                    Column(Modifier.fillMaxSize()) {
                        PanelHeader("撮影設定 · ${probe.mode}") { panel = CapturePanel.NONE; confirmReset = false }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            Settings(probe, unlocked, diagnostics = { panel = CapturePanel.DIAGNOSTICS }, reset = { confirmReset = true })
                        }
                    }
                }
            }
            CaptureBar(probe, captureReady, interactable = panel != CapturePanel.DIAGNOSTICS && !ownsCapture,
                shutter = shutter@{
                    if (panel == CapturePanel.DIAGNOSTICS) return@shutter
                    if (probe.recordingStage in setOf(RecordingStage.STARTING, RecordingStage.RECORDING)) probe.stopRecording()
                    else if (probe.mode == CaptureMode.STILL) { if (editor.dirty) notice = "受付値で撮影・未適用の編集は保持"; probe.capture() }
                    else record()
                }, mode = { if (panel != CapturePanel.DIAGNOSTICS && unlocked) { editor = editor.close(); probe.selectMode(if (probe.mode == CaptureMode.STILL) CaptureMode.CINE else CaptureMode.STILL) } },
                placeholder = { if (panel != CapturePanel.DIAGNOSTICS) notice = "最新写真ビューアは未実装です。端末のギャラリーで確認してください" })
        }
        if (panel == CapturePanel.DIAGNOSTICS) Box(Modifier.fillMaxSize().background(Ink)) {
            Box(Modifier.matchParentSize().blockPanelBackground())
            Column(Modifier.fillMaxSize()) {
                PanelHeader("診断 · CameraX 1.6.2") { panel = CapturePanel.SETTINGS }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Diagnostics(probe, unlocked && !reportPending, export, measurementNow)
                }
            }
        }
    }
    }
}

@Composable private fun Ruler(probe: CameraProbe, editor: CaptureEditor, enabled: Boolean, exposureEnabled: Boolean,
    change: (CaptureEditor) -> Unit, auto: () -> Unit, refreshGeneration: Long) {
    val key = editor.selected ?: return
    if (key.group != CaptureKey.ISO) {
        OtherControlRuler(probe, editor, enabled, change)
        return
    }
    val choices = probe.exposureChoices
    val rulerEpoch = probe.currentEpoch
    // The AUTO observation starts the editor; subsequent frames do not move the selection.
    val samples = probe.previewExposureSamples
    val fresh = freshPreviewExposurePair(samples, rulerEpoch, refreshGeneration.let { SystemClock.elapsedRealtime() })
    var autoSeed by remember(rulerEpoch, key) { mutableStateOf<ManualExposure?>(null) }
    if (autoSeed == null && fresh != null) autoSeed = captureAutoRulerSeed(autoSeed, fresh)
    val source = probe.liveExposureTarget ?: probe.appliedExposure ?: autoSeed
    val pair = source?.let { choices.pair(it.iso, it.exposureNs) }
    val waitingForFresh = probe.liveExposureTarget == null && probe.appliedExposure == null && fresh == null
    val information = if (waitingForFresh) "AUTO実測待ち · 選択一時停止" else if (probe.manualExposure || probe.liveExposureTarget != null) "撮影target · 変更即反映" else "候補を選ぶと撮影targetへ反映"
    Box(Modifier.fillMaxWidth().background(Color(0xF00D0F10))) {
        // Shield only the ISO/SS ruler; children retain their drag gestures.
        Box(Modifier.matchParentSize().blockPanelBackground())
        Column(Modifier.fillMaxWidth()) {
            CaptureRulerHeading(key.name, information,
                horizontal = LocalCaptureHorizontal.current && !waitingForFresh,
                informationColor = Muted) {
                TextButton(onClick = auto,
                    enabled = probe.bound && (!probe.busy || probe.exposureOperationPending) && !probe.recordingBusy && probe.stillExposure?.ownsCapture != true,
                    modifier = Modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("AUTO", Modifier.sensorRotation(), fontSize = 12.sp)
                }
                TextButton(onClick = { change(editor.close()) }, enabled = editor.submitting == null,
                    modifier = Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) {
                    Text("×", Modifier.sensorRotation().semantics { contentDescription = "目盛りを閉じる" }, fontSize = 22.sp)
                }
            }
            val count = if (key == CaptureKey.ISO) choices.iso.size else choices.shutter.size
            if (pair == null || count == 0 || probe.selectedReport?.manualBlockReason != null) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    Text(probe.selectedReport?.manualBlockReason ?: if (count == 0) "端末/session範囲内に標準段階なし" else "AUTO実測待ち・値は未推定",
                        Modifier.padding(horizontal = 14.dp).sensorRotation(), color = Muted, fontSize = 12.sp)
                }
            } else {
                val index = if (key == CaptureKey.ISO) choices.iso.indexOf(pair.iso) else choices.shutter.indexOfFirst { it.exposureNs == pair.exposureNs }
                ExposureSelectionScale(key.name,
                    if (key == CaptureKey.ISO) choices.iso.map { it.toString() } else choices.shutter.map { it.label.removeSuffix(" s") },
                    index, exposureEnabled && !waitingForFresh, { probe.selectExposure(key, it) }, rotation = LocalCaptureAngle.current,
                    horizontal = LocalCaptureHorizontal.current, valueColor = if (probe.exposureOperationPending) Amber else Foreground,
                    allowCurrentSelection = !probe.manualExposure && probe.liveExposureTarget == null && probe.appliedExposure == null,
                    canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectExposure && probe.selectedReport?.manualBlockReason == null &&
                        (probe.liveExposureTarget != null || probe.appliedExposure != null ||
                            freshPreviewExposurePair(probe.previewExposureSamples, probe.currentEpoch, SystemClock.elapsedRealtime()) != null) })
            }
        }
    }
}
@Composable private fun OtherControlRuler(probe: CameraProbe, editor: CaptureEditor, enabled: Boolean,
    change: (CaptureEditor) -> Unit) {
    val key = editor.selected ?: return
    val rulerEpoch = probe.currentEpoch
    val intent = probe.liveControlIntent
    val focus = intent?.overrides?.focusDistance ?: probe.overrides.focusDistance
    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).background(Color(0xF00D0F10)).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { change(editor.close()) }, enabled = editor.submitting == null, modifier = Modifier.width(44.dp), contentPadding = PaddingValues(0.dp)) { Text("×", Modifier.sensorRotation(), fontSize = 22.sp) }
            Mono(key.name, Foreground, Modifier.weight(1f))
            if (key == CaptureKey.EV) ExposureZeroAction(intent?.evIndex ?: probe.acceptedEv,
                probe.exposureCompensationRange, probe.exposureCompensationStep,
                enabled && requestedExposureMode(probe) != true && probe.exposureCompensationSupported,
                probe::selectEv, textModifier = Modifier.sensorRotation(), horizontal = LocalCaptureHorizontal.current,
                canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls && requestedExposureMode(probe) != true && probe.exposureCompensationSupported })
            if (key == CaptureKey.FOCUS) FocusAutofocusAction(
                if (intent != null) intent.overrides.focusDistance else focus,
                probe.selectedReport?.autoFocusAvailable == true, enabled, probe::restoreAutofocus,
                textModifier = Modifier.sensorRotation(), horizontal = LocalCaptureHorizontal.current,
                canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls })
        }
        when (key.group) {
            CaptureKey.FOCUS -> FocusControlEditor(if (intent != null) intent.overrides.focusDistance else focus,
                probe.selectedReport?.overrides?.manualFocusMaximum, enabled,
                onSelect = probe::selectManualFocus, onAutofocus = probe::restoreAutofocus,
                rotation = LocalCaptureAngle.current, horizontal = LocalCaptureHorizontal.current,
                epoch = probe.currentEpoch, canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls })
            CaptureKey.EV -> ExposureCompensationControlEditor(intent?.evIndex ?: probe.acceptedEv, probe.exposureCompensationRange,
                probe.exposureCompensationStep, enabled && requestedExposureMode(probe) != true && probe.exposureCompensationSupported,
                onSelect = probe::selectEv, rotation = LocalCaptureAngle.current, horizontal = LocalCaptureHorizontal.current,
                canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls && requestedExposureMode(probe) != true && probe.exposureCompensationSupported })
            CaptureKey.WB -> WhiteBalanceControlEditor(probe.selectedReport?.overrides?.whiteBalanceModes ?: emptySet(),
                liveWhiteBalancePresentation(probe.overrides.whiteBalance, intent, probe.controlReceipt, probe.liveControlsPending), enabled, probe::selectWhiteBalance,
                rotation = LocalCaptureAngle.current, horizontal = LocalCaptureHorizontal.current,
                canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls })
            else -> Unit
        }

    }
}

private fun exposureKeyValue(key: CaptureKey, probe: CameraProbe, refreshGeneration: Long): ExposureValuePresentation =
    exposureValuePresentation(key, probe.previewExposureSamples.sample(key).takeIf { probe.bound }, probe.currentEpoch,
        refreshGeneration.let { SystemClock.elapsedRealtime() }, probe.exposureChoices, captureTarget = if (probe.bound) logicalCaptureTarget(probe.currentEpoch,
            probe.controlReceipt, probe.liveExposureTarget, probe.stillExposure?.target, probe.appliedExposure) else null)

@Composable private fun CaptureBar(probe: CameraProbe, ready: Boolean, interactable: Boolean, shutter: () -> Unit, mode: () -> Unit, placeholder: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(CaptureLayout.CAPTURE_RAIL.dp).background(Ink).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(52.dp).border(1.dp, Muted, RoundedCornerShape(6.dp)).clickable(enabled = interactable && !probe.busy && !probe.recordingBusy, onClick = placeholder), contentAlignment = Alignment.Center) { Text("写真\n未実装", modifier = Modifier.sensorRotation(), fontSize = 11.sp, textAlign = TextAlign.Center, color = Muted) }
        }
        CaptureShutter(chromeInput(probe, ready, interactable), shutter, rotation = LocalCaptureAngle.current)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = mode, enabled = interactable && !probe.busy && !probe.recordingBusy, modifier = Modifier.width(100.dp).height(48.dp), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Muted)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(if (probe.mode == CaptureMode.STILL) "●STILL" else "STILL", if (probe.mode == CaptureMode.STILL) Foreground else Muted, Modifier.weight(1f))
                    Mono(if (probe.mode == CaptureMode.CINE) "●CINE" else "CINE", if (probe.mode == CaptureMode.CINE) Foreground else Muted, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun Settings(probe: CameraProbe, enabled: Boolean, diagnostics: () -> Unit, reset: () -> Unit) =
    CaptureSettingsPanel(probe, enabled, diagnostics, reset)

@Composable private fun Diagnostics(probe: CameraProbe, enabled: Boolean, export: () -> Unit, nowMs: Long) =
    CaptureDiagnosticsPanel(probe, enabled, export, nowMs)

internal fun chromeInput(probe: CameraProbe, ready: Boolean = false, interactable: Boolean = true) = CaptureChromeInput(
    mode = probe.mode, bound = probe.bound, cameraId = probe.selectedId, photoFormat = CameraProbe.formatName(probe.format),
    boundVideoChoice = probe.activeVideoChoice,
    manualExposure = requestedExposureMode(probe), exposureModePhase = exposureModeFeedback(probe),
    recordingStage = probe.recordingStage, recordingDurationMs = probe.recordingDurationMs,
    photoStage = probe.photoStage, stillPhase = probe.stillExposure?.phase, ownsStill = probe.stillExposure?.ownsCapture == true,
    stillFilesTerminal = probe.stillExposure?.filesTerminal == true, stillFilesSaved = probe.stillExposure?.filesSaved,
    stillCapturePhase = probe.stillExposure?.captureReceipt?.phase,
    busy = probe.busy, captureReady = ready, interactable = interactable,
    thermalSeverity = probe.thermalSeverity.takeIf { it >= 0 }, audioWanted = probe.audioWanted,
    audioPermissionDenied = probe.audioPermissionDenied, audioState = probe.recordingAudioState,
    audioAmplitude = probe.recordingAudioAmplitude, recordingFailure = probe.recordingFailure,
    recordingSaveSucceeded = probe.recordingSaveSucceeded,
)

private fun requestedExposureMode(probe: CameraProbe): Boolean? = when {
    !probe.bound -> null
    probe.liveExposureTarget != null || probe.stillExposure != null -> true
    probe.controlReceipt.requested != null -> probe.controlReceipt.requested!!.exposure != null
    else -> null
}
private fun exposureModeFeedback(probe: CameraProbe): ControlPhase {
    if (!probe.bound) return ControlPhase.UNBOUND
    val phase = if (probe.exposureOperationPending) ControlPhase.PENDING else probe.controlReceipt.phase
    // A WB/MF snapshot does not change the already chosen exposure mode.
    return if (requestedExposureMode(probe) != probe.manualExposure || probe.controlReceipt.accepted == null) phase
        else ControlPhase.ACCEPTED
}

@Composable private fun PanelHeader(title: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Foreground, fontSize = 14.sp)
        TextButton(onClick = close, modifier = Modifier.size(44.dp).semantics { contentDescription = "閉じる" }, contentPadding = PaddingValues(0.dp)) { Text("×", fontSize = 22.sp) }
    }
}
@Composable private fun Mono(text: String, color: Color, modifier: Modifier = Modifier, size: Int = 11, rotate: Boolean = true) {
    Text(text, if (rotate) modifier.sensorRotation() else modifier, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
}
@Composable private fun Gear() {
    Icon(androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_preferences), contentDescription = "撮影設定", tint = Foreground, modifier = Modifier.size(24.dp).sensorRotation())
}
