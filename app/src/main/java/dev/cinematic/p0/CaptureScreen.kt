package dev.cinematic.p0

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.math.BigDecimal
import java.util.Locale

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
private fun nsText(ns: Long?): String = ExposureStops.label(ns)
private fun milliseconds(ns: Long): String = BigDecimal.valueOf(ns, 6).stripTrailingZeros().toPlainString()

@Composable
fun CaptureScreen(probe: CameraProbe, permission: Boolean, orientation: CaptureOrientation, requestPermission: () -> Unit,
    record: () -> Unit, export: () -> Unit, reportPending: Boolean, exit: () -> Unit) {
    var editor by remember { mutableStateOf(CaptureEditor(epoch = probe.currentEpoch)) }
    var panel by remember { mutableStateOf(CapturePanel.NONE) }
    var confirmReset by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var backAt by remember { mutableLongStateOf(0L) }
    val stillExposure = probe.stillExposure
    val stillPresentation = stillExposure?.presentation()
    val ownsCapture = stillExposure?.ownsCapture == true
    val recoveryRequired = stillPresentation?.recoveryRequired == true
    val unlocked = !probe.busy && !probe.recordingBusy && !ownsCapture
    val editable = unlocked && probe.bound && probe.controlReady
    val exposureEditable = probe.canSelectExposure
    val animatedAngle by animateFloatAsState(orientation.uiAngle, tween(250), label = "capture-elements")
    val captureReady = unlocked && probe.bound && probe.controlReady && probe.sharedSaveSupported && (stillExposure == null || stillExposure.canCapture)
    val receipt = probe.controlReceipt
    val focusAllowed by rememberUpdatedState(panel == CapturePanel.NONE && editor.submitting == null && !ownsCapture && !probe.busy)
    LaunchedEffect(probe.currentEpoch) { editor = editor.session(probe.currentEpoch) }
    LaunchedEffect(receipt) { editor = editor.complete(receipt) }
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
    LaunchedEffect(editor.note) { editor.note?.let { notice = it } }
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

    fun submit(action: () -> Unit) {
        if (editor.epoch != probe.currentEpoch) { editor = editor.session(probe.currentEpoch); return }
        if (!editable) return
        action()
        editor = editor.sent(probe.controlReceipt.sequence).complete(probe.controlReceipt)
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
                if (probe.recordingBusy) Box(Modifier.fillMaxSize().border(1.dp, Rec))
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
                    if (probe.bound && !probe.controlReady && !probe.busy) Text("設定状態を確認・復旧してください", Modifier.align(Alignment.Center).sensorRotation().background(Ink.copy(alpha = .8f)).padding(12.dp), color = Failed, fontSize = 13.sp)
                    val message = captureMessage(probe.sharedSaveSupported,
                        thermalWarning = if (ProbeControls.shouldStopRecording(probe.thermalSeverity)) "熱: OS ${ProbeControls.thermalName(probe.thermalSeverity)}・録画開始不可" else null,
                        microphoneWarning = if (probe.mode == CaptureMode.CINE && probe.audioWanted && probe.audioPermissionDenied) "マイク許可なし: 録画は未開始。権限を許可するか設定で音声OFFにしてください" else null,
                        failure = probe.lastFailure, recordingFailure = probe.recordingFailure.takeIf { probe.mode == CaptureMode.CINE },
                        notice = notice, phase = stillPresentation?.let { "${it.stage} · ${it.phase}" })
                    val banner = message?.text
                    val bannerColor = when (message?.tone) { FeedbackTone.FAILURE -> Failed; FeedbackTone.WARNING -> Amber; else -> Muted }
                    if (recoveryRequired) Column(Modifier.align(Alignment.Center).widthIn(max = 300.dp).sensorRotation().background(Ink.copy(alpha = .9f)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("撮影状態が不明です。停止して接続をやり直してください", color = Failed, fontSize = 12.sp)
                        TextButton(onClick = probe::stop) { Text("停止・復旧") }
                    } else if (!(orientation.horizontal && editor.selected?.group == CaptureKey.ISO && panel == CapturePanel.NONE)) banner?.let { Text(it, Modifier.align(if (orientation.horizontal) Alignment.Center else Alignment.TopCenter).padding(top = if (orientation.horizontal) 0.dp else 48.dp).widthIn(max = if (orientation.horizontal) 220.dp else 380.dp).sensorRotation().background(Ink.copy(alpha = .8f)).padding(8.dp), color = bannerColor, fontSize = 12.sp) }
                    if (orientation.horizontal && editor.selected?.group == CaptureKey.ISO && panel == CapturePanel.NONE && !recoveryRequired) {
                        val exposureInfo = stillPresentation?.let { "撮影target ${it.captureTarget}\npreview target ${it.previewTarget}\npreview API ${it.previewApi}\npreview実測 ${it.previewActual}" }
                            ?: "撮影target ${probe.appliedExposure?.let { "ISO ${it.iso} · ${nsText(it.exposureNs)}" } ?: "AUTO"}\npreview実測 ISO ${probe.sample?.actual?.iso ?: "—"} · ${nsText(probe.sample?.actual?.exposureNs)}"
                        Text(listOfNotNull(banner, exposureInfo).joinToString("\n"),
                            Modifier.align(Alignment.Center).widthIn(max = 180.dp).sensorRotation().background(Ink.copy(alpha = .7f)).padding(8.dp), color = bannerColor, fontSize = 11.sp)
                    }
                    if (panel == CapturePanel.NONE) Column(Modifier.align(Alignment.BottomCenter)) {
                        if (editor.selected != null) Ruler(probe, editor, editable, exposureEditable,
                            change = { editor = it }, apply = { submit {
                                when (editor.selected?.group) {
                                    CaptureKey.FOCUS -> editor.focus?.let { probe.editFocus(it); probe.applyManualFocus() }
                                    else -> Unit
                                }
                            } }, auto = { editor = editor.cancel(); probe.setExposureAuto() })
                        Row(Modifier.fillMaxWidth().height(72.dp).background(if (editor.selected == null) Ink.copy(alpha = .5f) else Color(0xF00D0F10))) {
                            CaptureKey.entries.forEach { key ->
                                val selected = editor.selected == key
                                val value = keyValue(key, editor, probe)
                                val available = controlAvailability(key, CaptureAccess(probe.bound, probe.busy, probe.recordingBusy, ownsCapture,
                                    probe.controlReady, probe.exposureOperationPending, probe.manualExposure, probe.selectedReport?.manualBlockReason,
                                    probe.exposureCompensationSupported, probe.selectedReport?.overrides?.manualFocusMaximum?.let { it > 0f } == true))
                                if (key.group == CaptureKey.ISO) {
                                    val tag = keyTag(key, editor, probe)
                                    CaptureControlKey(key.name, value, tag, selected,
                                        enabled = available.enabled, onUnavailable = { notice = available.reason }, unavailableReason = available.reason,
                                        onClick = { editor = editor.select(key) }, modifier = Modifier.weight(1f),
                                        unit = keyUnit(key, value), rotation = animatedAngle, horizontal = orientation.horizontal,
                                        valueColor = if (probe.exposureOperationPending) Amber else Foreground,
                                        tagColor = when (tag) { "送信中", "要求待機" -> Amber; "不明", "失敗" -> Failed; else -> Muted })
                                } else Box(Modifier.weight(1f).fillMaxHeight().background(if (selected) Color(0xFF1E2225) else Color.Transparent)
                                    .alpha(if (available.enabled) 1f else .45f)
                                    .clickable { if (!available.enabled) notice = available.reason else editor = editor.select(key) }
                                    .semantics { contentDescription = "${key.name} $value ${available.reason ?: ""}" }, contentAlignment = Alignment.Center) {
                                    Column(Modifier.sensorRotation(), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Mono(key.name, Muted, rotate = false)
                                        if (key == CaptureKey.FOCUS) {
                                            val focus = focusPresentation(probe.overrides.focusDistance, editor.focus, probe.controlReceipt)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Mono(focus.value, sensorControlColor(focus.tone), Modifier.weight(1f, fill = false), size = 16, rotate = false)
                                                if (focus.unit.isNotEmpty()) Mono(focus.unit, Muted, Modifier.padding(start = 2.dp), size = 11, rotate = false)
                                            }
                                            Mono(if (focus.stateTag in setOf("編集中", "送信中", "不明", "入力拒否", "入力不正")) focus.stateTag else focus.modeTag,
                                                sensorControlColor(focus.tone), size = 11, rotate = false)
                                        } else {
                                            val wb = if (key == CaptureKey.WB) whiteBalancePresentation(probe.overrides.whiteBalance, probe.controlReceipt) else null
                                            Mono(wb?.selectedLabel ?: value, wb?.let { sensorControlColor(it.tone) } ?: Foreground, size = 16, rotate = false)
                                            Mono(wb?.stateTag ?: keyTag(key, editor, probe), wb?.let { sensorControlColor(it.tone) } ?: Muted, size = 11, rotate = false)
                                        }
                                    }
                                    if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Foreground))
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
                    Diagnostics(probe, unlocked && !reportPending, export)
                }
            }
        }
    }
    }
}

