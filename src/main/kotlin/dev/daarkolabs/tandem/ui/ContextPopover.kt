package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceAtMost
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.BarSegment
import dev.daarkolabs.tandem.chat.BreakdownGroup
import dev.daarkolabs.tandem.chat.CategoryRow
import dev.daarkolabs.tandem.chat.ContextTone
import dev.daarkolabs.tandem.chat.GroupItem
import dev.daarkolabs.tandem.chat.popoverStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator

// The context ring's popover (docs/superpowers/specs/2026-10-22-desktop-parity-design.md 3.1, 3.5, 3.7, 16.2): the breakdown of the
// context window. Everything it shows comes from its controller; the status line is decided by `popoverStatus`, never here.

/**
 * What the ring needs to open its popover (spec 3.1): [open] makes the controller of one open popover in the scope it is given, [busy]
 * disables the two buttons while a turn runs, the popover is at most [maxHeight] high (a provider, read only by the open popover, so a
 * resize recomposes nothing else), `See detailed breakdown` calls [onDetailed] and `Show session usage` [onUsage] (spec 16.2), each
 * after the popover closed.
 */
internal class ContextPopoverHost(
    val open: (CoroutineScope) -> ContextBreakdownController,
    val busy: StateFlow<Boolean>,
    val maxHeight: () -> Dp,
    val onDetailed: () -> Unit,
    val onUsage: () -> Unit,
)

/**
 * The chat column's width, as the prompt bar measures it; null where nothing measured it. A provider, read only by the open popover,
 * so a resize recomposes nothing else.
 */
internal val LocalChatColumnWidth = compositionLocalOf<() -> Dp?> { { null } }

/** The popover's width (spec 3.1): `min(360 dp, chat column − 32 dp)`, at least 280 dp; 360 dp without a measured column. */
internal fun contextPopoverWidth(column: Dp?): Dp =
    if (column == null) POPOVER_MAX_WIDTH else (column - 32.dp).coerceAtMost(POPOVER_MAX_WIDTH).coerceAtLeast(POPOVER_MIN_WIDTH)

private val POPOVER_MAX_WIDTH = 360.dp
private val POPOVER_MIN_WIDTH = 280.dp

/** A passive menu item's content lines up with a selectable item's, which Jewel insets by its item padding (measured at 13 dp in 0.37). */
internal val PASSIVE_INSET: Modifier = Modifier.padding(horizontal = 13.dp)

/** What a disclosure reads to assistive technology. */
private fun disclosure(expanded: Boolean): String = if (expanded) "Expanded" else "Collapsed"

/** The disclosure's glyph: `⌄` open, `›` closed. */
private fun chevron(expanded: Boolean): String = if (expanded) "⌄" else "›"

/**
 * The popover of [controller] above the ring (spec 3.1), right-aligned [gapPx] above it, in a Jewel [PopupMenu]: the header (the
 * category list's toggle), the status line with `Retry` after a failure, the bar, and while the list is open the category rows and
 * the groups, each group collapsed when the popover opens; then `See detailed breakdown` and `Show session usage`, disabled while
 * [host]'s turn runs. A click on the header, a group or `Retry` keeps the popover open through the close request Jewel makes after
 * every item's click (a hold-open flag, as a footer menu's page turn); a click on the ring itself ([anchorHovered]) closes it through
 * the ring. [onClose] closes it, saying whether a key (Esc, or Enter on `See detailed breakdown` or `Show session usage`) did, so the
 * ring can take the focus back (spec 3.5): the two buttons' actions wait for that close request, which carries Jewel's own input mode.
 * Nothing is drawn until the controller's first build, so a reopened popover draws the kept answer at once (spec 3.1).
 *
 * Jewel's menu scrolls its own column under the height cap ([ContextPopoverHost.maxHeight], passed as the menu's modifier, which
 * `MenuContent` applies around its `verticalScroll` column), so every row stays a menu item and keeps Jewel's keys.
 */
