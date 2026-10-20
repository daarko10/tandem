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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.ide.BrowserUtil
import dev.daarkolabs.tandem.chat.DiffKind
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.ui.ChatUiState
import dev.daarkolabs.tandem.ui.bottomFade
import dev.daarkolabs.tandem.ui.chatPalette
import dev.daarkolabs.tandem.ui.monoTextStyle
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.markdown.Markdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text

/** A body shows this many lines until `Show all` (spec 4.7). */
private const val CLAMP_LINES = 20

/** ... or this much height, whichever comes first; its bottom fades out. */
private val CLAMP_HEIGHT: Dp = 240.dp

/**
 * An opened call's body (spec 4.7), drawn from pre-built data: lines in the editor font in a filled box, a command's `$` line in its
 * box and its output on the page, Markdown from [blocks] (parsed by the presenter; nothing until they arrive), result links, question
 * answers, JSON input and output, an edit's lines, an image's size. Long bodies show 20 lines, faded, with `Show all N lines`; past
 * the 400 lines a body keeps, `… N more lines` says how many were left out. Everything is selectable; nothing scrolls inside the row.
 */
@Composable
internal fun ToolBodyView(call: ToolCall, blocks: ImmutableList<MarkdownBlock>?, ui: ChatUiState) {
    val colors = JewelTheme.globalColors.text
    val mono = monoTextStyle()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        when (val body = call.body) {
            ToolBody.None -> Unit
            is ToolBody.Mono -> Clamped("all:${call.id}", body.lines, body.totalLines, ui) { lines, modifier ->
                MonoBox(modifier) { MonoLines(lines, body.firstLineError) }
            }
            is ToolBody.Command -> {
                MonoBox { Text("$ ${body.command}", style = mono, color = colors.normal) }
                Clamped("all:${call.id}", body.output, body.totalLines, ui) { lines, modifier ->
                    Box(modifier.fillMaxWidth().padding(top = 4.dp)) { MonoLines(lines, body.firstLineError) }
                }
            }
            is ToolBody.Markdown -> {
                body.title?.let { Text(it, color = colors.info) }
                blocks?.let { Markdown(it, body.markdown, modifier = Modifier.fillMaxWidth(), selectable = true) }
            }
            is ToolBody.Links -> {
                body.links.forEach { link -> Link(link.title, onClick = { openWebLink(link.url) }) }
                blocks?.let { Markdown(it, body.markdown, modifier = Modifier.fillMaxWidth(), selectable = true) }
            }
            is ToolBody.Pairs -> SelectionContainer {
                Column { body.pairs.forEach { (first, second) -> Text("$first: $second", color = colors.normal) } }
            }
            is ToolBody.Json -> {
                Text("INPUT", color = colors.info)
                val input = body.input.split("\n")
                Clamped("all:${call.id}:input", input, input.size, ui) { lines, modifier -> MonoBox(modifier) { MonoLines(lines, false) } }
                body.output?.let { output ->
                    Text("OUTPUT", color = colors.info, modifier = Modifier.padding(top = 4.dp))
                    val lines = output.split("\n")
                    Clamped("all:${call.id}:output", lines, lines.size, ui) { shown, modifier -> MonoBox(modifier) { MonoLines(shown, false) } }
                }
            }
            is ToolBody.Diff -> {
                val lines = body.hunks.flatMap { it.lines }
                Clamped("all:${call.id}", lines, lines.size, ui) { shown, modifier ->
                    val palette = chatPalette()
                    MonoBox(modifier, padded = false) {
                        SelectionContainer {
                            Column(Modifier.fillMaxWidth()) {
                                shown.forEach { line ->
                                    val (prefix, fill) = when (line.kind) {
                                        DiffKind.REMOVED -> "-" to palette.deletedFill
                                        DiffKind.ADDED -> "+" to palette.insertedFill
                                        DiffKind.CONTEXT -> " " to Color.Transparent
                                    }
                                    Text(
                                        "$prefix ${line.text}",
                                        style = mono,
                                        color = colors.normal,
                                        modifier = Modifier.fillMaxWidth().background(fill).padding(horizontal = 8.dp),
                                    )
                                }
                                if (body.truncated) Text("…", style = mono, color = colors.info, modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                    }
                }
            }
            is ToolBody.Image -> Text(imageLine(body), style = mono, color = colors.info)
        }
    }
}

/** `[image 8×8 png]`: an image result's size and type; its data never reaches a row. */
internal fun imageLine(image: ToolBody.Image): String {
    val type = image.mediaType.substringAfter('/')
    val size = if (image.width != null && image.height != null) "${image.width}×${image.height} " else ""
    return "[image $size$type]"
}

/**
 * [lines] under the one clamp rule (spec 4.7): more than 20 lines show 20, clamped at 240 dp with a fading bottom, and a
 * `Show all N lines` link that opens them in place ([key] remembers it); [total] beyond the lines kept ends with `… N more lines`.
 */
@Composable
private fun <T> Clamped(
    key: String,
    lines: List<T>,
    total: Int,
    ui: ChatUiState,
    content: @Composable (lines: List<T>, modifier: Modifier) -> Unit,
) {
    val showAllState = ui.expanded(key)
    val showAll by showAllState
    if (!showAll && lines.size > CLAMP_LINES) {
        content(lines.take(CLAMP_LINES), Modifier.bottomFade(CLAMP_HEIGHT))
        Link("Show all ${maxOf(total, lines.size)} lines", onClick = { showAllState.value = true })
    } else {
        content(lines, Modifier)
        if (total > lines.size) Text("… ${total - lines.size} more lines", color = JewelTheme.globalColors.text.info)
    }
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

/** [lines] as one selectable text in the editor font; with [firstLineError] the first line is in the error colour. */
@Composable
private fun MonoLines(lines: List<String>, firstLineError: Boolean) {
    val colors = JewelTheme.globalColors.text
    val text = buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            if (index == 0 && firstLineError) withStyle(SpanStyle(color = colors.error)) { append(line) } else append(line)
        }
    }
    SelectionContainer { Text(text, style = monoTextStyle(), color = colors.normal) }
}

/** Opens a web link in the browser; anything but `http(s)` is ignored. */
private fun openWebLink(url: String) {
    if (url.startsWith("https://") || url.startsWith("http://")) BrowserUtil.browse(url)
}
