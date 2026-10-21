package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.ui.bottomFade
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/** A prompt of more laid-out lines than this clamps (spec 4.3). */
private const val CLAMP_LINES = 8

/** A clamped bubble's height (spec 4.3). */
private val CLAMP_HEIGHT: Dp = 160.dp

/** A clamped bubble's text fades out over this band at its bottom. */
private val FADE_HEIGHT: Dp = 60.dp

/** Whether the user's bubble is clamped (spec 4.3): not at all, clamped with `Show more`, or opened with `Show less`. */
internal enum class BubbleClamp { NONE, CLAMPED, OPEN }

/**
 * How a bubble whose text lays out in [lineCount] lines shows (spec 4.3): in full up to 8 lines; past that clamped until `Show
 * more` ([shown]), then open with `Show less`. The count is the laid-out lines, so one long line that wraps clamps too.
 */
internal fun bubbleClamp(lineCount: Int, shown: Boolean): BubbleClamp = when {
    lineCount <= CLAMP_LINES -> BubbleClamp.NONE
    shown -> BubbleClamp.OPEN
    else -> BubbleClamp.CLAMPED
}

/**
 * What the user sent (spec 4.3): a filled bubble flush with the column's right edge, as wide as its text up to 85 % of the column.
 * The text is plain, never Markdown, with its line breaks; a slash command or `!` line is in the editor font. One text node. A
 * prompt over 8 laid-out lines ([bubbleClamp]) is clamped at 160 dp, its text fading out at the bottom, with `Show more` under the
 * bubble; opened, `Show less`.
 */
@Composable
internal fun UserBubble(row: UserRow) {
    val body = JewelTheme.defaultTextStyle
    val style = if (row.mono) JewelTheme.editorTextStyle.copy(fontSize = body.fontSize, lineHeight = body.lineHeight) else body
    val fill = chatPalette().bubbleFill
    var lines by remember(row.key) { mutableIntStateOf(0) }
    var shown by remember(row.key) { mutableStateOf(false) }
    val clamp = bubbleClamp(lines, shown)
    val clamped = clamp == BubbleClamp.CLAMPED
    Box(Modifier.chatColumn().padding(top = row.gap.dp.dp), contentAlignment = Alignment.TopEnd) {
        Box(Modifier.fillMaxWidth(BUBBLE_WIDTH), contentAlignment = Alignment.TopEnd) {
            Column(horizontalAlignment = Alignment.End) {
                // The fill stays whole: the bubble clamps, its text fades.
                Box(
                    Modifier.background(fill, RoundedCornerShape(8.dp))
                        .then(if (clamped) Modifier.heightIn(max = CLAMP_HEIGHT) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    SelectionContainer {
                        Box(if (clamped) Modifier.clipToBounds().bottomFade(FADE_HEIGHT) else Modifier) {
                            Text(
                                row.text,
                                style = style,
                                color = JewelTheme.globalColors.text.normal,
                                // Unbounded, so that the text lays out all its lines whether clamped or not.
                                modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true).testTag("user-bubble"),
                                onTextLayout = { lines = it.lineCount },
                            )
                        }
                    }
                }
                when (clamp) {
                    BubbleClamp.CLAMPED -> Link("Show more", onClick = { shown = true })
                    BubbleClamp.OPEN -> Link("Show less", onClick = { shown = false })
                    BubbleClamp.NONE -> Unit
                }
            }
        }
    }
}

/** The bubble's widest, as a fraction of the column. */
private const val BUBBLE_WIDTH = 0.85f
