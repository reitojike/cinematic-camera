package dev.cinematic.p0

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal enum class MessageSlot { CHIP, CAPTION, CENTER, PROBLEM, BANNER, INLINE, TOAST }

internal enum class MessageAction {
    RETRY, RECONNECT, STOP_AND_RECOVER, BACK_TO_LIST, RESUME_PREVIEW, CANCEL_SAVE,
    RECHECK_SAVE, ALLOW_NOTIFICATIONS, GRANT_ACCESS, CHOOSE_AGAIN, OPEN_DIAGNOSTICS,
    OPEN_FILES, OPEN_SETTINGS, CONFIRM, CLOSE, CANCEL_IMPORT, RELEASE_GRANT, OPEN_INFO,
}

/** Labels and enabled state are supplied by the pure screen assembler, never inferred by drawing. */
internal data class MessageControl(
    val action: MessageAction,
    val label: String,
    val enabled: Boolean = true,
    val description: String? = null,
    val stateDescription: String? = null,
    val emphasis: Boolean = false,
) {
    init { if (BuildConfig.DEBUG) require(label.isNotBlank()) }
}

/** Null fraction is ongoing activity. A fraction describes development work, not save completion. */
internal data class MessageProgress(val fraction: Float? = null)

internal data class UiMessage(
    val slot: MessageSlot,
    val tone: CaptureChromeTone,
    val body: String,
    /** Stable identity of the fact, shared by differently worded presentations of that fact. */
    val fact: String,
    val title: String? = null,
    val note: String? = null,
    val primary: MessageControl? = null,
    val secondary: MessageControl? = null,
    /** Processing or paused processing is distinct from an ordinary WARNING. */
    val inProgress: Boolean = false,
    val progress: MessageProgress? = null,
) {
    init {
        if (BuildConfig.DEBUG) require(isValid()) { "Invalid UI message contract" }
    }

    internal fun isValid(): Boolean =
            (body.isNotBlank() && fact.isNotBlank()) &&
            (progress == null || (inProgress && (progress.fraction == null ||
                progress.fraction.isFinite() && progress.fraction >= 0f && progress.fraction < 1f))) &&
            (primary == null || primary.label.isNotBlank()) &&
            (secondary == null || secondary.label.isNotBlank()) &&
            (tone != CaptureChromeTone.RECORDING) &&
            (slot == MessageSlot.CHIP || tone in setOf(
            CaptureChromeTone.NORMAL, CaptureChromeTone.WARNING,
            CaptureChromeTone.FAILURE, CaptureChromeTone.ACCEPTED,
        )) &&
            (slot != MessageSlot.PROBLEM || (tone == CaptureChromeTone.FAILURE && !title.isNullOrBlank())) &&
            (title == null || slot == MessageSlot.PROBLEM) &&
            (note == null || slot in setOf(MessageSlot.PROBLEM, MessageSlot.BANNER)) &&
            (secondary == null || primary != null) &&
            (primary == null || slot in setOf(MessageSlot.CENTER, MessageSlot.PROBLEM, MessageSlot.BANNER, MessageSlot.INLINE)) &&
            (secondary == null || slot != MessageSlot.INLINE) &&
            (!inProgress || (slot == MessageSlot.BANNER && tone == CaptureChromeTone.WARNING)) &&
            (slot != MessageSlot.BANNER || tone in setOf(CaptureChromeTone.WARNING, CaptureChromeTone.FAILURE)) &&
            (slot != MessageSlot.TOAST || tone in setOf(CaptureChromeTone.NORMAL, CaptureChromeTone.ACCEPTED))
}

internal data class ScreenMessages(
    val chips: List<UiMessage> = emptyList(),
    val caption: UiMessage? = null,
    val center: UiMessage? = null,
    val banner: UiMessage? = null,
    val toast: UiMessage? = null,
    val inline: List<UiMessage> = emptyList(),
)

