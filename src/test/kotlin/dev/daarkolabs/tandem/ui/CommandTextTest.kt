package dev.daarkolabs.tandem.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import dev.daarkolabs.tandem.chat.ShellTokens
import dev.daarkolabs.tandem.ui.rows.commandText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The coloured command line of a Bash body (polish spec 2.4): pure, no composition. */
class CommandTextTest {
    private val colors = ShellColors(
        prompt = Color.Red,
        command = Color.Blue,
        argument = Color.Green,
        operator = Color.White,
        comment = Color.Gray,
        plain = Color.White,
    )

    private fun spans(text: AnnotatedString): List<Pair<String, Color>> =
        text.spanStyles.map { text.text.substring(it.start, it.end) to it.item.color }

    @Test
    fun `the command starts with a secondary $ and colours each token kind`() {
        val command = "grep -r x | head"
        val text = commandText(command, ShellTokens.tokenize(command), colors, null)
        assertEquals("$ grep -r x | head", text.text)
        assertEquals(0, text.spanStyles.first().start)
        assertEquals(2, text.spanStyles.first().end)
        assertEquals(
            listOf(
                "$ " to Color.Red, "grep" to Color.Blue, " " to Color.White, "-r" to Color.Green, " " to Color.White,
                "x" to Color.Green, " " to Color.White, "|" to Color.White, " " to Color.White, "head" to Color.Blue,
            ),
            spans(text),
        )
        val comment = "ls # all"
        assertEquals("# all" to Color.Gray, spans(commandText(comment, ShellTokens.tokenize(comment), colors, null)).last())
    }

    @Test
    fun `a clamped command keeps the colours of its first 20 lines`() {
        val command = (1..25).joinToString("\n") { "echo $it" }
        val text = commandText(command, ShellTokens.tokenize(command), colors, 20)
        assertEquals(20, text.text.lines().size)
        assertTrue(text.text.endsWith("echo 20"), text.text)
        assertEquals("20" to Color.Green, spans(text).last())
        assertTrue(spans(text).none { it.first == "21" || it.first == "25" })
        assertEquals(text.text.length, text.spanStyles.last().end)
        // A heredoc body cut in the middle keeps its colour up to the cut.
        val heredoc = "cat <<EOF\n" + (1..30).joinToString("\n") { "line $it" } + "\nEOF"
        val cut = commandText(heredoc, ShellTokens.tokenize(heredoc), colors, 20)
        assertTrue(cut.text.endsWith("line 19"), cut.text)
        assertEquals(Color.White, cut.spanStyles.last().item.color)
        assertEquals(cut.text.length, cut.spanStyles.last().end)
    }

    @Test
    fun `text the tokens do not cover is still shown`() {
        val text = commandText("ls -la", emptyList(), colors, null)
        assertEquals("$ ls -la", text.text)
        assertEquals("ls -la" to Color.White, spans(text).last())
    }
}
