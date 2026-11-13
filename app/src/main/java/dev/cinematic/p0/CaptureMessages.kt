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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    OPEN_FILES, OPEN_SETTINGS, CONFIRM, CLOSE,
}

/** Labels and enabled state are supplied by the pure screen assembler, never inferred by drawing. */
internal data class MessageControl(
    val action: MessageAction,
    val label: String,
    val enabled: Boolean = true,
    val description: String? = null,
    val stateDescription: String? = null,
) {
    init { require(label.isNotBlank()) }
}

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
) {
    init {
        require(body.isNotBlank() && fact.isNotBlank())
        require(tone != CaptureChromeTone.RECORDING)
        require(slot == MessageSlot.CHIP || tone in setOf(
            CaptureChromeTone.NORMAL, CaptureChromeTone.WARNING,
            CaptureChromeTone.FAILURE, CaptureChromeTone.ACCEPTED,
        ))
        require(slot != MessageSlot.PROBLEM || (tone == CaptureChromeTone.FAILURE && !title.isNullOrBlank()))
        require(title == null || slot == MessageSlot.PROBLEM)
        require(note == null || slot == MessageSlot.PROBLEM)
        require(secondary == null || primary != null)
        require(primary == null || slot in setOf(MessageSlot.CENTER, MessageSlot.PROBLEM, MessageSlot.BANNER, MessageSlot.INLINE))
        require(secondary == null || slot != MessageSlot.INLINE)
        require(!inProgress || (slot == MessageSlot.BANNER && tone == CaptureChromeTone.WARNING))
        require(slot != MessageSlot.BANNER || tone in setOf(CaptureChromeTone.WARNING, CaptureChromeTone.FAILURE))
        require(slot != MessageSlot.TOAST || tone in setOf(CaptureChromeTone.NORMAL, CaptureChromeTone.ACCEPTED))
    }
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
internal fun screenMessages(candidates: List<UiMessage>): ScreenMessages {
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
    val caption = candidates.firstOrNull { it.slot == MessageSlot.CAPTION }
    require(caption == null || caption.fact !in usedFacts) { "Caption describes the image, not a repeated status" }
    caption?.let { usedFacts.add(it.fact) }
    val inline = candidates.filter { it.slot == MessageSlot.INLINE && usedFacts.add(it.fact) }
    // A short chip may summarize a fact explained in a banner/problem; other duplicates are excluded.
    val chips = candidates.filter { it.slot == MessageSlot.CHIP }.distinctBy { it.fact }
    require(chips.all { it.fact !in usedFacts || it.fact == banner?.fact || it.fact == problem?.fact }) {
        "A chip may repeat only a banner or problem fact"
    }
    return ScreenMessages(chips, caption, center, banner, toast, inline)
}

internal data class MessageDialog(
    val title: String,
    val body: String,
    val close: MessageControl,
    val confirm: MessageControl? = null,
) {
    init { require(title.isNotBlank() && body.isNotBlank()) }
}

@Composable
internal fun CaptureTextAction(
    control: MessageControl,
    onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier,
    tone: CaptureChromeTone? = null,
) {
    TextButton(
        onClick = { onAction(control.action) }, enabled = control.enabled,
        modifier = modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).semantics {
            control.description?.let { contentDescription = it }
            control.stateDescription?.let { stateDescription = it }
        },
        shape = RectangleShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = tone?.let(::captureChromeColor) ?: CaptureDesign.Text,
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
    modifier: Modifier = Modifier, inSheet: Boolean = false) {
    require(message.slot == MessageSlot.BANNER)
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }
        .background(if (inSheet) CaptureDesign.Panel else CaptureDesign.Scrim, RectangleShape)
        .height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(captureChromeColor(message.tone)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(message.body, style = CaptureDesign.Body, color = CaptureDesign.Text)
            MessageActions(message, onAction)
        }
    }
}

@Composable
internal fun CaptureProblem(message: UiMessage, onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier) {
    require(message.slot == MessageSlot.PROBLEM)
    Column(modifier.semantics { liveRegion = LiveRegionMode.Polite }
        .background(CaptureDesign.Scrim, RectangleShape).padding(16.dp),
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
    require(message.slot == MessageSlot.CENTER)
    Column(modifier.background(CaptureDesign.Scrim, RectangleShape).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message.body, style = CaptureDesign.Body, color = CaptureDesign.Muted)
        MessageActions(message, onAction)
    }
}

@Composable
internal fun CaptureInlineNote(message: UiMessage, onAction: (MessageAction) -> Unit,
    modifier: Modifier = Modifier) {
    require(message.slot == MessageSlot.INLINE)
    Column(modifier.background(CaptureDesign.messageTint(message.tone), RectangleShape).padding(8.dp)) {
        Text(message.body, style = CaptureDesign.Body, color = captureChromeColor(message.tone))
        MessageActions(message, onAction)
    }
}

/** Placement at the screen bottom and the four-second lifetime remain the caller's responsibility. */
@Composable
internal fun CaptureToast(message: UiMessage, modifier: Modifier = Modifier) {
    require(message.slot == MessageSlot.TOAST)
    Text(message.body, modifier.semantics { liveRegion = LiveRegionMode.Polite }
        .background(CaptureDesign.Panel, RectangleShape).border(1.dp, CaptureDesign.Line, RectangleShape).padding(12.dp),
        style = CaptureDesign.Body, color = captureChromeColor(message.tone))
}

/** Static INFO uses only close; confirmation uses confirm plus close. No lifecycle or action mapping here. */
@Composable
internal fun CaptureDialog(message: MessageDialog, onDismissRequest: () -> Unit,
    onAction: (MessageAction) -> Unit, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {}) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(modifier, shape = RectangleShape, color = CaptureDesign.Panel,
            border = BorderStroke(1.dp, CaptureDesign.Border)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(message.title, Modifier.semantics { heading() }, style = CaptureDesign.Label, color = CaptureDesign.Text)
                Text(message.body, style = CaptureDesign.Body, color = CaptureDesign.Text)
                content()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaptureTextAction(message.close, onAction)
                    message.confirm?.let { CaptureTextAction(it, onAction) }
                }
            }
        }
    }
}

@Composable
internal fun DevelopCaption(message: UiMessage, modifier: Modifier = Modifier) {
    require(message.slot == MessageSlot.CAPTION)
    Text(message.body, modifier.background(CaptureDesign.Scrim, RectangleShape).padding(8.dp),
        style = CaptureDesign.Body, color = captureChromeColor(message.tone),
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}