/** Stable within a priority: the screen supplies its own deterministic order of candidates. */
internal fun screenMessages(
    messages: List<UiMessage>,
    strict: Boolean = BuildConfig.DEBUG,
    report: (String) -> Unit = ::reportMessageContract,
    failureFallback: UiMessage? = null,
): ScreenMessages {
    var needsFailureFallback = false
    val valid = messages.filter {
        val accepted = messageContract(it.isValid(), "invalid_message", strict, report)
        if (!accepted && it.isFailurePresentation()) needsFailureFallback = true
        accepted
    }
    // Keep a valid problem before the generic one. Never copy the broken text or controls.
    val candidates = valid + if (needsFailureFallback)
        listOfNotNull(checkedFailureFallback(failureFallback, strict, report)) else emptyList()
    val problem = candidates.firstOrNull { it.slot == MessageSlot.PROBLEM }
    val banner = if (problem == null) candidates.filter { it.slot == MessageSlot.BANNER }
        .minByOrNull { when {
            it.tone == CaptureChromeTone.FAILURE -> 0
            it.inProgress -> 1
            else -> 2
        } } else null
    val toast = if (problem == null && banner == null) candidates.firstOrNull { it.slot == MessageSlot.TOAST } else null
    val center = problem ?: candidates.firstOrNull {
        it.slot == MessageSlot.CENTER && it.fact != banner?.fact && it.fact != toast?.fact
    }
    val usedFacts = listOfNotNull(center, banner, toast).map { it.fact }.toMutableSet()
    val caption = candidates.firstOrNull { it.slot == MessageSlot.CAPTION }?.takeIf {
        messageContract(it.fact !in usedFacts, "duplicate_caption", strict, report)
    }
    caption?.let { usedFacts.add(it.fact) }
    val inline = candidates.filter { it.slot == MessageSlot.INLINE && usedFacts.add(it.fact) }
    // A short chip may summarize a fact explained in a banner/problem; other duplicates are excluded.
    val chips = candidates.filter { it.slot == MessageSlot.CHIP }.distinctBy { it.fact }.filter {
        messageContract(it.fact !in usedFacts || it.fact == banner?.fact || it.fact == problem?.fact,
            "duplicate_chip", strict, report)
    }
    return ScreenMessages(chips, caption, center, banner, toast, inline)
}

internal fun UiMessage.isFailurePresentation(): Boolean = slot == MessageSlot.PROBLEM ||
    (slot == MessageSlot.BANNER && tone == CaptureChromeTone.FAILURE)

/** Classify the supplied fact, not the renderer a buggy caller selected. */
internal fun failureFallbackFor(message: UiMessage, fallback: UiMessage?,
    strict: Boolean = BuildConfig.DEBUG, report: (String) -> Unit = ::reportMessageContract): UiMessage? =
    if (message.isFailurePresentation()) checkedFailureFallback(fallback, strict, report) else null

/** The screen owns allowed actions and their enabled conditions; shared drawing never invents them. */
internal fun checkedFailureFallback(fallback: UiMessage?, strict: Boolean = BuildConfig.DEBUG,
    report: (String) -> Unit = ::reportMessageContract): UiMessage? {
    if (fallback == null) return null
    return fallback.takeIf {
        messageContract(it.isValid() && it.slot == MessageSlot.PROBLEM,
            "invalid_failure_fallback", strict, report)
    }
}

/** Never record message text, facts, URIs, or user data when a display contract is broken. */
private fun reportMessageContract(reason: String) {
    android.util.Log.w("CinematicUi", "Suppressed UI message: $reason")
}

internal fun messageContract(valid: Boolean, reason: String, strict: Boolean,
    report: (String) -> Unit): Boolean {
    if (!valid) {
        if (strict) throw IllegalArgumentException(reason)
        report(reason)
    }
    return valid
}