@Composable
internal fun ContextPopover(
    host: ContextPopoverHost,
    controller: ContextBreakdownController,
    gapPx: Int,
    anchorHovered: () -> Boolean,
    onClose: (byKeyboard: Boolean) -> Unit,
) {
    val built by controller.model.collectAsState()
    val fetch by controller.fetch.collectAsState()
    val rowsOpen by controller.rowsOpen.collectAsState()
    val busy by host.busy.collectAsState()
    var openGroups: PersistentSet<String> by remember { mutableStateOf(persistentSetOf()) }
    // Jewel asks to close the menu after every item's click; a toggle or a retry holds it open through that one request.
    val holdOpen = remember { mutableStateOf(false) }
    // A button's action, run on the close request that follows its click, which says whether the click was a key (Enter).
    val afterClose = remember { mutableStateOf<(() -> Unit)?>(null) }
    val model = built ?: return
    val status = popoverStatus(fetch is FetchState.Loading, (fetch as? FetchState.Failed)?.text, fetch is FetchState.Stopped, model.rows.isNotEmpty())
    val palette = chatPalette()
    val dark = JewelTheme.isDark
    val errorColor = JewelTheme.globalColors.text.error
    val small = smallTextStyle()
    val width = contextPopoverWidth(LocalChatColumnWidth.current())
    val header = (model.value ?: "") + (status.headerSuffix ?: "")
    val groups = openGroups
    PopupMenu(
        onDismissRequest = { mode ->
            when {
                holdOpen.value -> {
                    holdOpen.value = false
                    false
                }
                // A click on the ring itself closes the popover through the ring (its click toggles it).
                mode == InputMode.Touch && anchorHovered() -> false
                else -> {
                    onClose(mode == InputMode.Keyboard)
                    afterClose.value?.let { action ->
                        afterClose.value = null
                        action()
                    }
                    true
                }
            }
        },
        popupPositionProvider = remember(gapPx) { AboveAnchor(alignEnd = true, gapPx = gapPx) },
        modifier = Modifier.width(width).heightIn(max = host.maxHeight())
            .testTag("context-popover")
            .semantics { contentDescription = "Context window breakdown" },
    ) {
        selectableItem(selected = false, onClick = {
            holdOpen.value = true
            controller.toggleRows()
        }) {
            Row(
                Modifier.fillMaxWidth().semantics {
                    contentDescription = "Context window categories"
                    stateDescription = disclosure(rowsOpen)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Context window", style = small, color = palette.dim)
                Spacer(Modifier.weight(1f))
                Text(header, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
                Text(chevron(rowsOpen), style = small, color = palette.dim, modifier = Modifier.padding(start = 6.dp))
            }
        }
        status.line?.let { line ->
            passiveItem {
                Text(line, style = small, color = if (status.error) errorColor else palette.dim, modifier = Modifier.fillMaxWidth().then(PASSIVE_INSET))
            }
        }
        if (status.error) {
            selectableItem(selected = false, onClick = {
                holdOpen.value = true
                controller.retry()
            }) { Text("Retry", style = small) }
        }
        if (model.segments.isNotEmpty()) {
            passiveItem { ContextBar(model.segments, palette.dim, dark) }
        }
        if (rowsOpen) {
            val rowsAlpha = if (status.dimRows) 0.5f else 1f
            model.rows.forEach { row -> passiveItem { CategoryRowView(row, palette.dim, dark, rowsAlpha) } }
            model.groups.forEach { group ->
                val expanded = group.key in groups
                selectableItem(selected = false, onClick = {
                    holdOpen.value = true
                    openGroups = if (expanded) openGroups.remove(group.key) else openGroups.add(group.key)
                }) { GroupHeader(group, expanded, palette.dim, rowsAlpha) }
                if (expanded) {
                    group.items.forEach { item -> passiveItem { GroupItemRow(item, palette.dim, rowsAlpha) } }
                    if (group.more > 0) {
                        passiveItem { Text("and ${group.more} more", style = small, color = palette.dim, modifier = PASSIVE_INSET.padding(start = 14.dp)) }
                    }
                }
            }
        }
        separator()
        selectableItem(selected = false, enabled = !busy, onClick = { afterClose.value = host.onDetailed }) { Text(if (busy) "See detailed breakdown (after this turn)" else "See detailed breakdown", style = small) }
        selectableItem(selected = false, enabled = !busy, onClick = { afterClose.value = host.onUsage }) { Text(if (busy) "Show session usage (after this turn)" else "Show session usage", style = small) }
    }
}

/**
 * The segmented bar (spec 3.1): 4 dp high, full width, rounded, over a track in [dim] at 30 % alpha; one segment per used category in
 * row order, its share of the width, at least 1 px when not zero, in its tone's colour ([TandemColors.contextTone]) or [dim].
 */
@Composable
private fun ContextBar(segments: ImmutableList<BarSegment>, dim: Color, dark: Boolean) {
    Canvas(PASSIVE_INSET.fillMaxWidth().padding(vertical = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp))) {
        drawRect(dim.copy(alpha = 0.3f))
        var x = 0f
        for (segment in segments) {
            if (x >= size.width) break
            if (segment.fraction <= 0f) continue
            val w = (segment.fraction * size.width).coerceAtLeast(1f).coerceAtMost(size.width - x)
            drawRect(toneColor(segment.tone, dim, dark), topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
    }
}

private fun toneColor(tone: ContextTone, dim: Color, dark: Boolean): Color = TandemColors.contextTone(tone, dark) ?: dim

/**
 * A category row (spec 3.1, 3.7): an 8 dp swatch (hollow for free space), the CLI's name, the tokens and the share; one node for
 * assistive technology, its [CategoryRow.description].
 */
@Composable
private fun CategoryRowView(row: CategoryRow, dim: Color, dark: Boolean, alpha: Float) {
    val small = smallTextStyle()
    Row(
        PASSIVE_INSET.fillMaxWidth().alpha(alpha).semantics(mergeDescendants = true) { contentDescription = row.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val swatch = Modifier.size(8.dp)
        if (row.tone == ContextTone.FREE) {
            Box(swatch.border(1.dp, dim, CircleShape))
        } else {
            Box(swatch.background(toneColor(row.tone, dim, dark), CircleShape))
        }
        Text(row.name, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).weight(1f))
        Text(row.tokens, style = small, color = dim, modifier = Modifier.padding(start = 8.dp))
        Text(row.share, style = small, color = dim, modifier = Modifier.padding(start = 8.dp))
    }
}

/** A group's header (spec 3.1, 3.7): `›` or `⌄`, the title, the total and the count, `<title>, <count> items, <total> tokens`. */
@Composable
private fun GroupHeader(group: BreakdownGroup, expanded: Boolean, dim: Color, alpha: Float) {
    val small = smallTextStyle()
    Row(
        Modifier.fillMaxWidth().alpha(alpha).semantics {
            contentDescription = "${group.title}, ${group.count} items, ${group.total} tokens"
            stateDescription = disclosure(expanded)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(chevron(expanded), style = small, color = dim, modifier = Modifier.width(14.dp))
        Text(group.title, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(group.total, style = small, color = dim, modifier = Modifier.padding(start = 8.dp))
        Text(group.count.toString(), style = small, color = dim, modifier = Modifier.padding(start = 8.dp))
    }
}

/** An item of an open group: its label (memory-file paths ellipsised in the middle), dim when [GroupItem.dim], its tooltip when set. */
@Composable
private fun GroupItemRow(item: GroupItem, dim: Color, alpha: Float) {
    val small = smallTextStyle()
    val row = @Composable {
        Row(PASSIVE_INSET.fillMaxWidth().alpha(alpha).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.label,
                style = small,
                color = if (item.dim) dim else Color.Unspecified,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f),
            )
            Text(item.tokens, style = small, color = dim, modifier = Modifier.padding(start = 8.dp))
        }
    }
    val tooltip = item.tooltip
    if (tooltip != null) WithTooltip(tooltip) { row() } else row()
}
