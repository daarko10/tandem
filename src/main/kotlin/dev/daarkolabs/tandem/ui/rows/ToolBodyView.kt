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
import dev.daarkolabs.tandem.ui.LocalUrlHandler
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

/** A body shows this many lines until `Show more` (spec 4.7, polish spec 2.2). */
private const val CLAMP_LINES = 20

/** ... or this much height, whichever comes first. */
private val CLAMP_HEIGHT: Dp = 240.dp

/** A clamped body's bottom fades out over about this many lines (spec 4.7). */
private const val FADE_LINES = 3

/** `Show more` opens a body up to this many lines; the rest is counted in `… N more lines` (spec 4.7, 5.3). */
private const val MAX_LINES = 400

/**
 * An opened call's body (spec 4.7), drawn from pre-built data: lines in the editor font in a filled box, a command's `$` line in its
 * box and its output on the page, Markdown from [blocks] (parsed by the presenter; nothing until they arrive), result links, question
 * answers, JSON input and output, an edit's lines, an image's size. Every body, the command's box included, is clamped at 20 lines
 * or 240 dp, whichever comes first, faded, with `Show more`, and `Show less` clamps it again (polish spec 2.2); past the 400 lines a
 * body keeps, `… N more lines` says how many were left out. Markdown cannot be cut by line, so its bodies clamp by height alone.
 * The clamps are the call's (`c:<call id>`, [ChatUiState.clampOpen]) wherever the body renders, so collapsing the row above clamps
 * them again. [framed]: a lone row's body (a top-level row, a row nested in an Agent body), not a group sub-row's. Everything is
 * selectable; nothing scrolls inside the row.
 */
