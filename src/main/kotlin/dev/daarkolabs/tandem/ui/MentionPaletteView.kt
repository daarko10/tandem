package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.daarkolabs.tandem.chat.MentionPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.component.scrollbarContentSafePadding
import kotlin.math.max
import kotlin.math.min

/** The `@` palette shows at most this many rows before it scrolls, as the slash palette does. */
private const val VISIBLE_ROWS: Int = 10

/** The `@` palette is as wide as the composer box, up to this. */
private val MAX_WIDTH: Dp = 480.dp

/** Between the popup and the composer box, and inside the list's frame above its first row and under its last. */
private val ANCHOR_GAP: Dp = 8.dp
private val FRAME_PADDING: Dp = 4.dp

/** The accessible name of the `@` palette. */
internal const val MENTION_PALETTE_DESCRIPTION: String = "Files"

/** A row's two parts: the file's name, then its folder with its trailing `/` (dimmed in the row); no folder at the base. */
internal fun mentionRowParts(path: String): Pair<String, String> {
    val cut = path.lastIndexOf('/')
    return if (cut < 0) path to "" else path.substring(cut + 1) to path.substring(0, cut + 1)
}

/** The height of the `@` palette's list in pixels: its rows, at most [VISIBLE_ROWS], never more than [maxPx]. */
internal fun mentionListHeight(rows: Int, rowPx: Int, maxPx: Int): Int = min(min(rows, VISIBLE_ROWS) * rowPx, max(0, maxPx))

/**
 * The `@` file palette (spec 3.5), in the slash palette's compact form ([SlashPaletteView]): a popup above the composer box it is
 * placed in, at its left edge, 8 dp above it, as wide as the box up to 480 dp ([boxWidthPx]) and at most [maxHeightPx] tall. One row
 * per file, with the slash palette's row ([PaletteRowShell]): its name, then its folder dimmed, the selected row highlighted and
 * scrolled into view; 10 rows show, the rest scroll. It never takes the focus and no click outside dismisses it, so every key still
 * reaches the text area; a click on a row calls [onPick] with its index. Test tags `mention-palette`, `mention-row-<index>`.
 */
@Composable
internal fun MentionPaletteView(palette: MentionPalette, boxWidthPx: Int, maxHeightPx: Int, onPick: (Int) -> Unit) {
    val density = LocalDensity.current
    val anchorGap = with(density) { ANCHOR_GAP.roundToPx() }
    val position = remember(anchorGap) { AboveAnchor(alignEnd = false, gapPx = anchorGap) }
    val currentOnPick by rememberUpdatedState(onPick)
    Popup(popupPositionProvider = position, properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
        val rowHeight = maxOf(24.dp, lineHeight(JewelTheme.defaultTextStyle) + 6.dp)
        val rowPx = with(density) { rowHeight.roundToPx() }
        val listPx = mentionListHeight(palette.rows.size, rowPx, maxHeightPx - 2 * with(density) { FRAME_PADDING.roundToPx() })
        val scrollbarPadding = if (palette.rows.size * rowPx > listPx) scrollbarContentSafePadding() else 0.dp
        val width = with(density) { min(max(0, boxWidthPx), MAX_WIDTH.roundToPx()).toDp() }
        val listState = rememberLazyListState()
        LaunchedEffect(palette.query, palette.selected) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val shown = visible.any { it.index == palette.selected && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
            if (visible.isNotEmpty() && !shown) {
                listState.scrollToItem(if (palette.selected > visible.last().index) max(0, palette.selected - visible.size + 1) else palette.selected)
            }
        }
        val colors = JewelTheme.globalColors
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.width(width)
                .testTag("mention-palette")
                .semantics { contentDescription = MENTION_PALETTE_DESCRIPTION }
                .clipToBounds()
                .background(colors.panelBackground, shape)
                .border(1.dp, colors.borders.normal, shape)
                .padding(vertical = FRAME_PADDING),
        ) {
            VerticallyScrollableContainer(listState, Modifier.height(with(density) { listPx.toDp() }).clipToBounds()) {
                LazyColumn(state = listState) {
                    itemsIndexed(palette.rows, key = { _, path -> path }) { index, path ->
                        val (name, folder) = mentionRowParts(path)
                        PaletteRowShell(
                            path,
                            selected = index == palette.selected,
                            height = rowHeight,
                            scrollbarPadding = scrollbarPadding,
                            modifier = Modifier.testTag("mention-row-$index"),
                            onClick = { currentOnPick(index) },
                        ) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (folder.isNotEmpty()) {
                                Text(
                                    " $folder",
                                    color = colors.text.info,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
