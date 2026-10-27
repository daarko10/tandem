package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.intellij.openapi.util.SystemInfo
import dev.daarkolabs.tandem.chat.CommandScope
import dev.daarkolabs.tandem.chat.PaletteItem
import dev.daarkolabs.tandem.chat.SlashPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.scrollbarContentSafePadding
import org.jetbrains.jewel.ui.component.styling.ScrollbarVisibility
import org.jetbrains.jewel.ui.theme.scrollbarStyle
import org.jetbrains.jewel.ui.theme.textAreaStyle
import kotlin.math.max
import kotlin.math.min

/** The list shows at most this many rows before it scrolls (MCP spec 14). */
private const val MAX_ROWS: Int = 10

/** The narrowest the list is, so that a highlighted row of a short catalogue still has room for its argument hint. */
private val MIN_LIST_WIDTH: Dp = 200.dp

/** The description box beside the list is at most this wide, and goes under the list when less than [MIN_DESCRIPTION_WIDTH] is left. */
private val MAX_DESCRIPTION_WIDTH: Dp = 360.dp
private val MIN_DESCRIPTION_WIDTH: Dp = 140.dp

/** Between the list and the description box, and between the popup and the composer box. */
private val GAP: Dp = 6.dp
private val ANCHOR_GAP: Dp = 8.dp

/** A row's highlight is inset by [ROW_INSET] from the list's edges, and its text by [TEXT_INSET] from the highlight's. */
private val ROW_INSET: Dp = 4.dp
private val TEXT_INSET: Dp = 10.dp

/** Above the first row and under the last (or the footer), inside the list's frame. */
private val FRAME_PADDING: Dp = 4.dp

/** The description box's padding; it wraps to as many lines as the description needs, at most this many before an ellipsis. */
private val DESCRIPTION_PADDING_X: Dp = 8.dp
private val DESCRIPTION_PADDING_Y: Dp = 6.dp
private const val DESCRIPTION_LINES: Int = 8

/** The composer's text after a lone `/` while the palette is open (MCP spec 14). */
internal const val FILTER_HINT: String = "Type to filter"

/** Where the highlighted row's description goes: beside the list when the composer is wide enough, else under it. */
internal enum class DescriptionPlace { BESIDE, UNDER }

/** The palette's widths in pixels: the list's, where the description goes, and how wide the description may be. */
internal data class PaletteGeometry(val listWidth: Int, val place: DescriptionPlace, val descriptionMaxWidth: Int)

/**
 * The palette's widths for rows whose widest content is [contentWidth], above a composer box [boxWidth] wide: the list fits that
 * content, at least [minListWidth], never wider than the box; the description goes beside it, [gap] away and at most
 * [maxDescription] wide, when at least [minDescription] is left of the box's width, else under it, as wide as the list. All in pixels.
 */
internal fun paletteGeometry(
    contentWidth: Int,
    boxWidth: Int,
    minListWidth: Int,
    gap: Int,
    minDescription: Int,
    maxDescription: Int,
): PaletteGeometry {
    val box = max(0, boxWidth)
    val list = max(contentWidth, minListWidth).coerceAtMost(box)
    val room = box - list - gap
    return if (room >= minDescription) {
        PaletteGeometry(list, DescriptionPlace.BESIDE, min(room, maxDescription))
    } else {
        PaletteGeometry(list, DescriptionPlace.UNDER, list)
    }
}

/**
 * What follows a row's name, dimmed: the argument hint only on the [selected] row, and ` · via /<alias>` on a row only an alias
 * matched.
 */
internal fun rowTrailing(item: PaletteItem, selected: Boolean): String = buildString {
    if (selected && item.argumentHint.isNotEmpty()) append(' ').append(item.argumentHint)
    item.via?.let { append(" · via /").append(it) }
}

/** The scope's label (MCP spec 3.4, spec 17.6), shown before the description; none for a built-in or an entry of no known source. */
internal fun scopeLabel(scope: CommandScope): String? = when (scope) {
    CommandScope.BUILTIN -> null
    CommandScope.PROJECT -> "project"
    CommandScope.USER -> "user"
    CommandScope.PLUGIN -> "plugin"
    CommandScope.CLAUDE_AI -> "claude.ai"
    CommandScope.MANAGED -> "managed"
    CommandScope.CLI_FLAG -> "CLI flag"
    CommandScope.MCP -> "MCP"
    CommandScope.OTHER -> null
}

/**
 * The horizontal space a row takes beside its text, in pixels, as its paddings lay it out, each side rounded on its own: [ROW_INSET]
 * and [TEXT_INSET] on both sides, then, while the list scrolls, [scrollbarPadding] at the row's end (the track drawn over the rows)
 * and [scrollbarTakenOut] (the track Jewel's container takes out of the rows' width).
 */
