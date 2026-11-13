package dev.cinematic.p0

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalView
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

@Composable fun CaptureTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = CaptureDesign.Text, background = CaptureDesign.Ink, surface = CaptureDesign.Panel,
        onSurface = CaptureDesign.Text, onBackground = CaptureDesign.Text, error = CaptureDesign.Failed), content = content)
}

@Composable
fun CaptureScreen(probe: CameraProbe, permission: Boolean, orientation: CaptureOrientation, requestPermission: () -> Unit,
    record: () -> Unit, export: () -> Unit, reportPending: Boolean, exit: () -> Unit,
    openPhotos: () -> Unit, photosPending: Boolean, capture: () -> Unit) {
    val hapticView = LocalView.current
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
    val unlocked = !photosPending && !probe.busy && !probe.recordingBusy && !ownsCapture
    val exposureEditable = !photosPending && probe.canSelectExposure
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
    val previewTap by rememberUpdatedState<(Float?, Float?) -> Unit>({ x, y ->
        when (previewTapAction(panel, editor.selected, focusAllowed)) {
            PreviewTapAction.DISMISS_RULER -> editor = editor.close()
            PreviewTapAction.FOCUS -> if (x == null || y == null) probe.focusCenter() else probe.focusAt(x, y)
            PreviewTapAction.IGNORE -> Unit
        }
    })
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

    if (confirmReset) CaptureResetDialog(dismiss = { confirmReset = false }, reset = {
        confirmReset = false
        if (unlocked) { editor = editor.close(); probe.resetAllControls() }
    })

    val landscapeDialHeight = rememberLandscapeDialHeight(probe, liveIntent)
    CompositionLocalProvider(LocalCaptureAngle provides animatedAngle, LocalCaptureHorizontal provides orientation.horizontal,
        LocalLandscapeDialHeight provides if (orientation.horizontal) landscapeDialHeight else null) {
    BoxWithConstraints(Modifier.fillMaxSize().background(CaptureDesign.Ink).safeDrawingPadding()) {
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
                        it.installFocusTap(onCenter = { previewTap(null, null) }) { x, y ->
                            previewTap(x, y)
                        }
                        probe.initialize(it)
                    }
                }, update = {
                    it.importantForAccessibility = if (previewAccessibilityVisible(panel, editor.selected)) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                        else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                },
                    modifier = Modifier.fillMaxSize().padding(top = previewTop))
                if (panel == CapturePanel.NONE && editor.selected != null) {
                    val interaction = remember { MutableInteractionSource() }
                    Box(Modifier.matchParentSize().clickable(interactionSource = interaction, indication = null,
                        role = Role.Button, onClick = { previewTap(null, null) })
                        .semantics { contentDescription = "目盛りを閉じる" })
                }
                if (probe.recordingStage == RecordingStage.RECORDING) Box(Modifier.fillMaxSize().border(1.dp, CaptureDesign.Recording))
                val feedback = probe.focusFeedback
                if (feedback != null && panel == CapturePanel.NONE && !probe.recordingBusy) {
                    val focusColor = when (feedback.phase) { FocusPhase.PENDING -> CaptureDesign.Amber; FocusPhase.FAILED -> CaptureDesign.Failed; FocusPhase.COMPLETED -> if (feedback.afSucceeded == true) CaptureDesign.Accepted else CaptureDesign.Muted }
                    Column(Modifier.offset(x = (maxWidth * feedback.point.first - 24.dp).coerceIn(0.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp)),
                        y = (previewTop + (maxHeight - previewTop) * feedback.point.second - 24.dp).coerceIn(0.dp, (maxHeight - 48.dp).coerceAtLeast(0.dp))).size(48.dp).sensorRotation(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(32.dp).border(1.dp, focusColor))
                        Mono(if (feedback.phase == FocusPhase.PENDING) "要求" else if (feedback.phase == FocusPhase.FAILED) "失敗" else "API完了", focusColor, rotate = false)
                    }
                }
                if (!permission) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("カメラの許可が必要です", color = CaptureDesign.Text)
                    Text("拒否後は端末のアプリ設定からも変更できます", color = CaptureDesign.Muted, fontSize = 12.sp)
                    TextButton(onClick = requestPermission) { Text("カメラを許可") }
                }
                if (panel != CapturePanel.DIAGNOSTICS) {
                    CaptureStatusBar(chromeInput(probe, captureReady),
                        settingsEnabled = unlocked || recoveryRequired, settingsSelected = panel == CapturePanel.SETTINGS,
                        onSettings = { if (unlocked || recoveryRequired) { editor = editor.close(); panel = if (panel == CapturePanel.SETTINGS) CapturePanel.NONE else CapturePanel.SETTINGS } },
                        rotation = animatedAngle, horizontal = orientation.horizontal)
                    val recoveryEpoch = probe.currentEpoch
                    val diagnosticsEnabled = unlocked || recoveryRequired
                    val displayFailureFallback = captureFailureFallback(diagnosticsEnabled)
                    val messages = captureMessages(
                        waitingForConnection = !probe.bound && permission,
                        controlRecovery = probe.bound && !probe.controlReady && !probe.busy && !recoveryRequired,
                        captureRecovery = recoveryRequired,
                        canReconnect = probe.canRecoverControls,
                        diagnosticsEnabled = diagnosticsEnabled,
                        thermalWarning = ProbeControls.shouldStopRecording(probe.thermalSeverity),
                        microphoneWarning = probe.mode == CaptureMode.CINE && probe.audioWanted && probe.audioPermissionDenied,
                        captureFailed = probe.lastFailure != null,
                        recordingFailed = probe.mode == CaptureMode.CINE && probe.recordingFailure != null,
                        notice = notice,
                        phase = pendingNotice,
                    )
                    val messageAction: (MessageAction) -> Unit = { action ->
                        when (action) {
                            MessageAction.RECONNECT -> probe.recoverControls(recoveryEpoch)
                            MessageAction.STOP_AND_RECOVER -> probe.stop()
                            MessageAction.OPEN_DIAGNOSTICS -> { if (diagnosticsEnabled) panel = CapturePanel.DIAGNOSTICS }
                            else -> Unit
                        }
                    }
                    CaptureMessageOverlay(messages, orientation.horizontal, recoveryRequired,
                        messageAction, Modifier.sensorRotation(), failureFallback = displayFailureFallback)
                    if (panel == CapturePanel.NONE) Column(Modifier.align(Alignment.BottomCenter)) {
                        if (editor.selected != null) Box(Modifier.weight(1f, fill = false)
                            .then(if (orientation.horizontal) Modifier.height(48.dp + landscapeDialHeight).background(CaptureDesign.Ruler) else Modifier)) {
                            Box(Modifier.matchParentSize().blockPanelBackground())
                            Ruler(probe, editor, probe.canSelectLiveControls, exposureEditable,
                                change = { editor = it }, auto = { editor = editor.cancel(); probe.setExposureAuto() }, refreshGeneration = measurementTick)
                        }
                        Box(Modifier.fillMaxWidth().background(if (editor.selected == null) CaptureDesign.Ink.copy(alpha = .5f) else CaptureDesign.Ruler)) {
                            Box(Modifier.matchParentSize().blockPanelBackground())
                            val rows = captureKeyRows()
                            val landscapeHeights = remember { mutableStateMapOf<CaptureKey, Float>() }
                            val commonHeight = if (orientation.horizontal) captureKeyCommonHeight(landscapeHeights.values) else 0f
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
                                            landscapeCommonHeight = commonHeight, onLandscapeNaturalHeight = { landscapeHeights[key] = it },
                                            valueColor = if (feedback.affected) sensorControlColor(feedback.tone) else CaptureDesign.Text,
                                            statusDescription = shown.contentDescription,
                                            widthSamples = if (key == CaptureKey.ISO) probe.exposureChoices.iso.map { it.toString() }
                                                else probe.exposureChoices.shutter.map { it.label.removeSuffix(" s") })
                                    } else {
                                        val field = when (key) {
                                            CaptureKey.FOCUS -> focusAutoPresentation(focusFeedback,
                                                autofocusObservation(probe, measurementTick), probe.selectedReport?.overrides?.manualFocusMaximum)
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
                                            landscapeCommonHeight = commonHeight, onLandscapeNaturalHeight = { landscapeHeights[key] = it },
                                            statusDescription = when {
                                                key == CaptureKey.FOCUS && field.modeTag == "AF 近似" -> "AFプレビュー実測の近似。${field.stateTag}。撮影設定値ではありません"
                                                key == CaptureKey.FOCUS && field.modeTag == "AF 待機" -> "AF実測待ち、現在値未確認。${field.stateTag}"
                                                key == CaptureKey.FOCUS && field.modeTag == "AF 候補なし" -> "手動フォーカス候補なし。${field.stateTag}"
                                                else -> "最新の指定 ${field.stateTag}。API受付値と実測は診断で確認"
                                            },
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
                    .fillMaxWidth().height(dimensions.settingsHeight.dp).background(CaptureDesign.Panel)) {
                    // Sibling shield consumes blank space without cancelling child scroll gestures.
                    Box(Modifier.matchParentSize().blockPanelBackground())
                    CaptureSettingsSheet(probe, unlocked,
                        close = { panel = CapturePanel.NONE; confirmReset = false },
                        diagnostics = { panel = CapturePanel.DIAGNOSTICS }, reset = { confirmReset = true })
                }
            }
            CaptureBar(probe, captureReady, interactable = panel != CapturePanel.DIAGNOSTICS && !ownsCapture,
                shutter = shutter@{
                    if (panel == CapturePanel.DIAGNOSTICS) return@shutter
                    val before = ShutterPressState(probe.mode, probe.photoStage, probe.recordingStage)
                    if (probe.recordingStage in setOf(RecordingStage.STARTING, RecordingStage.RECORDING)) probe.stopRecording()
                    else if (probe.mode == CaptureMode.STILL) { if (editor.dirty) notice = "受付値で撮影・未適用の編集は保持"; capture() }
                    else record()
                    val after = ShutterPressState(probe.mode, probe.photoStage, probe.recordingStage)
                    if (shutterPressAccepted(before, after)) hapticView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, 0)
                }, mode = { if (panel != CapturePanel.DIAGNOSTICS && unlocked) { editor = editor.close(); probe.selectMode(if (probe.mode == CaptureMode.STILL) CaptureMode.CINE else CaptureMode.STILL) } },
                photosEnabled = !photosPending && dev.cinematic.p0.photoroll.canOpenPhotoRoll(probe.busy, probe.recordingBusy,
                    ownsCapture, recoveryRequired || probe.controlReceipt.phase == ControlPhase.UNKNOWN),
                openPhotos = { if (panel != CapturePanel.DIAGNOSTICS) { editor = editor.close(); openPhotos() } })
        }
        if (panel == CapturePanel.DIAGNOSTICS) Box(Modifier.fillMaxSize().background(CaptureDesign.Ink)) {
            Box(Modifier.matchParentSize().blockPanelBackground())
            CaptureDiagnosticsSheet(probe, unlocked && !reportPending, export, measurementNow,
                settings = { panel = CapturePanel.SETTINGS })
        }
    }
    }
}

