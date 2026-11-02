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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.math.BigDecimal
import java.util.Locale

private val LocalCaptureAngle = staticCompositionLocalOf { 0f }
private val LocalCaptureHorizontal = staticCompositionLocalOf { false }
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
private fun nsText(ns: Long?): String = ns?.let {
    BigDecimal.valueOf(it, 6).stripTrailingZeros().toPlainString() + "ms"
} ?: "—"
private fun milliseconds(ns: Long): String = BigDecimal.valueOf(ns, 6).stripTrailingZeros().toPlainString()

@Composable
fun CaptureScreen(probe: CameraProbe, permission: Boolean, orientation: CaptureOrientation, requestPermission: () -> Unit,
    record: () -> Unit, export: () -> Unit, reportPending: Boolean, exit: () -> Unit) {
    var editor by remember { mutableStateOf(CaptureEditor(epoch = probe.currentEpoch)) }
    var panel by remember { mutableStateOf(CapturePanel.NONE) }
    var confirmReset by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var backAt by remember { mutableLongStateOf(0L) }
    val unlocked = !probe.busy && !probe.recordingBusy
    val editable = unlocked && probe.bound && probe.controlReady
    val animatedAngle by animateFloatAsState(orientation.uiAngle, tween(250), label = "capture-elements")
    val receipt = probe.controlReceipt
    LaunchedEffect(probe.currentEpoch) { editor = editor.session(probe.currentEpoch) }
    LaunchedEffect(receipt) { editor = editor.complete(receipt) }
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
        val settingsHeight = CaptureLayout.sheetHeight(maxHeight.value).dp
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                // Keep the same PreviewView mounted while panels/rulers change: no UI-induced camera rebind.
                if (permission) AndroidView(factory = { context ->
                    PreviewView(context).also {
                        it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        it.scaleType = PreviewView.ScaleType.FIT_CENTER
                        it.installFocusTap { x, y -> if (panel == CapturePanel.NONE && editor.submitting == null) probe.focusAt(x, y) }
                        probe.initialize(it)
                    }
                }, modifier = Modifier.fillMaxSize())
                if (probe.recordingBusy) Box(Modifier.fillMaxSize().border(1.dp, Rec))
                val feedback = probe.focusFeedback
                if (feedback != null && panel == CapturePanel.NONE && !probe.recordingBusy) {
                    val focusColor = when (feedback.phase) { FocusPhase.PENDING -> Amber; FocusPhase.FAILED -> Failed; FocusPhase.COMPLETED -> if (feedback.afSucceeded == true) Accepted else Muted }
                    Column(Modifier.offset(x = (maxWidth * feedback.point.first - 24.dp).coerceIn(0.dp, (maxWidth - 48.dp).coerceAtLeast(0.dp)),
                        y = (maxHeight * feedback.point.second - 24.dp).coerceIn(0.dp, (maxHeight - 48.dp).coerceAtLeast(0.dp))).size(48.dp).sensorRotation(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    Row(Modifier.fillMaxWidth().height(44.dp).background(Ink.copy(alpha = .5f)).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        val chip = if (probe.recordingBusy) "${if (orientation.horizontal) "●" else "REC "}${probe.recordingDurationMs / 60000}:${(probe.recordingDurationMs / 1000 % 60).toString().padStart(2, '0')}"
                            else if (orientation.horizontal) "CAM${probe.selectedId ?: "—"}" else "${probe.mode} · ${if (!probe.controlReady) "—" else if (probe.manualExposure) "MAN" else "AUTO"}"
                        Mono(chip, if (probe.recordingBusy) Rec else Foreground, Modifier.weight(1f))
                        Mono("OS ${probe.thermalSeverity.takeIf { it >= 0 } ?: "—"}", if (probe.thermalSeverity >= 2) Amber else Muted)
                        if (probe.mode == CaptureMode.CINE) Mono(if (probe.audioWanted) "${if (orientation.horizontal) "M" else "MIC "}${probe.recordingAudioAmplitude?.let { if (orientation.horizontal) "ON" else String.format(Locale.ROOT, "%.2f", it) } ?: "—"}" else "MIC OFF", Muted)
                        TextButton(onClick = { if (unlocked) { editor = editor.close(); panel = if (panel == CapturePanel.SETTINGS) CapturePanel.NONE else CapturePanel.SETTINGS } },
                            enabled = unlocked, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(8.dp)) { Gear() }
                    }
                    if (!probe.bound && permission) Text("接続待ち・プレビュー停止中", Modifier.align(Alignment.Center).sensorRotation().background(Ink.copy(alpha = .7f)).padding(12.dp), color = Foreground, fontSize = 13.sp)
                    if (probe.bound && !probe.controlReady && !probe.busy) Text("設定状態を確認・復旧してください", Modifier.align(Alignment.Center).sensorRotation().background(Ink.copy(alpha = .8f)).padding(12.dp), color = Failed, fontSize = 13.sp)
                    val banner = when {
                        ProbeControls.shouldStopRecording(probe.thermalSeverity) -> "熱: OS ${ProbeControls.thermalName(probe.thermalSeverity)}・録画開始不可"
                        probe.audioPermissionDenied -> "マイク許可なし: 録画は未開始。権限を許可するか設定で音声OFFにしてください"
                        probe.lastFailure != null -> probe.lastFailure
                        probe.recordingFailure != null -> probe.recordingFailure
                        notice != null -> notice
                        !unlocked -> null
                        else -> null
                    }
                    banner?.let { Text(it, Modifier.align(if (orientation.horizontal) Alignment.Center else Alignment.TopCenter).padding(top = if (orientation.horizontal) 0.dp else 48.dp).widthIn(max = if (orientation.horizontal) 220.dp else 380.dp).sensorRotation().background(Ink.copy(alpha = .8f)).padding(8.dp), color = if (probe.lastFailure != null) Failed else Amber, fontSize = 12.sp) }
                    if (orientation.horizontal && editor.selected != null && panel == CapturePanel.NONE && banner == null) {
                        Text("ISO ${probe.sample?.actual?.iso ?: "—"} · ${nsText(probe.sample?.actual?.exposureNs)} · ${probe.controlReceipt.phase}",
                            Modifier.align(Alignment.Center).widthIn(max = 180.dp).sensorRotation().background(Ink.copy(alpha = .7f)).padding(8.dp), color = Muted, fontSize = 11.sp)
                    }
                    if (panel == CapturePanel.NONE) Column(Modifier.align(Alignment.BottomCenter)) {
                        if (editor.selected != null) Ruler(probe, editor, editable,
                            change = { editor = it }, apply = { submit {
                                when (editor.selected?.group) {
                                    CaptureKey.ISO -> editor.exposure?.let { probe.editIso(it.iso); probe.editMs(it.milliseconds); probe.setExposure(it.iso, it.milliseconds) }
                                    CaptureKey.FOCUS -> editor.focus?.let { probe.editFocus(it); probe.applyManualFocus() }
                                    else -> Unit
                                }
                            } }, auto = { editor = editor.cancel(); probe.setExposureAuto() })
                        Row(Modifier.fillMaxWidth().height(72.dp).background(if (editor.selected == null) Ink.copy(alpha = .5f) else Color(0xF00D0F10))) {
                            CaptureKey.entries.forEach { key ->
                                val selected = editor.selected == key
                                val value = keyValue(key, editor, probe)
                                Column(Modifier.weight(1f).fillMaxHeight().background(if (selected) Color(0xFF1E2225) else Color.Transparent)
                                    .clickable(enabled = unlocked) { if (key == CaptureKey.LOOK) notice = "LOOKは未実装です・色処理なし" else editor = editor.select(key) }
                                    .padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Column(Modifier.sensorRotation(), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(key.name, color = if (key == CaptureKey.LOOK) Muted else Foreground, fontSize = 11.sp)
                                    Mono(value, if (key.group == CaptureKey.ISO && editor.exposure != null || key == CaptureKey.FOCUS && editor.focus != null) Amber else Foreground, rotate = false)
                                    Text(if (key == CaptureKey.LOOK) "未実装" else if (selected && editor.dirty) "編集中" else if (probe.controlReceipt.phase == ControlPhase.UNKNOWN) "不明" else if (probe.controlReceipt.phase == ControlPhase.PENDING) "送信中" else if (key == CaptureKey.ISO || key == CaptureKey.SHUTTER) if (probe.manualExposure) "MAN" else "AUTO" else "", fontSize = 11.sp, color = Muted)
                                    }
                                    Spacer(Modifier.height(3.dp).fillMaxWidth().background(if (selected) Foreground else Color.Transparent))
                                }
                            }
                        }
                    }
                }
            }
            if (panel == CapturePanel.SETTINGS) Column(Modifier.fillMaxWidth().height(settingsHeight).background(Sheet)) {
                PanelHeader("撮影設定 · ${probe.mode}") { panel = CapturePanel.NONE; confirmReset = false }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Settings(probe, unlocked, diagnostics = { panel = CapturePanel.DIAGNOSTICS }, reset = { confirmReset = true })
                }
            }
            if (panel != CapturePanel.DIAGNOSTICS) CaptureBar(probe, editable,
                shutter = {
                    if (probe.recordingStage in setOf(RecordingStage.STARTING, RecordingStage.RECORDING)) probe.stopRecording()
                    else if (probe.mode == CaptureMode.STILL) { if (editor.dirty) notice = "受付値で撮影・未適用の編集は保持"; probe.capture() }
                    else record()
                }, mode = { if (unlocked) { editor = editor.close(); probe.selectMode(if (probe.mode == CaptureMode.STILL) CaptureMode.CINE else CaptureMode.STILL) } },
                placeholder = { notice = "最新写真ビューアは未実装です。端末のギャラリーで確認してください" })
        }
        if (panel == CapturePanel.DIAGNOSTICS) Column(Modifier.fillMaxSize().background(Ink)) {
            PanelHeader("診断 · CameraX 1.6.2") { panel = CapturePanel.SETTINGS }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Diagnostics(probe, unlocked && !reportPending, export)
            }
        }
    }
    }
}