internal fun Density.rowInsetsPx(scrollbarPadding: Dp = 0.dp, scrollbarTakenOut: Dp = 0.dp): Int =
    2 * ROW_INSET.roundToPx() + 2 * TEXT_INSET.roundToPx() + scrollbarPadding.roundToPx() + scrollbarTakenOut.roundToPx()

/** The horizontal space the footer takes beside its text, in pixels: one padding of [ROW_INSET] + [TEXT_INSET], each side rounded. */
internal fun Density.footerInsetsPx(): Int = 2 * (ROW_INSET + TEXT_INSET).roundToPx()

/**
 * The width of the scrollbar's track that Jewel's container takes out of the content beside it: only on macOS ([mac]) with scroll
 * bars always shown ([alwaysVisible]), the track's expanded [thickness]; elsewhere the track is drawn over the content, which keeps
 * `scrollbarContentSafePadding()` clear instead.
 */
internal fun scrollbarTakenOut(mac: Boolean, alwaysVisible: Boolean, thickness: Dp): Dp = if (mac && alwaysVisible) thickness else 0.dp

/** Whether [item] has anything for the description box: a description or a scope. */
internal fun hasDescription(item: PaletteItem): Boolean = item.description.isNotEmpty() || scopeLabel(item.scope) != null

/** The description box's text: the scope in [dim] (with ` · ` before a description), then the description. */
internal fun descriptionText(item: PaletteItem, dim: Color): AnnotatedString = buildAnnotatedString {
    scopeLabel(item.scope)?.let { label ->
        withStyle(SpanStyle(color = dim)) {
            append(label)
            if (item.description.isNotEmpty()) append(" · ")
        }
    }
    append(item.description)
}

/**
 * The top of the [selected] row in the list's viewport, [viewportHeight] tall, whose first visible row is [firstIndex] scrolled up by
 * [firstOffset], every row [rowHeight] tall; a row scrolled out of view reads as the nearest edge's row.
 */
internal fun selectedRowTop(selected: Int, firstIndex: Int, firstOffset: Int, rowHeight: Int, viewportHeight: Int): Int =
    ((selected - firstIndex) * rowHeight - firstOffset).coerceIn(0, max(0, viewportHeight - rowHeight))

/**
 * The description box's top beside the list: level with the highlighted row ([rowTop]), moved up as far as it must to end with the
 * popup, [height] tall.
 */
internal fun descriptionTop(rowTop: Int, boxHeight: Int, height: Int): Int = rowTop.coerceIn(0, max(0, height - boxHeight))

/** Whether the composer shows [FILTER_HINT] after its text: a lone `/` with the palette open ([palette]). */
internal fun filterHintShown(text: CharSequence, palette: SlashPalette?): Boolean = palette != null && text.length == 1 && text[0] == '/'

/**
 * The slash-command palette (MCP spec 3.4, 14), in the desktop app's form: a compact popup above the composer box it is placed in, at
 * the box's left edge, its bottom 8 dp above the box's top. The list is as wide as its widest row (a position provider cannot size
 * a popup, so the rows are measured here, with room for the scrollbar while it scrolls), at least 200 dp, never wider than the box
 * ([boxWidthPx]); while the popup is open its width only grows, so neither it nor the description's place moves as the user types
 * and the rows thin out. One line per command, its name
 * without the slash, the highlighted row's argument hint after it, dimmed, and ` · via /<alias>` on a row only an alias matched. It
 * shows 10 rows and scrolls past that, its selected row scrolled into view; the footer (`No matching command`, `Commands load when
 * the session starts`) is its last line. The highlighted row's description, the scope first, sits in a box beside the list, level
 * with the row and up to 360 dp wide, when the box leaves room for it, else under the list, as wide as the list and as tall as its
 * text; it shows the whole description, wrapped to as many lines as it needs, up to 8 before an ellipsis.
 *
 * The popup never takes the focus and no click outside dismisses it, so every key still reaches the text area and a press outside
 * goes to what it lands on; it has no dismiss rule of its own, the composer shows it while the palette is open. It is at most
 * [maxHeightPx] tall, the space above the box, never placed over the box. A click on a row calls [onPick] with it. The list is given
 * its exact height (its rows, at most 10): Jewel's scroll container measures its scrollbar at the height it may take, and a popup
 * does not clip it, so with only a maximum the scrollbar ran down over the box. Nothing it draws leaves its bounds.
 */