/** Measure all supported label families before a particular ruler is opened. */
@Composable private fun rememberLandscapeDialHeight(probe: CameraProbe, intent: ControlSnapshot?): Dp {
    val maximum = probe.selectedReport?.overrides?.manualFocusMaximum
    val anchor = if (intent != null) intent.overrides.focusDistance else probe.overrides.focusDistance
    val history = remember(probe.currentEpoch, maximum) { mutableStateListOf<Float>() }
    // A formerly selected exact MF anchor can remain in the active editor after the value changes.
    SideEffect { if (anchor != null && anchor.isFinite() && anchor !in history) history.add(anchor) }
    val anchors = (history + listOfNotNull(anchor)).distinct()
    val choices = probe.exposureChoices
    val range = probe.exposureCompensationRange
    val step = probe.exposureCompensationStep
    val wbModes = probe.selectedReport?.overrides?.whiteBalanceModes ?: emptySet()
    val labels = remember(choices, range, step, maximum, anchors, wbModes) {
        landscapeDialSizingLabels(choices, range, step, maximum, anchors, wbModes)
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val widths = remember(labels, density, measurer) {
        labels.map { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
    }
    return with(density) { landscapeDialMeasuredHeightPx(56.dp.roundToPx(), 12.dp.roundToPx(), widths).toDp() }
}

@Composable private fun Ruler(probe: CameraProbe, editor: CaptureEditor, enabled: Boolean, exposureEnabled: Boolean,
    change: (CaptureEditor) -> Unit, auto: () -> Unit, refreshGeneration: Long) {
    val key = editor.selected ?: return
    if (key.group != CaptureKey.ISO) {
        OtherControlRuler(probe, editor, enabled, change, refreshGeneration)
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
    Box(Modifier.fillMaxWidth().background(CaptureDesign.Ruler)) {
        // Shield only the ISO/SS ruler; children retain their drag gestures.
        Box(Modifier.matchParentSize().blockPanelBackground())
        Column(Modifier.fillMaxWidth()) {
            CaptureRulerHeading(captureKeyVisualLabel(key), onClose = { change(editor.close()) },
                closeEnabled = editor.submitting == null, rotation = LocalCaptureAngle.current,
                horizontal = LocalCaptureHorizontal.current, waiting = waitingForFresh) {
                TextButton(onClick = auto,
                    enabled = probe.bound && (!probe.busy || probe.exposureOperationPending) && !probe.recordingBusy && probe.stillExposure?.ownsCapture != true,
                    modifier = Modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    SensorShortcutText("AUTO", Modifier.sensorRotation(), LocalCaptureHorizontal.current)
                }
            }
            val count = if (key == CaptureKey.ISO) choices.iso.size else choices.shutter.size
            if (pair == null || count == 0 || probe.selectedReport?.manualBlockReason != null) {
                Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                    Text(probe.selectedReport?.manualBlockReason ?: if (count == 0) "端末/session範囲内に標準段階なし" else "AUTO実測待ち・値は未推定",
                        Modifier.padding(horizontal = 14.dp).sensorRotation(), color = CaptureDesign.Muted, fontSize = 12.sp)
                }
            } else {
                val index = if (key == CaptureKey.ISO) choices.iso.indexOf(pair.iso) else choices.shutter.indexOfFirst { it.exposureNs == pair.exposureNs }
                ExposureSelectionScale(if (key == CaptureKey.SHUTTER) "シャッタースピード" else key.name,
                    if (key == CaptureKey.ISO) choices.iso.map { it.toString() } else choices.shutter.map { it.label.removeSuffix(" s") },
                    index, exposureEnabled && !waitingForFresh, { probe.selectExposure(key, it) }, rotation = LocalCaptureAngle.current,
                    horizontal = LocalCaptureHorizontal.current, valueColor = if (probe.exposureOperationPending) CaptureDesign.Amber else CaptureDesign.Text,
                    descriptions = if (key == CaptureKey.ISO) choices.iso.map { it.toString() }
                        else choices.shutter.map { captureShutterDescription(it.label.removeSuffix(" s")) },
                    allowCurrentSelection = !probe.manualExposure && probe.liveExposureTarget == null && probe.appliedExposure == null,
                    canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectExposure && probe.selectedReport?.manualBlockReason == null &&
                        (probe.liveExposureTarget != null || probe.appliedExposure != null ||
                            freshPreviewExposurePair(probe.previewExposureSamples, probe.currentEpoch, SystemClock.elapsedRealtime()) != null) })
            }
        }
    }
}
@Composable private fun OtherControlRuler(probe: CameraProbe, editor: CaptureEditor, enabled: Boolean,
    change: (CaptureEditor) -> Unit, refreshGeneration: Long) {
    val key = editor.selected ?: return
    val rulerEpoch = probe.currentEpoch
    val intent = probe.liveControlIntent
    val focus = if (intent != null) intent.overrides.focusDistance else probe.overrides.focusDistance
    val observedFocus = if (key == CaptureKey.FOCUS && focus == null) autofocusObservation(probe, refreshGeneration) else null
    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).background(CaptureDesign.Ruler),
        horizontalAlignment = Alignment.CenterHorizontally) {
        CaptureRulerHeading(key.name, onClose = { change(editor.close()) }, closeEnabled = editor.submitting == null,
            rotation = LocalCaptureAngle.current, horizontal = LocalCaptureHorizontal.current,
            waiting = key == CaptureKey.FOCUS && focus == null && observedFocus == null &&
                probe.selectedReport?.overrides?.manualFocusMaximum?.let { it.isFinite() && it > 0f } == true) {
            if (key == CaptureKey.EV) ExposureZeroAction(intent?.evIndex ?: probe.acceptedEv,
                probe.exposureCompensationRange, probe.exposureCompensationStep,
                enabled && requestedExposureMode(probe) != true && probe.exposureCompensationSupported,
                probe::selectEv, textModifier = Modifier.sensorRotation(), horizontal = LocalCaptureHorizontal.current,
                canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls && requestedExposureMode(probe) != true && probe.exposureCompensationSupported })
            if (key == CaptureKey.FOCUS) FocusAutofocusAction(
                if (intent != null) intent.overrides.focusDistance else focus,
                probe.selectedReport?.autoFocusAvailable == true, enabled, probe::restoreAutofocus,
                textModifier = Modifier.sensorRotation(), horizontal = LocalCaptureHorizontal.current,
                canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls &&
                    probe.selectedReport?.autoFocusAvailable == true },
                manualNow = { probe.liveControlIntent?.let { it.overrides.focusDistance != null }
                    ?: (probe.selectedControls.overrides.focusDistance != null) })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            when (key.group) {
                CaptureKey.FOCUS -> FocusControlEditor(focus,
                    probe.selectedReport?.overrides?.manualFocusMaximum, enabled,
                    observedAutoFocus = observedFocus, onSelect = probe::selectManualFocus,
                    rotation = LocalCaptureAngle.current, horizontal = LocalCaptureHorizontal.current,
                    epoch = probe.currentEpoch, canSelect = { probe.currentEpoch == rulerEpoch && probe.canSelectLiveControls },
                    autoCanSelect = { focusDialInputAllowed(probe.liveControlIntent, probe.overrides.focusDistance,
                        autofocusObservation(probe, refreshGeneration) != null) },
                    autoStillActive = { focusDialAutoActive(probe.liveControlIntent, probe.overrides.focusDistance) })
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
}

