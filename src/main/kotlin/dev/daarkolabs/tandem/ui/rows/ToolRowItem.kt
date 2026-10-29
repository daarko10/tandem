package dev.daarkolabs.tandem.ui.rows

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
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
import dev.daarkolabs.tandem.ui.TandemColors
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.monoTextStyle
import dev.daarkolabs.tandem.ui.smallTextStyle
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Whether [row] opens (polish spec 2.1): a group always does, in every state, to list its calls; a lone call does when its body has
 * something to show (a live call's body so far included) or, for an Agent call, when its subagent has rows (spec 4.8, 4.9).
 */
internal fun canExpand(row: ToolRow): Boolean =
    row.calls.size > 1 || row.calls.single().let { it.body !is ToolBody.None || it.children.isNotEmpty() }

/** Whether a group's sub-row for [call] opens: when its body, live or finished, has something to show. */
internal fun canExpand(call: ToolCall): Boolean = call.body !is ToolBody.None

/**
 * The owners (`c:<call id>`) of the clamped bodies beneath [row] (polish spec 2.2): each of its calls and, for an Agent call, each
 * call of the rows nested in its body, recursively. Collapsing the row clamps them all again.
 */
internal fun clampOwners(row: ToolRow): List<String> {
    val owners = ArrayList<String>()
    fun add(row: ToolRow) {
        for (call in row.calls) {
            owners += "c:${call.id}"
            if (call.kind == ToolKind.AGENT) call.children.forEach { child -> if (child is ToolRow) add(child) }
        }
    }
    add(row)
    return owners
}

/** The dot before a live call's label (polish spec 2.1): spark-coloured while it runs, amber while it waits for the user. */
internal enum class Marker { NONE, RUNNING, WAITING }

/** The marker of [call]: [Marker.RUNNING] while its input streams or it runs, [Marker.WAITING] while it waits, else none. */
internal fun marker(call: ToolCall): Marker = when (call.state) {
    ToolState.PENDING, ToolState.RUNNING -> Marker.RUNNING
    ToolState.WAITING -> Marker.WAITING
    ToolState.DONE, ToolState.FAILED, ToolState.DENIED, ToolState.INTERRUPTED -> Marker.NONE
}

/** The marker of [row]'s own line: its call's for a lone row; none for a group, whose open box marks the live call. */
internal fun headerMarker(row: ToolRow): Marker = row.calls.singleOrNull()?.let(::marker) ?: Marker.NONE

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
    val expanded by ui.expanded(row.key)
    val opens = canExpand(row)
    val lone = row.calls.singleOrNull()
    val needsParse = lone != null && parsedOnOpen(lone.body) && rendered.bodies[row.key] == null
    // A Tandem edit's row (IDE-edits spec 3.4, 3.8): its tag after the label, `Show diff` while its diff is open.
    val edit = lone?.takeIf { it.review != null }
    Column(Modifier.chatColumn().padding(top = row.gap.dp.dp).thenIf(edit != null) { testTag(editRowTag(edit!!.id)) }) {
        ToolLine(
            row.label,
            row.state,
            row.stats,
            opens,
            expanded && opens,
            minHeight = 24.dp,
            marker = headerMarker(row),
            tag = lone?.tag,
        ) {
            // A lone row opens its call's sub-row too, so it stays open if the row becomes a group (polish spec 2.1); closing either
            // clamps every body beneath it again (2.2).
            ui.toggle(row.key, clampOwners(row), opensWith = lone?.let { "c:${it.id}" })
        }
        edit?.let { call ->
            val showDiff = LocalShowDiff.current
            showDiffClick(call, showDiff)?.let { click ->
                Link(
                    SHOW_DIFF_LABEL,
                    onClick = click,
                    modifier = Modifier.padding(start = 14.dp)
                        .testTag(showDiffTag(call.id))
                        .semantics { contentDescription = SHOW_DIFF_LABEL },
                )
            }
        }
        // An agent's sub-line: `Done (2 tool uses · 21.8k tokens · 5.8s)` (spec 4.8).
        lone?.progress?.takeIf { lone.kind == ToolKind.AGENT }?.let { Text(it, style = smallTextStyle(), color = JewelTheme.globalColors.text.info) }
        if (expanded && opens) {
            if (lone == null) {
                ToolGroupBox(row.calls, rendered.bodies, ui)
            } else if (lone.kind == ToolKind.AGENT) {
                // Every open asks, whatever the call holds yet: the presenter then parses the nested text and the agent's own body
                // as they arrive, while the row exists.
                LaunchedEffect(row.key) { ui.onExpand(row.key) }
                AgentBody(row.key, lone, rendered.bodies, ui)
            } else {
                // The one place that asks for the body's Markdown: on the click that opens it, and again for a row that was already
                // open when its presenter was made. The presenter ignores a key it already has.
                if (needsParse) LaunchedEffect(row.key) { ui.onExpand(row.key) }
                ToolBodyView(lone, rendered.bodies[row.key], ui, framed = true)
            }
        }
    }
}

/**
 * One tool line, a row's or a group's sub-row's: the [marker] dot of a live call, [label] in the colour of [state], [stats] before
 * the chevron, the chevron that turns when [open], in the label's colour (a failed row is red to its end, spec 4.2). The chevron is
 * drawn exactly when the line [opens], live or finished (polish spec 2.1), and the whole line is then the click target. Assistive
 * technology knows it as a button named by [description], its label unless the caller says otherwise (a streaming thinking row).
 * With [shimmer] a light band sweeps across the label ([Modifier.shimmer]): a streaming thinking row's, never a tool's (spec 15.1).
 * A [tag] (a Tandem edit's `with your changes` or `auto-accepted`, IDE-edits spec 3.4) follows the label, dimmed.
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
    description: String = label.text,
    marker: Marker = Marker.NONE,
    shimmer: Boolean = false,
    tag: String? = null,
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
                contentDescription = description
            }
            .thenIf(opens) {
                background(if (hovered) hover else Color.Transparent, RoundedCornerShape(6.dp))
                    .hoverable(interaction)
                    .clickable(interaction, indication = null, onClick = onClick)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A live call's dot, 8 dp before its label (polish spec 2.1, amending spec 4.6.1 and 4.2 for this dot).
        val dot = when (marker) {
            Marker.NONE -> null
            Marker.RUNNING -> TandemColors.spark
            Marker.WAITING -> colors.warning
        }
        if (dot != null) Box(Modifier.padding(end = 8.dp).size(6.dp).background(dot, RoundedCornerShape(50)))
        // The band on the label alone, never on the stats or the chevron; +∞ clamps to the label's width (spec 15.1).
        val band = rememberShimmer(shimmer)
        Text(
            text,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
                .shimmer(shimmer, { band.value }, { Float.POSITIVE_INFINITY }, colors.normal),
        )
        tag?.let { Text(it, style = smallTextStyle(), color = colors.disabled, maxLines = 1, modifier = Modifier.padding(start = 8.dp)) }
        stats?.let { DiffStatsText(it) }
        if (opens) {
            Icon(AllIconsKeys.General.ChevronRight, null, Modifier.padding(start = 8.dp).size(16.dp).rotate(angle), tint = tone)
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