@Composable private fun Ruler(probe: CameraProbe, editor: CaptureEditor, enabled: Boolean, exposureEnabled: Boolean,
    change: (CaptureEditor) -> Unit, apply: () -> Unit, auto: () -> Unit) {
    val key = editor.selected ?: return
    if (key.group != CaptureKey.ISO) {
        OtherControlRuler(probe, editor, enabled, exposureEnabled, change, apply, auto)
        return
    }
    val actual = probe.sample?.actual
    val choices = probe.exposureChoices
    val source = probe.liveExposureTarget ?: probe.appliedExposure ?: actual?.let {
        if (it.iso != null && it.exposureNs != null) ManualExposure(it.iso, it.exposureNs) else null
    }
    val pair = source?.let { choices.pair(it.iso, it.exposureNs) }
    val tag = keyTag(key, editor, probe)
    val value = keyValue(key, editor, probe)
    val information = if (probe.stillExposure != null) "${if (probe.exposureOperationPending) "最新要求" else "撮影target"} · preview実測 ${if (key == CaptureKey.ISO) actual?.iso ?: "—" else nsText(actual?.exposureNs)}"
        else "受付 ${if (key == CaptureKey.ISO) probe.appliedExposure?.iso ?: "AUTO" else nsText(probe.appliedExposure?.exposureNs)} · 実測 ${if (key == CaptureKey.ISO) actual?.iso ?: "—" else nsText(actual?.exposureNs)}"
    Box(Modifier.fillMaxWidth().height(104.dp).background(Color(0xF00D0F10))) {
        // Shield only the ISO/SS ruler; children retain their drag gestures.
        Box(Modifier.matchParentSize().blockPanelBackground())
        Column(Modifier.fillMaxSize()) {
            CaptureRulerHeading(key.name, information, value + keyUnit(key, value).let { if (it.isEmpty()) "" else " $it" }, tag,
                horizontal = LocalCaptureHorizontal.current,
                valueColor = if (probe.exposureOperationPending || editor.dirty) Amber else Foreground,
                informationColor = if (tag == "不明" || tag == "失敗") Failed else Muted) {
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
                    index, exposureEnabled, { probe.selectExposure(key, it) }, rotation = LocalCaptureAngle.current,
                    horizontal = LocalCaptureHorizontal.current, valueColor = if (probe.exposureOperationPending) Amber else Foreground)
            }
        }
    }
}
@Composable private fun OtherControlRuler(probe: CameraProbe, editor: CaptureEditor, enabled: Boolean, exposureEnabled: Boolean,
    change: (CaptureEditor) -> Unit, apply: () -> Unit, auto: () -> Unit) {
    val key = editor.selected ?: return
    val actual = probe.sample?.actual
    val acceptedExposure = probe.appliedExposure
    val base = probe.liveExposureTarget?.let { ExposureDraft(it.iso.toString(), milliseconds(it.exposureNs)) } ?: editor.exposure ?: acceptedExposure?.let { ExposureDraft(it.iso.toString(), milliseconds(it.exposureNs)) }
        ?: actual?.let { values -> if (values.iso != null && values.exposureNs != null) ExposureDraft(values.iso.toString(), milliseconds(values.exposureNs)) else null }
    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).background(Color(0xF00D0F10)).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { change(editor.close()) }, enabled = editor.submitting == null, modifier = Modifier.width(44.dp), contentPadding = PaddingValues(0.dp)) { Text("×", Modifier.sensorRotation(), fontSize = 22.sp) }
            Mono(if (LocalCaptureHorizontal.current) key.name else "${key.name} · ${if (key.group == CaptureKey.ISO && probe.exposureOperationPending) if (probe.exposureQueued) "最新要求待機" else "送信中" else if (editor.submitting != null) "送信中" else if (editor.dirty) "編集中" else if (probe.controlReceipt.phase == ControlPhase.ACCEPTED) "受付済" else "受付 / 実測"}", if (editor.dirty) Amber else Foreground, Modifier.weight(1f))
            if (key.group == CaptureKey.ISO) TextButton(onClick = auto, enabled = probe.bound && (!probe.busy || probe.exposureOperationPending) && !probe.recordingBusy) { Text("AUTO", Modifier.sensorRotation(), fontSize = 11.sp) }
            if (key == CaptureKey.FOCUS) {
                TextButton(onClick = { change(editor.cancel()) }, enabled = editor.submitting == null) { Text("取消", Modifier.sensorRotation(), fontSize = 11.sp) }
                TextButton(onClick = apply, enabled = enabled && editor.dirty) { Text("適用", Modifier.sensorRotation(), fontSize = 11.sp) }
            }
        }
        when (key.group) {
            CaptureKey.ISO -> {
                val choices = probe.exposureChoices
                val pair = base?.let { input ->
                    input.iso.toIntOrNull()?.let { iso -> runCatching { choices.pair(iso, Exposure.millisecondsToNs(input.milliseconds)) }.getOrNull() }
                }
                val count = if (key == CaptureKey.ISO) choices.iso.size else choices.shutter.size
                if (pair == null || count == 0 || probe.selectedReport?.manualBlockReason != null) {
                    Text(probe.selectedReport?.manualBlockReason ?: if (count == 0) "端末/session範囲内に標準段階なし" else "AUTO実測待ち・値は未推定", color = Muted, fontSize = 12.sp)
                } else {
                    val index = if (key == CaptureKey.ISO) choices.iso.indexOf(pair.iso) else choices.shutter.indexOfFirst { it.exposureNs == pair.exposureNs }
                    Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("◀", color = Muted, fontSize = 11.sp)
                        if (count > 1) Slider(index.coerceAtLeast(0).toFloat(), onValueChange = { next ->
                            val selected = kotlin.math.round(next).toInt().coerceIn(0, count - 1)
                            probe.selectExposure(key, selected)
                        }, valueRange = 0f..(count - 1).toFloat(), steps = (count - 2).coerceAtLeast(0),
                            enabled = exposureEnabled, modifier = Modifier.weight(1f))
                        else TextButton(onClick = { probe.selectExposure(key, 0) }, enabled = exposureEnabled, modifier = Modifier.weight(1f)) { Text("選択") }
                        Text("▶", color = Muted, fontSize = 11.sp)
                        Mono(if (key == CaptureKey.ISO) pair.iso.toString() else ExposureStops.label(pair.exposureNs), Amber, Modifier.width(88.dp))
                    }
                }
            }
            CaptureKey.FOCUS -> FocusControlEditor(probe.overrides.focusDistance, actual?.focus, editor.focus,
                probe.selectedReport?.overrides?.manualFocusMaximum, enabled,
                onDraft = { value -> value.toFloatOrNull()?.let { change(editor.copy(focus = String.format(Locale.ROOT, "%.4f", it))) } },
                onAutofocus = { change(editor.cancel()); probe.restoreAutofocus() },
                rotation = LocalCaptureAngle.current, horizontal = LocalCaptureHorizontal.current)
            CaptureKey.EV -> Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { probe.compensateEv(-1) }, enabled = enabled && !probe.manualExposure && probe.exposureCompensationSupported) { Text("−") }
                Mono(if (probe.manualExposure) "MAN中は無効" else "EV ${evText(probe)}", Foreground)
                TextButton(onClick = { probe.compensateEv(1) }, enabled = enabled && !probe.manualExposure && probe.exposureCompensationSupported) { Text("＋") }
            }
            CaptureKey.WB -> WhiteBalanceControlEditor(probe.selectedReport?.overrides?.whiteBalanceModes ?: emptySet(),
                whiteBalancePresentation(probe.overrides.whiteBalance, probe.controlReceipt), enabled, probe::selectWhiteBalance,
                rotation = LocalCaptureAngle.current, horizontal = LocalCaptureHorizontal.current)
            else -> Unit
        }
        val observed = probe.sample
        if (key.group == CaptureKey.ISO && probe.liveExposureTarget != null) {
            val target = probe.liveExposureTarget!!
            Mono("要求 ISO ${target.iso} ${ExposureStops.label(target.exposureNs)} / 受付 ${probe.appliedExposure?.let { "ISO ${it.iso} ${ExposureStops.label(it.exposureNs)}" } ?: "AUTO"}", Amber)
        }
        val match = observed?.corresponds(probe.controlReceipt) == true
        if (!LocalCaptureHorizontal.current) Mono("実測 ISO ${actual?.iso ?: "—"} · ${nsText(actual?.exposureNs)} · ${if (match) "要求対応・一致は別確認" else "更新/対応確認待ち"}", Muted, Modifier.padding(vertical = 5.dp))
        if (probe.controlReceipt.phase in setOf(ControlPhase.REJECTED, ControlPhase.UNKNOWN)) Text(probe.controlReceipt.detail, color = Failed, fontSize = 11.sp)
    }
}