/** Read the sample before the clock; the generation also expires values when frames stop. */
private fun autofocusObservation(probe: CameraProbe, refreshGeneration: Long): Float? {
    val sample = probe.previewFocusSample
    val now = refreshGeneration.let { SystemClock.elapsedRealtime() }
    return freshAutofocusSample(sample, probe.currentEpoch, now,
        probe.selectedReport?.overrides?.manualFocusMaximum)?.actual?.focus?.takeIf { probe.bound }
}

private fun exposureKeyValue(key: CaptureKey, probe: CameraProbe, refreshGeneration: Long): ExposureValuePresentation =
    exposureValuePresentation(key, probe.previewExposureSamples.sample(key).takeIf { probe.bound }, probe.currentEpoch,
        refreshGeneration.let { SystemClock.elapsedRealtime() }, probe.exposureChoices, captureTarget = if (probe.bound) logicalCaptureTarget(probe.currentEpoch,
            probe.controlReceipt, probe.liveExposureTarget, probe.stillExposure?.target, probe.appliedExposure) else null)

@Composable private fun CaptureBar(probe: CameraProbe, ready: Boolean, interactable: Boolean, shutter: () -> Unit, mode: () -> Unit,
    photosEnabled: Boolean, openPhotos: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(CaptureLayout.CAPTURE_RAIL.dp).background(CaptureDesign.Ink).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(52.dp).alpha(if (photosEnabled) 1f else .4f).border(1.dp, CaptureDesign.Muted, RoundedCornerShape(6.dp))
                .clickable(enabled = interactable && photosEnabled, role = Role.Button, onClick = openPhotos), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_gallery), "写真一覧を開く",
                    Modifier.size(28.dp).sensorRotation(), tint = CaptureDesign.Muted)
            }
        }
        CaptureShutter(chromeInput(probe, ready, interactable), shutter, rotation = LocalCaptureAngle.current)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            CaptureModeSwitch(probe.mode, interactable && !probe.busy && !probe.recordingBusy, mode)
        }
    }
}