internal data class MessageDialog(
    val title: String,
    val body: String,
    val close: MessageControl,
    val confirm: MessageControl? = null,
    val confirmationLast: Boolean = false,
) {
    fun actionsInDisplayOrder(): List<MessageControl> =
        if (confirmationLast) listOfNotNull(close, confirm) else listOfNotNull(confirm, close)
    init { if (BuildConfig.DEBUG) require(title.isNotBlank() && body.isNotBlank()) }
}

@Composable
internal fun CaptureTextAction(
    control: MessageControl,
    onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier,
    tone: CaptureChromeTone? = null,
) {
    if (!messageContract(control.label.isNotBlank(), "invalid_action_renderer", BuildConfig.DEBUG,
        ::reportMessageContract)) return
    TextButton(
        onClick = { onAction(control.action) }, enabled = control.enabled,
        modifier = modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics {
            control.description?.let { contentDescription = it }
            control.stateDescription?.let { stateDescription = it }
        },
        shape = RectangleShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (control.emphasis) CaptureDesign.Amber else androidx.compose.ui.graphics.Color.Transparent,
            contentColor = if (control.emphasis) CaptureDesign.Ink else tone?.let(::captureChromeColor) ?: CaptureDesign.Text,
            disabledContentColor = CaptureDesign.Disabled,
        ),
    ) { Text(control.label, style = CaptureDesign.Explanation) }
}

@Composable
private fun MessageActions(message: UiMessage, onAction: (MessageAction) -> Unit) {
    if (message.primary != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CaptureTextAction(message.primary, onAction)
            message.secondary?.let { CaptureTextAction(it, onAction) }
        }
    }
}

@Composable
internal fun CaptureBanner(message: UiMessage, onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier, inSheet: Boolean = false, failureFallback: UiMessage? = null) {
    if (!messageContract(message.isValid() && message.slot == MessageSlot.BANNER,
        "invalid_banner_renderer", BuildConfig.DEBUG, ::reportMessageContract)) {
        failureFallbackFor(message, failureFallback)?.let { CaptureProblem(it, onAction, modifier) }
        return
    }
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }
        .background(if (inSheet) CaptureDesign.Panel else CaptureDesign.Scrim, RectangleShape)
        .height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(captureChromeColor(message.tone)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(message.body, style = CaptureDesign.Body, color = CaptureDesign.Text)
            message.note?.let { Text(it, style = CaptureDesign.Body, color = CaptureDesign.Muted) }
            message.progress?.let { progress ->
                // Phase text is announced by the banner; do not announce an overall percentage.
                val bar = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                    .clearAndSetSemantics { }
                if (progress.fraction == null) LinearProgressIndicator(
                    modifier = bar, color = CaptureDesign.Amber, trackColor = CaptureDesign.Line,
                    strokeCap = StrokeCap.Butt,
                ) else LinearProgressIndicator(
                    progress = { progress.fraction }, modifier = bar,
                    color = CaptureDesign.Amber, trackColor = CaptureDesign.Line,
                    strokeCap = StrokeCap.Butt, drawStopIndicator = {},
                )
            }
            MessageActions(message, onAction)
        }
    }
}

@Composable
internal fun CaptureProblem(message: UiMessage, onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier, failureFallback: UiMessage? = null) {
    if (!messageContract(message.isValid() && message.slot == MessageSlot.PROBLEM,
        "invalid_problem_renderer", BuildConfig.DEBUG, ::reportMessageContract)) {
        failureFallbackFor(message, failureFallback)?.let { CaptureProblem(it, onAction, modifier) }
        return
    }
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }
        .background(CaptureDesign.Scrim, RectangleShape)
        .drawWithContent {
            drawContent()
            // Draw after the border so the full 3dp tone stripe remains visible.
            drawRect(captureChromeColor(message.tone), size = Size(size.width, 3.dp.toPx()))
        }
        .border(1.dp, CaptureDesign.Line, RectangleShape)
        .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(requireNotNull(message.title), Modifier.semantics { heading() },
            style = CaptureDesign.Label, color = captureChromeColor(message.tone))
        Text(message.body, style = CaptureDesign.Body, color = CaptureDesign.Text)
        message.note?.let { Text(it, style = CaptureDesign.Body, color = CaptureDesign.Muted) }
        MessageActions(message, onAction)
    }
}

