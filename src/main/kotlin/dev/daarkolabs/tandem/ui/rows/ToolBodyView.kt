@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui.rows

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.ide.BrowserUtil
import dev.daarkolabs.tandem.chat.DiffKind
import dev.daarkolabs.tandem.chat.DiffLine
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.ui.ChatUiState
import dev.daarkolabs.tandem.ui.bottomFade
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.monoTextStyle
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.modifier.thenIf
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/** A body shows this many lines until `Show all` (spec 4.7). */
private const val CLAMP_LINES = 20

/** ... or this much height, whichever comes first. */
private val CLAMP_HEIGHT: Dp = 240.dp

/** A clamped body's bottom fades out over about this many lines (spec 4.7). */
private const val FADE_LINES = 3

/**
 * An opened call's body (spec 4.7), drawn from pre-built data: lines in the editor font in a filled box, a command's `$` line in its
 * box and its output on the page, Markdown from [blocks] (parsed by the presenter; nothing until they arrive), result links, question
 * answers, JSON input and output, an edit's lines, an image's size. Every body is clamped at 20 lines or 240 dp, whichever comes
 * first, faded, with `Show all N lines`; past the 400 lines a body keeps, `… N more lines` says how many were left out. Everything
 * is selectable; nothing scrolls inside the row.
 */
@Composable
internal fun ToolBodyView(call: ToolCall, blocks: ImmutableList<MarkdownBlock>?, ui: ChatUiState) {
    val colors = JewelTheme.globalColors.text
    val mono = monoTextStyle()
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            when (val body = call.body) {
                ToolBody.None -> Unit
                is ToolBody.Mono -> Clamped("all:${call.id}", body.lines.size, body.totalLines, ui) { shown, modifier ->
                    MonoBox(modifier) { MonoLines(body.lines.take(shown), body.firstLineError) }
                }
                is ToolBody.Command -> {
                    MonoBox { Text("$ ${body.command}", style = mono, color = colors.normal) }
                    Clamped("all:${call.id}", body.output.size, body.totalLines, ui) { shown, modifier ->
                        Box(modifier.fillMaxWidth().padding(top = 4.dp)) { MonoLines(body.output.take(shown), body.firstLineError) }
                    }
                }
                is ToolBody.Markdown -> {
                    val count = remember(body) { body.markdown.lines().size + (if (body.title != null) 1 else 0) }
                    Clamped("all:${call.id}", count, count, ui) { _, modifier ->
                        Column(modifier.fillMaxWidth()) {
                            body.title?.let { Text(it, color = colors.info) }
                            blocks?.let { Markdown(it, body.markdown, modifier = Modifier.fillMaxWidth(), selectable = true) }
                        }
                    }
                }
                is ToolBody.Links -> {
                    val count = remember(body) { body.links.size + body.markdown.lines().size }
                    Clamped("all:${call.id}", count, count, ui) { _, modifier ->
                        Column(modifier.fillMaxWidth()) {
                            body.links.forEach { link -> Link(link.title, onClick = { openWebLink(link.url) }) }
                            blocks?.let { Markdown(it, body.markdown, modifier = Modifier.fillMaxWidth(), selectable = true) }
                        }
                    }
                }
                is ToolBody.Pairs -> Clamped("all:${call.id}", body.pairs.size, body.pairs.size, ui) { shown, modifier ->
                    Column(modifier.fillMaxWidth()) {
                        body.pairs.take(shown).forEach { (first, second) -> Text("$first: $second", color = colors.normal) }
                    }
                }
                is ToolBody.Json -> {
                    val input = remember(body) { body.input.split("\n") }
                    val output = remember(body) { body.output?.split("\n") }
                    Text("INPUT", color = colors.info)
                    Clamped("all:${call.id}:input", input.size, input.size, ui) { shown, modifier ->
                        MonoBox(modifier) { MonoLines(input.take(shown), false) }
                    }
                    output?.let { lines ->
                        Text("OUTPUT", color = colors.info, modifier = Modifier.padding(top = 4.dp))
                        Clamped("all:${call.id}:output", lines.size, lines.size, ui) { shown, modifier ->
                            MonoBox(modifier) { MonoLines(lines.take(shown), false) }
                        }
                    }
                }
                is ToolBody.Diff -> {
                    val lines = remember(body) { body.hunks.flatMap { it.lines } }
                    Clamped("all:${call.id}", lines.size, lines.size, ui) { shown, modifier ->
                        MonoBox(modifier, padded = false) { DiffLines(lines.take(shown), body.truncated) }
                    }
                }
                is ToolBody.Image -> Text(imageLine(body), style = mono, color = colors.info)
            }
        }
    }
}

/**
 * An edit's [lines], each prefixed `-`, `+` or a space on its diff fill. A [truncated] diff ends with a bare `…`: spec 5.3's
 * `ToolBody.Diff` carries no count of the lines left out, so it cannot say `… N more lines` (spec 4.20's form).
 */