@Composable private fun Ruler(probe: CameraProbe, editor: CaptureEditor, enabled: Boolean,
    change: (CaptureEditor) -> Unit, apply: () -> Unit, auto: () -> Unit) {
    val key = editor.selected ?: return
    val actual = probe.sample?.actual
    val acceptedExposure = probe.appliedExposure
    val base = editor.exposure ?: acceptedExposure?.let { ExposureDraft(it.iso.toString(), milliseconds(it.exposureNs)) }
        ?: actual?.let { values -> if (values.iso != null && values.exposureNs != null) ExposureDraft(values.iso.toString(), milliseconds(values.exposureNs)) else null }
    Column(Modifier.fillMaxWidth().heightIn(min = 104.dp).background(Color(0xF00D0F10)).padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { change(editor.close()) }, enabled = editor.submitting == null, modifier = Modifier.width(44.dp), contentPadding = PaddingValues(0.dp)) { Text("×", Modifier.sensorRotation(), fontSize = 22.sp) }
            Mono(if (LocalCaptureHorizontal.current) key.name else "${key.name} · ${if (editor.submitting != null) "送信中" else if (editor.dirty) "編集中" else if (probe.controlReceipt.phase == ControlPhase.ACCEPTED) "受付済" else "受付 / 実測"}", if (editor.dirty) Amber else Foreground, Modifier.weight(1f))
            if (key.group == CaptureKey.ISO) TextButton(onClick = auto, enabled = probe.bound && !probe.busy && !probe.recordingBusy) { Text("AUTO", Modifier.sensorRotation(), fontSize = 11.sp) }
            if (key.group == CaptureKey.ISO || key == CaptureKey.FOCUS) {
                TextButton(onClick = { change(editor.cancel()) }, enabled = editor.submitting == null) { Text("取消", Modifier.sensorRotation(), fontSize = 11.sp) }
                TextButton(onClick = apply, enabled = enabled && editor.dirty) { Text("適用", Modifier.sensorRotation(), fontSize = 11.sp) }
            }
        }
        when (key.group) {
            CaptureKey.ISO -> {
                val range = if (key == CaptureKey.ISO) probe.selectedReport?.isoRange?.let { it.first.toFloat()..it.last.toFloat() }
                    else probe.selectedReport?.exposureRange?.let { it.first / 1_000_000f..it.last / 1_000_000f }
                val maxTime = probe.activeVideoChoice?.fpsUpper?.let { 1000f / it }
                val limitedRange = if (key == CaptureKey.SHUTTER && range != null && maxTime != null) range.start..minOf(range.endInclusive, maxTime) else range
                if (base == null || range == null || probe.selectedReport?.manualBlockReason != null) Text(probe.selectedReport?.manualBlockReason ?: "AUTOの実測待ち・値は未推定", color = Muted, fontSize = 12.sp)
                else {
                    val value = if (key == CaptureKey.ISO) base.iso.toFloatOrNull() else base.milliseconds.toFloatOrNull()
                    val bounds = limitedRange!!
                    if (bounds.start > 0 && bounds.start < bounds.endInclusive) Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                        val low = kotlin.math.log10(bounds.start)
                        val high = kotlin.math.log10(bounds.endInclusive)
                        Text("◀", color = Muted, fontSize = 11.sp)
                        Slider(value = kotlin.math.log10((value ?: bounds.start).coerceIn(bounds)), valueRange = low..high, enabled = enabled,
                            onValueChange = { next ->
                                val number = Math.pow(10.0, next.toDouble()).toFloat().coerceIn(bounds)
                                change(editor.copy(exposure = if (key == CaptureKey.ISO) base.copy(iso = number.toInt().toString())
                                    else base.copy(milliseconds = String.format(Locale.ROOT, "%.6f", number))))
                            }, modifier = Modifier.weight(1f))
                        Text("▶", color = Muted, fontSize = 11.sp)
                        Mono(if (key == CaptureKey.ISO) base.iso else "${base.milliseconds}ms", Amber, Modifier.width(80.dp))
                    }
                }
            }
            CaptureKey.FOCUS -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { change(editor.cancel()); probe.restoreAutofocus() }, enabled = enabled) { Text("AF", Modifier.sensorRotation()) }
                    val maximum = probe.selectedReport?.overrides?.manualFocusMaximum
                    val focus = editor.focus ?: probe.overrides.focusDistance?.toString() ?: actual?.focus?.toString()
                    val number = focus?.toFloatOrNull()
                    if (maximum != null && number != null) {
                        Slider(number.coerceIn(0f, maximum), onValueChange = { change(editor.copy(focus = String.format(Locale.ROOT, "%.4f", it))) },
                            valueRange = 0f..maximum, enabled = enabled, modifier = Modifier.weight(1f))
                        Mono("$focus LU", Amber, Modifier.width(72.dp))
                    } else Text("MF非対応 / 実測待ち", color = Muted, fontSize = 12.sp)
                }
            }
            CaptureKey.EV -> Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { probe.compensateEv(-1) }, enabled = enabled && !probe.manualExposure && probe.exposureCompensationSupported) { Text("−") }
                Mono(if (probe.manualExposure) "MAN中は無効" else "EV ${evText(probe)}", Foreground)
                TextButton(onClick = { probe.compensateEv(1) }, enabled = enabled && !probe.manualExposure && probe.exposureCompensationSupported) { Text("＋") }
            }
            CaptureKey.WB -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { probe.selectWhiteBalance(null) }, enabled = enabled) { Text("AUTO/default", Modifier.sensorRotation()) }
                probe.selectedReport?.overrides?.whiteBalanceModes?.sorted()?.forEach { mode -> TextButton(onClick = { probe.selectWhiteBalance(mode) }, enabled = enabled) { Text(SensorModeNames.whiteBalance(mode), Modifier.sensorRotation()) } }
            }
            else -> Unit
        }
        val observed = probe.sample
        val match = observed?.corresponds(probe.controlReceipt) == true
        if (!LocalCaptureHorizontal.current) Mono("実測 ISO ${actual?.iso ?: "—"} · ${nsText(actual?.exposureNs)} · ${if (match) "要求対応・一致は別確認" else "更新/対応確認待ち"}", Muted, Modifier.padding(vertical = 5.dp))
        if (probe.controlReceipt.phase in setOf(ControlPhase.REJECTED, ControlPhase.UNKNOWN)) Text(probe.controlReceipt.detail, color = Failed, fontSize = 11.sp)
    }
}

