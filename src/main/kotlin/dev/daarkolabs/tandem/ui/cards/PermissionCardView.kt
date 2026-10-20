@file:OptIn(ExperimentalLayoutApi::class)

package dev.daarkolabs.tandem.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.Payload
import dev.daarkolabs.tandem.chat.PendingCard
import dev.daarkolabs.tandem.chat.PermissionCard
import dev.daarkolabs.tandem.ui.bottomFade
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.monoTextStyle
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontallyScrollableContainer
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/** A payload longer than this many lines is clamped until `Show more` (spec 4.12 item 4). */
private const val CLAMP_LINES = 8

/** ... and shown at most this tall while clamped. */
private val CLAMP_HEIGHT = 160.dp

/**
 * The permission card (spec 4.12): a warning dot and `Allow Claude to <verb> <object>?`, the subagent that asks, the request's
 * description, what the call would do in a mono box (clamped at 8 lines with `Show more`), then `Deny` (Esc), `Always allow for
 * session` (Cmd/Ctrl+Enter, only when the request offers it) and `Allow once` (Enter, the default, which takes the focus when
 * [takeFocus] says so), and how many more requests wait behind it ([moreWaiting]).
 */
@Composable
internal fun PermissionCardView(
    card: PermissionCard,
    moreWaiting: Int,
    takeFocus: () -> Boolean,
    onAllowOnce: () -> Unit,
    onAllowForSession: () -> Unit,
    onDeny: () -> Unit,
) {
    val defaultFocus = remember(card.requestId) { FocusRequester() }
    val colors = JewelTheme.globalColors.text
    CardFrame(
        card.requestId,
        "permission-card",
        "Permission request",
        textFieldFocused = false,
        defaultFocus = defaultFocus,
        takeFocus = takeFocus,
        onEnter = onAllowOnce,
        onCmdEnter = onAllowForSession.takeIf { card.canAlwaysAllow },
        onEscape = onDeny,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // Centred on the title's first line.
            Box(Modifier.padding(top = 6.dp).size(6.dp).background(colors.warning, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(permissionTitleText(card), color = colors.normal)
        }
        agentLine(card)?.let { Text(it, color = colors.info) }
        card.description?.takeIf { it != card.objectText }?.let { Text(it, color = colors.info) }
        PayloadView(card)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CardButton("Deny", "Esc", primary = false, onClick = onDeny)
            Spacer(Modifier.weight(1f))
            if (card.canAlwaysAllow) CardButton("Always allow for session", cmdEnterCap(), primary = false, onClick = onAllowForSession)
            CardButton("Allow once", "↩", primary = true, modifier = Modifier.focusRequester(defaultFocus), onClick = onAllowOnce)
        }
        MoreWaiting(moreWaiting)
    }
}

/** The card's title as plain text, exactly as the IDE tests match it (spec 4.12 item 1). */
internal fun permissionTitle(card: PermissionCard): String =
    if (card.objectText.isEmpty()) "Allow Claude to ${card.verb}?" else "Allow Claude to ${card.verb} ${card.objectText}?"

/** [permissionTitle] with the verb in semibold and the object in the editor font. */
@Composable
private fun permissionTitleText(card: PermissionCard): AnnotatedString {
    val mono = monoTextStyle()
    return remember(card.verb, card.objectText, mono) {
        buildAnnotatedString {
            append("Allow Claude to ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(card.verb) }
            if (card.objectText.isNotEmpty()) {
                append(" ")
                withStyle(SpanStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize)) { append(card.objectText) }
            }
            append("?")
        }
    }
}

/** `From the agent "<description>"` when a subagent sent the request (spec 4.12 item 2, 4.20). */
internal fun agentLine(card: PendingCard): String? = card.agentDescription?.let { "From the agent \"$it\"" }

/**
 * What the call would do (spec 4.12 item 4), in the editor font in a filled box that scrolls sideways: a command, a path, JSON; an
 * edit's path, then a box of its old lines (`- `, on the deleted fill) and new lines (`+ `, on the inserted fill). More than 8 lines
 * are clamped to 160 dp, faded, with `Show more` / `Show less`.
 */
@Composable
private fun PayloadView(card: PermissionCard) {
    val payload = card.payload
    if (payload is Payload.None) return
    val lines = remember(payload) { payloadLineCount(payload) }
    var expanded by remember(card.requestId) { mutableStateOf(false) }
    val clamped = lines > CLAMP_LINES && !expanded
    val mono = monoTextStyle()
    val textColor = JewelTheme.globalColors.text.normal
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().thenIf(clamped) { heightIn(max = CLAMP_HEIGHT).clipToBounds().bottomFade(60.dp) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (payload) {
                is Payload.Mono -> PayloadBox { Text(payload.text, style = mono, color = textColor) }
                is Payload.Json -> PayloadBox { Text(payload.text, style = mono, color = textColor) }
                is Payload.OldNew -> {
                    PayloadBox { Text(payload.path, style = mono, color = textColor) }
                    val palette = chatPalette()
                    PayloadBox(padded = false) {
                        editLines(payload.old).forEach { DiffLine("- $it", palette.deletedFill) }
                        editLines(payload.new).forEach { DiffLine("+ $it", palette.insertedFill) }
                    }
                }
                Payload.None -> Unit
            }
        }
    }
    if (lines > CLAMP_LINES) {
        Link(if (expanded) "Show less" else "Show more", onClick = { expanded = !expanded })
    }
}

/** How many lines [payload] shows: an edit's path, then its old and new lines ([editLines]). */
internal fun payloadLineCount(payload: Payload): Int = when (payload) {
    is Payload.Mono -> payload.text.lines().size
    is Payload.Json -> payload.text.lines().size
    is Payload.OldNew -> 1 + editLines(payload.old).size + editLines(payload.new).size
    Payload.None -> 0
}

/** The lines of one side of an edit: none when [text] is empty (an insertion has no old lines, a deletion no new ones). */
internal fun editLines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.lines()

/** One line of an edit, on its [fill], across the whole box. */
@Composable
private fun DiffLine(text: String, fill: Color) {
    Text(
        text,
        style = monoTextStyle(),
        color = JewelTheme.globalColors.text.normal,
        modifier = Modifier.fillMaxWidth().background(fill).padding(horizontal = 8.dp),
    )
}

/**
 * A box in the command and code fill with a hairline border (spec 4.2), radius 6 dp, 8 dp inside (spec 4.12 item 4), only above and
 * below when not [padded] (an edit's lines pad themselves, so their fills span the box); what it holds scrolls sideways rather than
 * wrapping, and its lines are at least as wide as the box.
 */
@Composable
private fun PayloadBox(padded: Boolean = true, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().background(chatPalette().boxFill, shape).border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(horizontal = if (padded) 8.dp else 0.dp, vertical = 8.dp),
    ) {
        val width = maxWidth
        HorizontallyScrollableContainer(Modifier.fillMaxWidth()) {
            Column(Modifier.widthIn(min = width).width(IntrinsicSize.Max)) { content() }
        }
    }
}
