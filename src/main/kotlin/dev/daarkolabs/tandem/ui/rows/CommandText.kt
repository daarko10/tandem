package dev.daarkolabs.tandem.ui.rows

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import dev.daarkolabs.tandem.chat.ShellToken
import dev.daarkolabs.tandem.chat.ShellTokenKind
import dev.daarkolabs.tandem.ui.ShellColors

/**
 * A Bash body's command line (polish spec 2.4): `$ ` in [ShellColors.prompt], then each of [tokens] (computed over the full
 * [command] where the body was built) in its kind's colour. With [maxLines] the text ends with the last character of that line (no
 * trailing newline), and the tokens it cuts keep their colours, so a clamped heredoc looks as it does in full. Text the tokens do
 * not cover is still shown, in [ShellColors.plain]. Pure.
 */
internal fun commandText(command: String, tokens: List<ShellToken>, colors: ShellColors, maxLines: Int?): AnnotatedString {
    val end = if (maxLines == null) command.length else lineCut(command, maxLines)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = colors.prompt)) { append("$ ") }
        var at = 0
        for (token in tokens) {
            if (token.range.first != at || at >= end) break
            val stop = minOf(token.range.last + 1, end)
            withStyle(SpanStyle(color = colors.of(token.kind))) { append(command, at, stop) }
            at = stop
        }
        if (at < end) withStyle(SpanStyle(color = colors.plain)) { append(command, at, end) }
    }
}

/** Where [command]'s first [lines] lines end: the index of the newline after the last of them, or its length. */
private fun lineCut(command: String, lines: Int): Int {
    if (lines < 1) return 0
    var index = -1
    repeat(lines) {
        index = command.indexOf('\n', index + 1)
        if (index < 0) return command.length
    }
    return index
}

private fun ShellColors.of(kind: ShellTokenKind): Color = when (kind) {
    ShellTokenKind.COMMAND -> command
    ShellTokenKind.ARGUMENT -> argument
    ShellTokenKind.OPERATOR -> operator
    ShellTokenKind.COMMENT -> comment
    ShellTokenKind.PLAIN -> plain
}

/** The line above a lone row's command box (polish spec 2.4): `PowerShell` for that tool, `Bash` for every other shell call. */
internal fun commandHeader(toolName: String): String = if (toolName == "PowerShell") "PowerShell" else "Bash"
