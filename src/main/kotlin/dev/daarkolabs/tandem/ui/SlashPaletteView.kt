package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.daarkolabs.tandem.chat.CommandScope
import dev.daarkolabs.tandem.chat.PaletteItem
import dev.daarkolabs.tandem.chat.SlashPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import kotlin.math.max
import kotlin.math.min

/** The palette shows at most this many rows before it scrolls (MCP spec 3.4). */
private const val MAX_ROWS: Int = 8

/**
 * The slash-command palette (MCP spec 3.4, 3.7): a popup above the composer box it is placed in, left-aligned on it and as wide as it
 * ([widthPx]: a position provider cannot size a popup, so the box measures itself), its bottom 8 dp above the box's top. It never
 * takes the focus and no click outside dismisses it, so every key still reaches the text area and a press outside goes to what it
 * lands on; it has no dismiss rule of its own, the composer shows it while the palette is open. Its rows list at most 8 rows and at
 * most [maxHeightPx] with the footer, the space above the box, scrolling past that, never placed over the box; the selected row is
 * scrolled into view. A click on a row calls [onPick] with it.
 *
 * A row: `/<name>` in the editor font, the argument hint after it and ` · via /<alias>` when only an alias matched, both in the info
 * colour, the description under it a size smaller, and the scope (`project`, `user`, `plugin`; nothing for a built-in) as a plain
 * pill at its end. The palette's footer (`No matching command`, `Commands load when the session starts`) is its last line.
 */
@Composable
internal fun SlashPaletteView(palette: SlashPalette, widthPx: Int, maxHeightPx: Int, onPick: (PaletteItem) -> Unit) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val position = remember(gap) { AboveAnchor(alignEnd = false, gapPx = gap) }
    val currentOnPick by rememberUpdatedState(onPick)
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        val colors = JewelTheme.globalColors
        val shape = RoundedCornerShape(8.dp)
        // The tallest row seen: 8 of them cap the list, so a list of one-line rows shows a few more before it scrolls.
        var rowHeightPx by remember { mutableIntStateOf(0) }
        val listMaxPx = if (rowHeightPx > 0) min(MAX_ROWS * rowHeightPx, maxHeightPx) else maxHeightPx
        Column(
            Modifier.width(with(density) { widthPx.toDp() })
                .heightIn(max = with(density) { max(0, maxHeightPx).toDp() })
                .testTag("slash-palette")
                .semantics { contentDescription = "Slash commands" }
                .background(colors.panelBackground, shape)
                .border(1.dp, colors.borders.normal, shape)
                .padding(vertical = 4.dp),
        ) {
            if (palette.items.isNotEmpty()) {
                val listState = rememberLazyListState()
                LaunchedEffect(palette.query, palette.selected) { scrollIntoView(listState, palette.selected) }
                VerticallyScrollableContainer(
                    listState,
                    Modifier.weight(1f, fill = false).heightIn(max = with(density) { max(0, listMaxPx).toDp() }),
                ) {
                    LazyColumn(state = listState) {
                        itemsIndexed(palette.items, key = { _, item -> item.name }) { index, item ->
                            PaletteRow(
                                item,
                                selected = index == palette.selected,
                                modifier = Modifier.onSizeChanged { if (it.height > rowHeightPx) rowHeightPx = it.height },
                            ) { currentOnPick(item) }
                        }
                    }
                }
            }
            palette.footer?.let { footer ->
                Text(footer, style = smallTextStyle(), color = colors.text.info, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

/** One command of the palette; at least 24 dp tall, so the IDE tests' click at (10, 10) lands on it (MCP spec 3.7). */
@Composable
private fun PaletteRow(item: PaletteItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val info = JewelTheme.globalColors.text.info
    Row(
        modifier.fillMaxWidth()
            .heightIn(min = 24.dp)
            .background(if (selected) chatPalette().bubbleFill else Color.Transparent)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "/${item.name}"
                this.selected = selected
            }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("/${item.name}", style = monoTextStyle(), maxLines = 1)
                val trailing = buildString {
                    if (item.argumentHint.isNotEmpty()) append(' ').append(item.argumentHint)
                    item.via?.let { append(" · via /").append(it) }
                }
                if (trailing.isNotEmpty()) Text(trailing, color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.description.isNotEmpty()) {
                Text(item.description, style = smallTextStyle(), color = info, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        scopeLabel(item.scope)?.let { TextPill(it) }
    }
}

/** The scope pill's text (MCP spec 3.4); none for a built-in. */
internal fun scopeLabel(scope: CommandScope): String? = when (scope) {
    CommandScope.BUILTIN -> null
    CommandScope.PROJECT -> "project"
    CommandScope.USER -> "user"
    CommandScope.PLUGIN -> "plugin"
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
