package dev.daarkolabs.tandem.ui.rows

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.DiffStats
import dev.daarkolabs.tandem.chat.RowLabel
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.chat.ToolKind
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.ui.ChatUiState
import dev.daarkolabs.tandem.ui.RenderedRow
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.monoTextStyle
import dev.daarkolabs.tandem.ui.smallTextStyle
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The states in which a call has no result yet: its row is drawn running and does not open. */
private val RUNNING: Set<ToolState> = setOf(ToolState.PENDING, ToolState.RUNNING, ToolState.WAITING)

/**
 * Whether [row] opens (spec 4.6.1): once it is no longer running, a group always does and a lone call does when its body has
 * something to show. A running row or group draws its chevron disabled, with no click, until its result arrives.
 */
internal fun canExpand(row: ToolRow): Boolean =
    row.state !in setOf(ToolState.PENDING, ToolState.RUNNING, ToolState.WAITING) && (row.calls.size > 1 || row.calls.single().body !is ToolBody.None)

/** Whether a group's sub-row for [call] opens: the rule of [canExpand] for one call. */
internal fun canExpand(call: ToolCall): Boolean = call.state !in RUNNING && call.body !is ToolBody.None

/** Whether [body] is Markdown the presenter parses when the body is first opened. */
internal fun parsedOnOpen(body: ToolBody): Boolean = body is ToolBody.Markdown || body is ToolBody.Links

/** [label]'s text with each of its spans in [mono] (spec 4.6.1 rule 9); spans outside the text are ignored. */
internal fun annotated(label: RowLabel, mono: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(label.text)
    label.spans.forEach { span ->
        val start = span.first.coerceIn(0, label.text.length)
        val end = (span.last + 1).coerceIn(start, label.text.length)
        if (end > start) addStyle(mono, start, end)
    }
}

/**
 * A tool call or a group of calls (spec 4.6.1): one secondary line with its label, diff stats and chevron. A click opens the call's
 * body below it, or a group's box of sub-rows ([ToolGroupBox]). The body is pre-built data; Markdown in it is parsed by the presenter
 * ([ChatUiState.onExpand]), never here.
 */
@Composable
internal fun ToolRowItem(rendered: RenderedRow, ui: ChatUiState) {
    val row = rendered.row as ToolRow
    val expandedState = ui.expanded(row.key)
    val expanded by expandedState
    val opens = canExpand(row)
    val lone = row.calls.singleOrNull()
    val needsParse = lone != null && parsedOnOpen(lone.body) && rendered.bodies[row.key] == null
    Column(Modifier.chatColumn().padding(top = row.gap.dp.dp)) {
        ToolLine(row.label, row.state, row.stats, opens, expanded && opens, minHeight = 24.dp) { expandedState.value = !expandedState.value }
        // An agent's sub-line: `Done (2 tool uses · 21.8k tokens · 5.8s)` (spec 4.8).
        lone?.progress?.takeIf { lone.kind == ToolKind.AGENT }?.let { Text(it, style = smallTextStyle(), color = JewelTheme.globalColors.text.info) }
        if (expanded && opens) {
            if (lone == null) {
                ToolGroupBox(row.calls, rendered.bodies, ui)
            } else {
                // The one place that asks for the body's Markdown: on the click that opens it, and again for a row that was already
                // open when its presenter was made. The presenter ignores a key it already has.
                if (needsParse) LaunchedEffect(row.key) { ui.onExpand(row.key) }
                ToolBodyView(lone, rendered.bodies[row.key], ui)
            }
        }
    }
}

/**
 * One tool line, a row's or a group's sub-row's: [label] in the colour of [state], [stats] before the chevron, the chevron that turns
 * when [open]. The whole line is the click target when [opens]; a running call draws its chevron disabled. Assistive technology
 * knows it as a button named by its label.
 */
@Composable
internal fun ToolLine(
    label: RowLabel,
    state: ToolState,
    stats: DiffStats?,
    opens: Boolean,
    open: Boolean,
    minHeight: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = JewelTheme.globalColors.text
    val tone = when (state) {
        ToolState.FAILED -> colors.error
        ToolState.DENIED, ToolState.INTERRUPTED -> colors.disabled
        ToolState.WAITING -> colors.warning
        else -> colors.info
    }
    val mono = monoTextStyle()
    val text = remember(label, JewelTheme.instanceUuid) { annotated(label, SpanStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize)) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hover = chatPalette().bubbleFill.copy(alpha = 0.5f)
    val angle by animateFloatAsState(if (open) 90f else 0f, tween(150))
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label.text
            }
            .thenIf(opens) {
                background(if (hovered) hover else Color.Transparent, RoundedCornerShape(6.dp))
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, onClick = onClick)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No leading dot in any state (spec 4.6.1).
        Text(text, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        stats?.let { DiffStatsText(it) }
        if (opens) {
            Icon(AllIconsKeys.General.ChevronRight, null, Modifier.padding(start = 8.dp).size(16.dp).rotate(angle), tint = colors.info)
        } else if (state in RUNNING) {
            Icon(AllIconsKeys.General.ChevronRight, null, Modifier.padding(start = 8.dp).size(16.dp), tint = colors.disabled)
        }
    }
}

/** `+A -B` in the added and removed colours; the zero side is shown (spec 4.6.1). The only place diff stats are drawn. */
@Composable
private fun DiffStatsText(stats: DiffStats) {
    val palette = chatPalette()
    val text = remember(stats, palette) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = palette.added)) { append("+${stats.added}") }
            withStyle(SpanStyle(color = palette.removed)) { append(" -${stats.removed}") }
        }
    }
    Text(text, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
}