@Composable
internal fun CaptureCenter(message: UiMessage, onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier) {
    if (!messageContract(message.isValid() && message.slot == MessageSlot.CENTER,
        "invalid_center_renderer", BuildConfig.DEBUG, ::reportMessageContract)) return
    Column(modifier.background(CaptureDesign.Scrim, RectangleShape).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message.body, style = CaptureDesign.Body, color = if (message.tone == CaptureChromeTone.NORMAL) CaptureDesign.Muted else captureChromeColor(message.tone))
        MessageActions(message, onAction)
    }
}

@Composable
internal fun CaptureInlineNote(message: UiMessage, onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier) {
    if (!messageContract(message.isValid() && message.slot == MessageSlot.INLINE,
        "invalid_inline_renderer", BuildConfig.DEBUG, ::reportMessageContract)) return
    Column(modifier.background(CaptureDesign.messageTint(message.tone), RectangleShape).padding(8.dp)) {
        Text(message.body, style = CaptureDesign.Body, color = captureChromeColor(message.tone))
        MessageActions(message, onAction)
    }
}

/** Placement at the screen bottom and the four-second lifetime remain the caller's responsibility. */
@Composable
internal fun CaptureToast(message: UiMessage, modifier: Modifier = Modifier) {
    if (!messageContract(message.isValid() && message.slot == MessageSlot.TOAST,
        "invalid_toast_renderer", BuildConfig.DEBUG, ::reportMessageContract)) return
    Text(message.body, modifier.semantics { liveRegion = LiveRegionMode.Polite }
        .background(CaptureDesign.Panel, RectangleShape).border(1.dp, CaptureDesign.Line, RectangleShape).padding(12.dp),
        style = CaptureDesign.Body, color = captureChromeColor(message.tone))
}

/** Static INFO uses only close; confirmation uses confirm plus close. No lifecycle or action mapping here. */
@Composable
internal fun CaptureDialog(message: MessageDialog, onDismissRequest: () -> Unit,
    onAction: (MessageAction) -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}) {
    if (!messageContract(message.title.isNotBlank() && message.body.isNotBlank() &&
        message.close.label.isNotBlank() && (message.confirm == null || message.confirm.label.isNotBlank()),
        "invalid_dialog_renderer", BuildConfig.DEBUG, ::reportMessageContract)) return
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(modifier, shape = RectangleShape, color = CaptureDesign.Panel,
            border = BorderStroke(1.dp, CaptureDesign.Border)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message.title, Modifier.semantics { heading() }, style = CaptureDesign.Title, color = CaptureDesign.Text)
                Text(message.body, style = CaptureDesign.Body, color = CaptureDesign.Text)
                content()
                if (message.confirmationLast) {
                    CaptureTextAction(message.close, onAction)
                    message.confirm?.let { confirm ->
                        Box(Modifier.padding(top = 12.dp)) { CaptureTextAction(confirm, onAction) }
                    }
                } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    message.actionsInDisplayOrder().forEach { CaptureTextAction(it, onAction) }
                }
            }
        }
    }
}

@Composable
internal fun DevelopCaption(message: UiMessage, modifier: Modifier = Modifier) {
    if (!messageContract(message.isValid() && message.slot == MessageSlot.CAPTION,
        "invalid_caption_renderer", BuildConfig.DEBUG, ::reportMessageContract)) return
    Text(message.body, modifier.background(CaptureDesign.Scrim, RectangleShape).padding(8.dp),
        style = CaptureDesign.Body, color = captureChromeColor(message.tone),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