@Composable private fun CaptureModeSwitch(mode: CaptureMode, enabled: Boolean, onToggle: () -> Unit) {
    CaptureModeSwitchSurface(mode == CaptureMode.CINE, Modifier.alpha(if (enabled) 1f else .4f)
        .toggleable(value = mode == CaptureMode.CINE, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
        .semantics { contentDescription = "撮影モード"; stateDescription = captureModeSwitchState(mode) }) {
            listOf(CaptureMode.STILL, CaptureMode.CINE).forEach { choice ->
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val density = LocalDensity.current
                    val horizontal = LocalCaptureHorizontal.current
                    val angle = LocalCaptureAngle.current
                    val measurer = rememberTextMeasurer()
                    val room = with(density) { captureModeLabelRoom(IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()), horizontal) }
                    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    val size = remember(choice, room, density, measurer) {
                        controlValueFontSize(11f) { candidate ->
                            val measured = measurer.measure(choice.name, style = style.copy(fontSize = candidate.sp), maxLines = 1, softWrap = false).size
                            measured.width <= room.width && measured.height <= room.height
                        }
                    }
                    Text(choice.name, Modifier.layout { measurable, constraints ->
                        val text = measurable.measure(controlScaleTextConstraints(constraints, horizontal))
                        val placement = controlScaleTextPlacement(IntSize(text.width, text.height), horizontal)
                        layout(placement.size.width, placement.size.height) { text.place(placement.offset.x, placement.offset.y) }
                    }.graphicsLayer { rotationZ = angle }, color = if (mode == choice) CaptureDesign.Ink else CaptureDesign.Muted,
                        fontSize = size.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
                }
            }
    }
}

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

@Composable private fun Mono(text: String, color: Color, modifier: Modifier = Modifier, size: Int = 11, rotate: Boolean = true) {
    Text(text, if (rotate) modifier.sensorRotation() else modifier, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
}