private fun keyUnit(key: CaptureKey, value: String): String = when {
    value == "AUTO" || value == "—" -> ""
    key == CaptureKey.SHUTTER -> "s"
    key == CaptureKey.FOCUS && value != "AF" -> "LU"
    else -> ""
}

private fun keyTag(key: CaptureKey, editor: CaptureEditor, probe: CameraProbe): String = when {
    key == CaptureKey.LOOK -> "未実装"
    key == CaptureKey.EV && probe.manualExposure -> "無効"
    probe.stillExposure?.phase == StillExposurePhase.UNKNOWN -> "不明"
    probe.stillExposure?.ownsCapture == true -> when (probe.stillExposure?.phase) {
        StillExposurePhase.CAPTURE_APPLYING -> "準備中"
        StillExposurePhase.CAPTURING -> "撮影中"
        else -> "復帰中"
    }
    key.group == CaptureKey.ISO && probe.exposureOperationPending -> if (probe.exposureQueued) "要求待機" else "送信中"
    editor.selected == key && editor.dirty -> "編集中"
    probe.controlReceipt.phase == ControlPhase.UNKNOWN -> "不明"
    probe.controlReceipt.phase == ControlPhase.REJECTED -> "失敗"
    probe.controlReceipt.phase == ControlPhase.PENDING -> "送信中"
    key.group == CaptureKey.ISO -> if (probe.manualExposure) "MAN" else "AUTO"
    key == CaptureKey.FOCUS -> if (editor.focus != null || probe.overrides.focusDistance != null) "MF" else "AF"
    key == CaptureKey.WB -> if (probe.overrides.whiteBalance == null || probe.overrides.whiteBalance == 1) "AUTO" else "MAN"
    else -> "AUTO"
}
private fun evText(probe: CameraProbe): String = probe.acceptedEv?.let { index -> probe.exposureCompensationStep?.let { String.format(Locale.ROOT, "%+.1f", index * it) } } ?: "—"
private fun keyValue(key: CaptureKey, editor: CaptureEditor, probe: CameraProbe): String = if (probe.controlReceipt.phase == ControlPhase.UNKNOWN && !probe.exposureOperationPending) "—" else when (key) {
    CaptureKey.ISO -> probe.liveExposureTarget?.takeIf { probe.exposureOperationPending }?.iso?.toString() ?: if (!probe.manualExposure) "AUTO" else probe.appliedExposure?.iso?.toString() ?: "—"
    CaptureKey.SHUTTER -> if (probe.exposureOperationPending) ExposureStops.label(probe.liveExposureTarget?.exposureNs).removeSuffix(" s") else if (!probe.manualExposure) "AUTO" else ExposureStops.label(probe.appliedExposure?.exposureNs).removeSuffix(" s")
    CaptureKey.EV -> if (probe.manualExposure) "—" else evText(probe)
    CaptureKey.FOCUS -> focusPresentation(probe.overrides.focusDistance, editor.focus, probe.controlReceipt).let { "${it.value} ${it.unit}".trim() }
    CaptureKey.WB -> probe.overrides.whiteBalance?.let { mapOf(1 to "AUTO", 2 to "INCAND", 3 to "FLUOR", 4 to "W.FLUOR", 5 to "DAYLT", 6 to "CLOUDY", 7 to "TWILT", 8 to "SHADE")[it] ?: it.toString() } ?: "AUTO"
    CaptureKey.LOOK -> "STD"
}

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

@Composable private fun Diagnostics(probe: CameraProbe, enabled: Boolean, export: () -> Unit) =
    CaptureDiagnosticsPanel(probe, enabled, export)

internal fun chromeInput(probe: CameraProbe, ready: Boolean = false, interactable: Boolean = true) = CaptureChromeInput(
    mode = probe.mode, bound = probe.bound, cameraId = probe.selectedId, photoFormat = CameraProbe.formatName(probe.format),
    boundVideoChoice = probe.activeVideoChoice, manualExposure = probe.manualExposure.takeIf { probe.controlReady },
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