@Composable
internal fun ToolBodyView(call: ToolCall, blocks: ImmutableList<MarkdownBlock>?, ui: ChatUiState, framed: Boolean) {
    val colors = JewelTheme.globalColors.text
    val mono = monoTextStyle()
    val onUrl = LocalUrlHandler.current
    val owner = "c:${call.id}"
    SelectionContainer {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            when (val body = call.body) {
                ToolBody.None -> Unit
                is ToolBody.Mono -> Clamped(owner, "body", body.lines.size, body.totalLines, ui) { shown, modifier ->
                    MonoBox(modifier) { MonoLines(body.lines.take(shown), body.firstLineError) }
                }
                is ToolBody.Command -> {
                    val command = remember(body) { kept("$ ${body.command}") }
                    commandParts(body, framed).forEach { part ->
                        when (part) {
                            CommandPart.HEADER -> Unit
                            CommandPart.COMMAND -> Clamped(owner, "command", command.lines.size, command.total, ui) { shown, modifier ->
                                MonoBox(modifier) { MonoLines(command.lines.take(shown), false) }
                            }
                            CommandPart.OUTPUT -> Clamped(owner, "output", body.output.size, body.totalLines, ui) { shown, modifier ->
                                Box(modifier.fillMaxWidth().padding(top = 4.dp)) { MonoLines(body.output.take(shown), body.firstLineError) }
                            }
                        }
                    }
                }
                is ToolBody.Markdown -> {
                    val count = remember(body) { body.markdown.lines().size + (if (body.title != null) 1 else 0) }
                    Clamped(owner, "body", count, count, ui, byLines = false) { _, modifier ->
                        Column(modifier.fillMaxWidth()) {
                            body.title?.let { Text(it, color = colors.info) }
                            blocks?.let {
                                Markdown(it, body.markdown, Modifier.fillMaxWidth(), selectable = true, onUrlClick = onUrl)
                            }
                        }
                    }
                }
                is ToolBody.Links -> {
                    val count = remember(body) { body.links.size + body.markdown.lines().size }
                    Clamped(owner, "body", count, count, ui, byLines = false) { _, modifier ->
                        Column(modifier.fillMaxWidth()) {
                            body.links.forEach { link -> Link(link.title, onClick = { openWebLink(link.url) }) }
                            blocks?.let {
                                Markdown(it, body.markdown, Modifier.fillMaxWidth(), selectable = true, onUrlClick = onUrl)
                            }
                        }
                    }
                }
                is ToolBody.Pairs -> Clamped(owner, "body", body.pairs.size, body.pairs.size, ui) { shown, modifier ->
                    Column(modifier.fillMaxWidth()) {
                        body.pairs.take(shown).forEach { (first, second) -> Text("$first: $second", color = colors.normal) }
                    }
                }
                is ToolBody.Json -> {
                    val input = remember(body) { kept(body.input) }
                    val output = remember(body) { body.output?.let(::kept) }
                    Text("INPUT", color = colors.info)
                    Clamped(owner, "input", input.lines.size, input.total, ui) { shown, modifier ->
                        MonoBox(modifier) { MonoLines(input.lines.take(shown), false) }
                    }
                    output?.let { out ->
                        Text("OUTPUT", color = colors.info, modifier = Modifier.padding(top = 4.dp))
                        Clamped(owner, "output", out.lines.size, out.total, ui) { shown, modifier ->
                            MonoBox(modifier) { MonoLines(out.lines.take(shown), false) }
                        }
                    }
                }
                is ToolBody.Diff -> {
                    val lines = remember(body) { body.hunks.flatMap { it.lines } }
                    Clamped(owner, "body", lines.size, lines.size, ui) { shown, modifier ->
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

/** The parts of a command body (polish spec 2.4): a header, the `$` command box, the output. */
internal enum class CommandPart { HEADER, COMMAND, OUTPUT }

/**
 * The parts [body] draws, in order: its command box, then its output only when it printed something (a running call, or one that
 * printed nothing, shows the command alone, polish spec 2.1). [framed] is a lone row's body; it draws no header yet.
 */
@Suppress("UNUSED_PARAMETER")
internal fun commandParts(body: ToolBody.Command, framed: Boolean): List<CommandPart> =
    if (body.output.isEmpty()) listOf(CommandPart.COMMAND) else listOf(CommandPart.COMMAND, CommandPart.OUTPUT)

/** A text's first [MAX_LINES] [lines] and how many it has in [total]. */
private class Kept(val lines: List<String>, val total: Int)

private fun kept(text: String): Kept {
    val all = text.split("\n")
    return Kept(all.take(MAX_LINES), all.size)
}

/** `[image 8×8 png]`: an image result's size and type; its data never reaches a row. */
internal fun imageLine(image: ToolBody.Image): String {
    val type = image.mediaType.substringAfter('/')
    val size = if (image.width != null && image.height != null) "${image.width}×${image.height} " else ""
    return "[image $size$type]"
}

/**
 * A body of [lineCount] lines under the one clamp rule (spec 4.7): more than 20 lines, or taller than 240 dp, is clamped. [content]
 * then draws the first `shown` lines (20 at most) at most 240 dp tall, clipped, with its bottom faded out, and a `Show more` link
 * opens it in place; opened, a `Show less` link clamps it again (polish spec 2.2). The state is [part] of the call [owner]'s
 * ([ChatUiState.clampOpen]), which collapsing a row above resets. [total] beyond the lines kept ends the opened body with
 * `… N more lines`. Without [byLines] (a body that cannot be cut by line, such as Markdown, whose content ignores `shown`) only the
 * measured height decides, so a short rendering of many source lines is neither faded nor offered a `Show more` that reveals nothing.
 */
@Composable
private fun Clamped(
    owner: String,
    part: String,
    lineCount: Int,
    total: Int,
    ui: ChatUiState,
    byLines: Boolean = true,
    content: @Composable (shown: Int, modifier: Modifier) -> Unit,
) {
    val openState = ui.clampOpen(owner, part)
    if (openState.value) {
        content(lineCount, Modifier)
        Link("Show less", onClick = { openState.value = false })
        if (total > lineCount) Text("… ${total - lineCount} more lines", color = JewelTheme.globalColors.text.info)
        return
    }
    // Whether the shown lines are taller than 240 dp, as measured; a 20-line body of wrapped lines can be.
    var tall by remember { mutableStateOf(false) }
    val tooManyLines = byLines && lineCount > CLAMP_LINES
    val clamped = tooManyLines || tall
    val fade = fadeHeight()
    val modifier = Modifier.clipToBounds().thenIf(clamped) { bottomFade(fade) }.capHeight(CLAMP_HEIGHT) { if (tall != it) tall = it }
    content(if (tooManyLines) CLAMP_LINES else lineCount, modifier)
    if (clamped) {
        Link("Show more", onClick = { openState.value = true })
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
