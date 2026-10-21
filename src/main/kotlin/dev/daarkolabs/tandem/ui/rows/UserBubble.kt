package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.Attachments
import dev.daarkolabs.tandem.chat.ImageRef
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.ui.RenderedRow
import dev.daarkolabs.tandem.ui.bottomFade
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.monoTextStyle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
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

/** Whether a prompt's bubble draws its text box: an images-only prompt shows its thumbnails alone (polish spec 2.7, edge 8). */
internal fun showsTextBox(row: UserRow): Boolean = row.text.isNotBlank()

/**
 * What the user sent (spec 4.3): a filled bubble flush with the column's right edge, as wide as its text up to 85 % of the column.
 * The text is plain, never Markdown, with its line breaks; a slash command or `!` line is in the editor font. One text node. A
 * prompt over 8 laid-out lines ([bubbleClamp]) is clamped at 160 dp, its text fading out at the bottom, with `Show more` under the
 * bubble; opened, `Show less`. The images sent with it show above the bubble ([ImageStrip]), from [rendered]'s decoded thumbnails
 * (polish spec 2.7); a prompt of images only has no bubble.
 */
@Composable
internal fun UserBubble(rendered: RenderedRow) {
    val row = rendered.row as UserRow
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
                if (row.images.isNotEmpty()) ImageStrip(row.images, rendered.images)
                if (showsTextBox(row)) {
                    if (row.images.isNotEmpty()) Spacer(Modifier.height(8.dp))
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
}

/** The bubble's widest, as a fraction of the column. */
private const val BUBBLE_WIDTH = 0.85f

/**
 * The images sent with a prompt, above its bubble and right-aligned (polish spec 2.7): each thumbnail at most 240 dp wide and 135 dp
 * tall with its aspect kept, rounded and bordered, described as `[Image #N]`. An image whose thumbnail [bitmaps] does not hold yet
 * (still decoding), or never will (data redacted), shows a 96 x 64 dp box reading `[Image #N]`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageStrip(images: ImmutableList<ImageRef>, bitmaps: ImmutableMap<String, ImageBitmap>) {
    val shape = RoundedCornerShape(6.dp)
    val border = JewelTheme.globalColors.borders.normal
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { ref ->
            val label = Attachments.label(ref.index)
            val bitmap = bitmaps[ref.id]
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = label,
                    modifier = Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 135.dp).clip(shape).border(1.dp, border, shape),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    Modifier.size(96.dp, 64.dp).border(1.dp, border, shape).semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = monoTextStyle(), color = chatPalette().secondary)
                }
            }
        }
    }
}