private fun evText(probe: CameraProbe): String = probe.acceptedEv?.let { index -> probe.exposureCompensationStep?.let { String.format(Locale.ROOT, "%+.1f", index * it) } } ?: "—"
private fun keyValue(key: CaptureKey, editor: CaptureEditor, probe: CameraProbe): String = if (probe.controlReceipt.phase == ControlPhase.UNKNOWN) "—" else when (key) {
    CaptureKey.ISO -> editor.exposure?.iso ?: probe.appliedExposure?.iso?.toString() ?: probe.sample?.actual?.iso?.toString() ?: "—"
    CaptureKey.SHUTTER -> editor.exposure?.milliseconds?.let { "${it}ms" } ?: nsText(probe.appliedExposure?.exposureNs ?: probe.sample?.actual?.exposureNs)
    CaptureKey.EV -> if (probe.manualExposure) "—" else evText(probe)
    CaptureKey.FOCUS -> editor.focus?.let { "$it LU" } ?: probe.overrides.focusDistance?.let { "$it LU" } ?: "AF"
    CaptureKey.WB -> probe.overrides.whiteBalance?.let(SensorModeNames::whiteBalance) ?: "AUTO"
    CaptureKey.LOOK -> "STD"
}

@Composable private fun CaptureBar(probe: CameraProbe, ready: Boolean, shutter: () -> Unit, mode: () -> Unit, placeholder: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(96.dp).background(Ink).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(52.dp).border(1.dp, Muted, RoundedCornerShape(6.dp)).clickable(enabled = !probe.busy && !probe.recordingBusy, onClick = placeholder), contentAlignment = Alignment.Center) { Text("写真\n未実装", modifier = Modifier.sensorRotation(), fontSize = 11.sp, textAlign = TextAlign.Center, color = Muted) }
        }
        val stoppable = probe.recordingStage in setOf(RecordingStage.STARTING, RecordingStage.RECORDING)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).clip(CircleShape).border(2.dp, if (probe.mode == CaptureMode.CINE) Rec else Foreground, CircleShape)
                .clickable(enabled = ready || stoppable, onClick = shutter).padding(6.dp)
                .semantics { contentDescription = if (stoppable) "録画停止" else if (probe.mode == CaptureMode.STILL) "静止画撮影" else "録画開始" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (stoppable) 24.dp else 48.dp).background(if (probe.mode == CaptureMode.CINE) Rec else Foreground.copy(alpha = if (ready) 1f else .3f), if (stoppable) RoundedCornerShape(4.dp) else CircleShape))
            }
            Mono(when {
                probe.recordingStage == RecordingStage.FINALIZING -> "最終保存中"
                probe.recordingStage == RecordingStage.STOPPING -> "停止処理中"
                probe.recordingBusy -> "REC · 停止"
                probe.photoStage == PhotoStage.SAVING -> "保存中"
                probe.photoStage == PhotoStage.CAPTURING -> "撮影中"
                probe.busy -> "設定送信中"
                probe.mode == CaptureMode.STILL && probe.photoStage == PhotoStage.SAVED -> "保存済"
                probe.mode == CaptureMode.CINE && probe.recordingSaveSucceeded == true -> "保存済"
                probe.mode == CaptureMode.CINE && probe.recordingSaveSucceeded == false -> "保存失敗"
                else -> probe.mode.name
            }, Muted)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextButton(onClick = mode, enabled = !probe.busy && !probe.recordingBusy, modifier = Modifier.width(100.dp).height(48.dp), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Muted)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Mono(if (probe.mode == CaptureMode.STILL) "●STILL" else "STILL", if (probe.mode == CaptureMode.STILL) Foreground else Muted, Modifier.weight(1f))
                    Mono(if (probe.mode == CaptureMode.CINE) "●CINE" else "CINE", if (probe.mode == CaptureMode.CINE) Foreground else Muted, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable private fun Settings(probe: CameraProbe, enabled: Boolean, diagnostics: () -> Unit, reset: () -> Unit) {
    Text("原画角を保持 · 比率クロップの保存は未実装", color = Muted, fontSize = 12.sp)
    if (probe.mode == CaptureMode.STILL) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf(ImageCapture.OUTPUT_FORMAT_JPEG, ImageCapture.OUTPUT_FORMAT_RAW, ImageCapture.OUTPUT_FORMAT_RAW_JPEG).forEach { format ->
                TextButton(onClick = { probe.selectFormat(format) }, enabled = enabled && format in probe.formats) { Text("${if (format == probe.format) "● " else ""}${CameraProbe.formatName(format)}") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(probe.highResolution, probe::chooseHighResolution, enabled = enabled); Text("API対応の高解像度を優先", fontSize = 13.sp) }
        Text(probe.stillResolution, fontSize = 11.sp, color = Muted)
    } else {
        Text(probe.videoSelection, fontSize = 11.sp, color = Muted)
        probe.videoChoices.forEach { option -> TextButton(onClick = { probe.selectVideo(option.key) }, enabled = enabled) { Text(option.label, fontSize = 12.sp) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(probe.audioWanted, probe::setAudio, enabled = enabled); Text("音声収録 · 開始時にマイク許可", fontSize = 13.sp) }
    }
    Text("カメラ", color = Muted, fontSize = 11.sp)
    Row(Modifier.horizontalScroll(rememberScrollState())) { probe.reports.forEach { report -> TextButton(onClick = { probe.selectCamera(report.id) }, enabled = enabled) { Text("${if (report.id == probe.selectedId) "●" else ""}ID ${report.id}") } } }
    Text("照明対策 · AUTO AEのみ有効 / LED除去保証なし", fontSize = 11.sp, color = Muted)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton(onClick = { probe.selectAntiBanding(null) }, enabled = enabled && probe.controlReady) { Text("default") }
        probe.selectedReport?.overrides?.antiBandingModes?.sorted()?.forEach { value -> TextButton(onClick = { probe.selectAntiBanding(value) }, enabled = enabled && probe.controlReady) { Text("${if (probe.overrides.antiBanding == value) "●" else ""}${SensorModeNames.antiBanding(value)}") } }
    }
    Row {
        TextButton(onClick = probe::toggleExposureLock, enabled = enabled && probe.controlReady && !probe.manualExposure && probe.selectedReport?.locks?.exposure == true) { Text("AE LOCK ${probe.autoLocks.exposure}") }
        TextButton(onClick = probe::toggleWhiteBalanceLock, enabled = enabled && probe.controlReady && probe.selectedReport?.locks?.whiteBalance == true && (probe.overrides.whiteBalance == null || probe.overrides.whiteBalance == 1)) { Text("WB LOCK ${probe.autoLocks.whiteBalance}") }
    }
    TextButton(onClick = reset, enabled = enabled && probe.bound) { Text("撮影制御をリセット…", color = Amber) }
    TextButton(onClick = diagnostics, enabled = enabled) { Text("診断・能力・検証レポート ›") }
}

@Composable private fun Diagnostics(probe: CameraProbe, enabled: Boolean, export: () -> Unit) {
    Row {
        TextButton(onClick = probe::bind, enabled = enabled && !probe.bound) { Text("Preview開始") }
        TextButton(onClick = probe::stop, enabled = enabled && probe.bound) { Text("Preview停止") }
        TextButton(onClick = export, enabled = enabled && !probe.exportingReport) { Text("レポート保存") }
    }
    Text("端末build/設定/ログを含みます。共有前に内容を確認。撮影画像や音声本体・自動アップロードなし。", fontSize = 12.sp, color = Muted)
    SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${BuildConfig.VERSION_NAME} / ${probe.mode} / ID ${probe.selectedId}\nepoch=${probe.currentEpoch} outputOrientation=${probe.outputOrientation} bound=${probe.bound} ready=${probe.controlReady}", fontSize = 12.sp)
        Text("操作: ${probe.controlReceipt.phase}\n要求: ${probe.controlReceipt.requested}\n最後の受付（履歴）: ${probe.controlReceipt.accepted}\n${probe.controlReceipt.detail}", fontSize = 12.sp)
        Text("実測（要求一致は別評価）: ${probe.sample}\n${probe.measured}", fontSize = 11.sp)
        Text("${probe.thermalStatus}\n${probe.focusStatus}\n${probe.evStatus}\n${probe.lockStatus}", fontSize = 12.sp)
        Text("${probe.status}\n${probe.recordingStage}: ${probe.recordingStats}", fontSize = 12.sp)
        Text("CameraX:\n${probe.cameraXReports}\nCamera2:\n${probe.reports.joinToString("\n\n") { it.text }}", fontSize = 11.sp)
        Text("直近ログ（保存先: アプリ専用領域）\n${probe.lines}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    } }
}

@Composable private fun PanelHeader(title: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = Foreground, fontSize = 14.sp)
        TextButton(onClick = close, modifier = Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text("×", fontSize = 22.sp) }
    }
}
@Composable private fun Mono(text: String, color: Color, modifier: Modifier = Modifier, rotate: Boolean = true) {
    Text(text, if (rotate) modifier.sensorRotation() else modifier, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
}
@Composable private fun Gear() {
    Icon(androidx.compose.ui.res.painterResource(android.R.drawable.ic_menu_preferences), contentDescription = "撮影設定", tint = Foreground, modifier = Modifier.size(24.dp).sensorRotation())
}
