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
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.Attachments
import dev.daarkolabs.tandem.chat.ImageRef
import dev.daarkolabs.tandem.chat.UserRow
import dev.daarkolabs.tandem.chat.mentionsIn
import dev.daarkolabs.tandem.session.ContextChip
import dev.daarkolabs.tandem.ui.RenderedRow
import dev.daarkolabs.tandem.ui.bottomFade
import dev.daarkolabs.tandem.ui.chatColumn
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.monoTextStyle
import dev.daarkolabs.tandem.ui.smallTextStyle
import dev.daarkolabs.tandem.ui.WithTooltip
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

/**
 * The project's files, relative to its base directory, as last read ([dev.daarkolabs.tandem.ide.context.ProjectFiles.known]): a
 * bubble draws an `@path` naming one of them as a chip (spec 3.5). Empty where none is provided.
 */
internal val LocalKnownPaths: ProvidableCompositionLocal<Set<String>> = compositionLocalOf { emptySet() }

/** A piece of a bubble's text: plain, or an `@path` of an existing file ([mention]), drawn as an inline chip. */
internal data class BubbleSegment(val text: String, val mention: Boolean)

/**
 * [text] cut into its plain pieces and its `@path` mentions of the [known] files (spec 3.5, edge 17): a mention of a path the project
 * does not have, or that is not a mention (`a@b`), stays text. A line suffix (`@hello.txt#L2`) names its file.
 */
internal fun bubbleSegments(text: String, known: Set<String>): List<BubbleSegment> {
    val segments = ArrayList<BubbleSegment>()
    var at = 0
    for (span in mentionsIn(text)) {
        if (span.path.substringBefore('#') !in known) continue
        if (span.start > at) segments += BubbleSegment(text.substring(at, span.start), mention = false)
        segments += BubbleSegment(text.substring(span.start, span.end), mention = true)
        at = span.end
    }
    if (at < text.length || segments.isEmpty()) segments += BubbleSegment(text.substring(at), mention = false)
    return segments
}

/** Whether a prompt's bubble draws its text box: an images-only prompt shows its thumbnails alone (polish spec 2.7, edge 8). */
internal fun showsTextBox(row: UserRow): Boolean = row.text.isNotBlank()

/**
 * What the user sent (spec 4.3): a filled bubble flush with the column's right edge, as wide as its text up to 85 % of the column.
 * The text is plain, never Markdown, with its line breaks; a slash command or `!` line is in the editor font. One text node. A
 * prompt over 8 laid-out lines ([bubbleClamp]) is clamped at 160 dp, its text fading out at the bottom, with `Show more` under the
 * bubble; opened, `Show less`. The images sent with it show above the bubble ([ImageStrip]), from [rendered]'s decoded thumbnails
 * (polish spec 2.7); a prompt of images only has no bubble. A [UserRow.caption] goes under it in the footer size and the dim tier.
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
                if (row.chips.isNotEmpty()) {
                    ContextChips(row.chips)
                    Spacer(Modifier.height(6.dp))
                }
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
                                    bubbleText(row.text, LocalKnownPaths.current),
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
                // `Sent while Claude worked` under a prompt the CLI took into the running turn (desktop-parity spec 12.1).
                row.caption?.let { caption ->
                    Text(caption, style = smallTextStyle(), color = chatPalette().dim, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** [text] with each `@path` of a [known] file drawn as an inline chip: the accent colour on the box fill ([bubbleSegments]). */
@Composable
private fun bubbleText(text: String, known: Set<String>): AnnotatedString {
    val palette = chatPalette()
    return remember(text, known, palette) {
        buildAnnotatedString {
            for (segment in bubbleSegments(text, known)) {
                if (segment.mention) {
                    withStyle(SpanStyle(color = palette.accent, background = palette.boxFill)) { append(segment.text) }
                } else {
                    append(segment.text)
                }
            }
        }
    }
}

/** What a context chip shows (spec 3.5): its [label] (`<name> (unsaved)`, `<name>:<start>-<end>`) and its file's path as [tooltip]. */
internal data class ChipLook(val label: String, val tooltip: String)

/** The look of [chip], in the bubble as in the composer. */
internal fun chipLook(chip: ContextChip): ChipLook = ChipLook(chip.label, chip.path)

/**
 * The context blocks a prompt carried (spec 3.5), above its bubble and right-aligned: one small bordered chip each, reading its label
 * (`<name> (unsaved)`, `<name>:<start>-<end>`), described as that label, with the file's path as its tooltip ([chipLook]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextChips(chips: ImmutableList<ContextChip>) {
    val shape = RoundedCornerShape(6.dp)
    val border = JewelTheme.globalColors.borders.normal
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { chip ->
            val look = chipLook(chip)
            WithTooltip(look.tooltip) {
                Box(
                    Modifier.border(1.dp, border, shape)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .semantics { contentDescription = look.label },
                ) {
                    Text(look.label, style = smallTextStyle(), color = chatPalette().secondary, maxLines = 1)
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