@Composable
private fun DiffLines(lines: List<DiffLine>, truncated: Boolean) {
    val colors = JewelTheme.globalColors.text
    val mono = monoTextStyle()
    val palette = chatPalette()
    Column(Modifier.fillMaxWidth()) {
        lines.forEach { line ->
            val (prefix, fill) = when (line.kind) {
                DiffKind.REMOVED -> "-" to palette.deletedFill
                DiffKind.ADDED -> "+" to palette.insertedFill
                DiffKind.CONTEXT -> " " to Color.Transparent
            }
            Text(
                "$prefix${line.text}",
                style = mono,
                color = colors.normal,
                modifier = Modifier.fillMaxWidth().background(fill).padding(horizontal = 8.dp),
            )
        }
        if (truncated) Text("…", style = mono, color = colors.info, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/** `[image 8×8 png]`: an image result's size and type; its data never reaches a row. */
internal fun imageLine(image: ToolBody.Image): String {
    val type = image.mediaType.substringAfter('/')
    val size = if (image.width != null && image.height != null) "${image.width}×${image.height} " else ""
    return "[image $size$type]"
}

/**
 * A body of [lineCount] lines under the one clamp rule (spec 4.7): more than 20 lines, or taller than 240 dp, is clamped. [content]
 * then draws the first `shown` lines (20 at most) at most 240 dp tall, clipped, with its bottom faded out, and a `Show all N lines`
 * link opens it in place ([key] remembers it). [total] beyond the lines kept ends the opened body with `… N more lines`.
 */
@Composable
private fun Clamped(
    key: String,
    lineCount: Int,
    total: Int,
    ui: ChatUiState,
    content: @Composable (shown: Int, modifier: Modifier) -> Unit,
) {
    val showAllState = ui.expanded(key)
    val showAll by showAllState
    if (showAll) {
        content(lineCount, Modifier)
        if (total > lineCount) Text("… ${total - lineCount} more lines", color = JewelTheme.globalColors.text.info)
        return
    }
    // Whether the shown lines are taller than 240 dp, as measured; a 20-line body of wrapped lines can be.
    var tall by remember { mutableStateOf(false) }
    val tooManyLines = lineCount > CLAMP_LINES
    val clamped = tooManyLines || tall
    val fade = fadeHeight()
    val modifier = Modifier.clipToBounds().thenIf(clamped) { bottomFade(fade) }.capHeight(CLAMP_HEIGHT) { if (tall != it) tall = it }
    content(if (tooManyLines) CLAMP_LINES else lineCount, modifier)
    if (clamped) {
        Link("Show all ${maxOf(total, lineCount)} lines", onClick = { showAllState.value = true })
    } else if (total > lineCount) {
        Text("… ${total - lineCount} more lines", color = JewelTheme.globalColors.text.info)
    }
}

/**
 * Lays the content out at its full height, but at most [max] tall; [onTall] hears, on every measure, whether the content is taller
 * than [max]. Put it after `clipToBounds()` and the fade, so that both apply to the capped box.
 */
private fun Modifier.capHeight(max: Dp, onTall: (Boolean) -> Unit): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxHeight = Constraints.Infinity))
    val cap = max.roundToPx()
    onTall(placeable.height > cap)
    val height = minOf(placeable.height, cap).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(placeable.width, height) { placeable.placeRelative(0, 0) }
}

/** About [FADE_LINES] lines of body text in the editor font. */
@Composable
private fun fadeHeight(): Dp {
    val style = monoTextStyle()
    val size = style.fontSize.takeIf { it.isSp } ?: 12.sp
    val line = when {
        style.lineHeight.isSp -> style.lineHeight
        style.lineHeight.isEm -> size * style.lineHeight.value
        else -> size * 1.4f
    }
    return with(LocalDensity.current) { line.toDp() } * FADE_LINES
}

/** A box in the command and code fill, with a hairline border (spec 4.2). */
@Composable
private fun MonoBox(modifier: Modifier = Modifier, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier.fillMaxWidth().background(chatPalette().boxFill, shape).border(1.dp, JewelTheme.globalColors.borders.normal, shape)
            .padding(if (padded) 8.dp else 0.dp, if (padded) 6.dp else 4.dp),
        content = content,
    )
}

/** [lines] as one text in the editor font; with [firstLineError] the first line is in the error colour. */
@Composable
private fun MonoLines(lines: List<String>, firstLineError: Boolean) {
    val colors = JewelTheme.globalColors.text
    val text = buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            if (index == 0 && firstLineError) withStyle(SpanStyle(color = colors.error)) { append(line) } else append(line)
        }
    }
    Text(text, style = monoTextStyle(), color = colors.normal)
}

/** Opens a web link in the browser; anything but `http(s)` is ignored. */
private fun openWebLink(url: String) {
    if (url.startsWith("https://") || url.startsWith("http://")) BrowserUtil.browse(url)
}