@Composable
internal fun SlashPaletteView(palette: SlashPalette, boxWidthPx: Int, maxHeightPx: Int, onPick: (PaletteItem) -> Unit) {
    val density = LocalDensity.current
    val anchorGap = with(density) { ANCHOR_GAP.roundToPx() }
    val position = remember(anchorGap) { AboveAnchor(alignEnd = false, gapPx = anchorGap) }
    val currentOnPick by rememberUpdatedState(onPick)
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        val nameStyle = JewelTheme.defaultTextStyle
        val footerStyle = smallTextStyle()
        val rowHeight = maxOf(24.dp, lineHeight(nameStyle) + 6.dp)
        val rowPx = with(density) { rowHeight.roundToPx() }
        // Never cut by the frame's own padding: the weight in ListFrame then only has to make room for the footer.
        val listPx = min(min(palette.items.size, MAX_ROWS) * rowPx, max(0, maxHeightPx - 2 * with(density) { FRAME_PADDING.roundToPx() }))
        // While the list scrolls its scrollbar takes room beside the rows, or is drawn over their end: either way the rows keep it.
        val scrolls = palette.items.size * rowPx > listPx
        val scrollbarPadding = if (scrolls) scrollbarContentSafePadding() else 0.dp
        val visibility = JewelTheme.scrollbarStyle.scrollbarVisibility
        val alwaysVisible = visibility is ScrollbarVisibility.AlwaysVisible
        val takenOut = if (scrolls) scrollbarTakenOut(SystemInfo.isMac, alwaysVisible, visibility.trackThicknessExpanded) else 0.dp
        val measurer = rememberTextMeasurer()
        // Each text measured once while the popup is open: the list fits the widest of the rows shown, and the footer. A row's name
        // and what follows it are two Texts, each as wide as its own measure, so they are measured apart.
        val widths = remember(nameStyle, density) { HashMap<String, Int>() }
        val measured = { text: String ->
            widths.getOrPut(text) { measurer.measure(text, nameStyle, maxLines = 1, softWrap = false).size.width }
        }
        val contentWidth = remember(palette.items, palette.footer, nameStyle, footerStyle, density, scrollbarPadding, takenOut) {
            val rows = palette.items.maxOfOrNull { item ->
                val trailing = rowTrailing(item, selected = false)
                measured(item.name) + if (trailing.isEmpty()) 0 else measured(trailing)
            }
            val footer = palette.footer?.let { measurer.measure(it, footerStyle, maxLines = 1, softWrap = false).size.width }
            with(density) {
                max(rows?.let { it + rowInsetsPx(scrollbarPadding, takenOut) } ?: 0, footer?.let { it + footerInsetsPx() } ?: 0)
            }
        }
        // The widest content seen since the popup opened: the width, and where the description goes, only grow (spec 14).
        val widest = remember(nameStyle, density) { intArrayOf(0) }
        widest[0] = max(widest[0], contentWidth)
        val geometry = with(density) {
            paletteGeometry(
                widest[0],
                boxWidthPx,
                MIN_LIST_WIDTH.roundToPx(),
                GAP.roundToPx(),
                MIN_DESCRIPTION_WIDTH.roundToPx(),
                MAX_DESCRIPTION_WIDTH.roundToPx(),
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(palette.query, palette.selected) { scrollIntoView(listState, palette.selected) }
        val selectedNow by rememberUpdatedState(palette.selected)
        // Against the list's viewport as laid out: the footer's line can cut it shorter than it asked for.
        val rowTop by remember(listState, rowPx) {
            derivedStateOf {
                val viewport = listState.layoutInfo.viewportSize.height
                selectedRowTop(selectedNow, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, rowPx, viewport)
            }
        }
        val selected = palette.items.getOrNull(palette.selected)?.takeIf(::hasDescription)
        val listWidth = with(density) { geometry.listWidth.toDp() }
        val outer = Modifier.heightIn(max = with(density) { max(0, maxHeightPx).toDp() })
            .testTag("slash-palette")
            .semantics { contentDescription = "Slash commands" }
        when (geometry.place) {
            DescriptionPlace.BESIDE -> BesideList(
                rowTopPx = with(density) { FRAME_PADDING.roundToPx() } + rowTop,
                gapPx = with(density) { GAP.roundToPx() },
                modifier = outer,
            ) {
                ListFrame(palette, listState, listPx, rowHeight, scrollbarPadding, Modifier.width(listWidth)) { currentOnPick(it) }
                if (selected != null) {
                    DescriptionBox(selected, Modifier.widthIn(max = with(density) { geometry.descriptionMaxWidth.toDp() }))
                }
            }
            DescriptionPlace.UNDER -> Column(outer.width(listWidth)) {
                ListFrame(palette, listState, listPx, rowHeight, scrollbarPadding, Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    currentOnPick(it)
                }
                // As tall as the highlighted row's whole description (up to 8 lines); the list, weighted, gives way when space is short.
                if (selected != null) {
                    Spacer(Modifier.height(GAP))
                    DescriptionBox(selected, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * The list's frame and the description box beside it: the list at the bottom, the box [gapPx] to its right with its top at [rowTopPx]
 * from the list's top, moved up as far as it must to end with the list; a box taller than the list makes the popup taller, the list
 * still at its bottom (next to the composer).
 */
@Composable
private fun BesideList(rowTopPx: Int, gapPx: Int, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val list = measurables[0].measure(loose)
        val room = if (loose.hasBoundedWidth) max(0, loose.maxWidth - list.width - gapPx) else Constraints.Infinity
        val description = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = room))
        val height = max(list.height, description?.height ?: 0)
        val width = list.width + (description?.let { gapPx + it.width } ?: 0)
        layout(width, height) {
            val listTop = height - list.height
            list.place(0, listTop)
            description?.place(list.width + gapPx, descriptionTop(listTop + rowTopPx, description.height, height))
        }
    }
}

/**
 * The rows in a framed, scrolling list [listPx] tall, each keeping [scrollbarPadding] clear at its end, then the footer; a click on a
 * row calls [onPick] with it.
 */
@Composable
private fun ListFrame(
    palette: SlashPalette,
    listState: LazyListState,
    listPx: Int,
    rowHeight: Dp,
    scrollbarPadding: Dp,
    modifier: Modifier,
    onPick: (PaletteItem) -> Unit,
) {
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.clipToBounds()
            .background(colors.panelBackground, shape)
            .border(1.dp, colors.borders.normal, shape)
            .padding(vertical = FRAME_PADDING),
    ) {
        if (palette.items.isNotEmpty()) {
            // The weight leaves the footer its line when the space above the box is short; the height is then cut to what is left.
            VerticallyScrollableContainer(
                listState,
                Modifier.weight(1f, fill = false).height(with(LocalDensity.current) { listPx.toDp() }).clipToBounds(),
            ) {
                LazyColumn(state = listState) {
                    itemsIndexed(palette.items, key = { _, item -> item.name }) { index, item ->
                        PaletteRow(item, selected = index == palette.selected, rowHeight, scrollbarPadding) { onPick(item) }
                    }
                }
            }
        }
        palette.footer?.let { footer ->
            Text(
                footer,
                style = smallTextStyle(),
                color = colors.text.info,
                modifier = Modifier.padding(horizontal = ROW_INSET + TEXT_INSET, vertical = 4.dp),
            )
        }
    }
}

/**
 * One command: its name, then [rowTrailing] dimmed, on one line [height] tall (at least 24 dp, so the IDE tests' click at (10, 10)
 * lands on it, MCP spec 3.7); the selected row on a rounded fill inset from the list's edges. Its text keeps [scrollbarPadding] clear
 * at its end, where the scrollbar's track may be drawn.
 */
@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, height: Dp, scrollbarPadding: Dp, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = ROW_INSET)
            .height(height)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) chatPalette().bubbleFill else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "/${item.name}"
                this.selected = selected
            }
            .padding(horizontal = TEXT_INSET)
            .padding(end = scrollbarPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val trailing = rowTrailing(item, selected)
        if (trailing.isNotEmpty()) {
            Text(
                trailing,
                color = JewelTheme.globalColors.text.info,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/** The highlighted row's [descriptionText] in a small framed box, wrapped to as many lines as it needs: at most 8, then an ellipsis. */
@Composable
private fun DescriptionBox(item: PaletteItem, modifier: Modifier) {
    val colors = JewelTheme.globalColors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.background(colors.panelBackground, shape)
            .border(1.dp, colors.borders.normal, shape)
            .padding(horizontal = DESCRIPTION_PADDING_X, vertical = DESCRIPTION_PADDING_Y),
    ) {
        Text(descriptionText(item, colors.text.info), maxLines = DESCRIPTION_LINES, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * [FILTER_HINT] after the composer's lone `/`, dimmed: drawn over the text area, which shows its own placeholder only while empty, at
 * the text's own start (the text area's content padding below [top]), after an invisible `/` as wide as the typed one.
 */
@Composable
internal fun FilterHint(top: Dp, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = top).padding(JewelTheme.textAreaStyle.metrics.contentPadding)) {
        Text("/", color = Color.Transparent, modifier = Modifier.clearAndSetSemantics {})
        Text(FILTER_HINT, color = JewelTheme.globalColors.text.info, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Scrolls [state] the least that shows the row [index] whole: a row cut at an edge moves in by what is cut; a row out of view
 * becomes the last in view when it is below, the first when it is above.
 */
private suspend fun scrollIntoView(state: LazyListState, index: Int) {
    val info = state.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val item = visible.firstOrNull { it.index == index }
    when {
        item == null && index > visible.last().index -> state.scrollToItem(max(0, index - visible.size + 1))
        item == null -> state.scrollToItem(index)
        item.offset < info.viewportStartOffset -> state.scrollBy((item.offset - info.viewportStartOffset).toFloat())
        item.offset + item.size > info.viewportEndOffset -> state.scrollBy((item.offset + item.size - info.viewportEndOffset).toFloat())
    }
}
