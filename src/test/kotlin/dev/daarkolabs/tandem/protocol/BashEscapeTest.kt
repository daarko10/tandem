package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The tags of a shell escape (spec 12.4, bash probes 1). */
class BashEscapeTest {
    /** The `message.content` of line [line] (1-based) of the transcript fixture [name]. */
    private fun content(name: String, line: Int): String =
        Json.parseToJsonElement(Fixtures.transcript(name).readLines()[line - 1]).jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content

    @Test
    fun `parse reads the merged line`() {
        assertEquals(
            BashLine("echo bash-mode-probe && printf err >&2", "bash-mode-probe\nerr", ""),
            BashEscape.parse(content("bash-mode", 5)),
        )
    }

    @Test
    fun `parse reads an input-only and an output-only line`() {
        assertEquals(BashLine("echo bash-mode-probe && printf err >&2", null, null), BashEscape.parse(content("bash-mode-respond", 3)))
        assertEquals(BashLine(null, "bash-mode-probe\nerr", ""), BashEscape.parse(content("bash-mode-respond", 6)))
    }

    @Test
    fun `an output that contains the closing tag is cut at the last one`() {
        val closing = BashEscape.parse("<bash-stdout>a</bash-stdout>b</bash-stdout><bash-stderr></bash-stderr>")
        assertEquals("a</bash-stdout>b", closing?.stdout)
        assertEquals("", closing?.stderr)
        val nested = BashEscape.parse("<bash-stdout>a<bash-stderr>x</bash-stderr>b</bash-stdout><bash-stderr></bash-stderr>")
        assertEquals(BashLine(null, "a<bash-stderr>x</bash-stderr>b", ""), nested)
    }

    @Test
    fun `a line without the tags is null`() {
        assertNull(BashEscape.parse("hello"))
        assertNull(BashEscape.parse(""))
        assertNull(BashEscape.parse("<local-command-stdout>x</local-command-stdout>"))
    }

    @Test
    fun `a leading space in the command is kept`() {
        assertEquals(" echo", BashEscape.parse("<bash-input> echo</bash-input>")?.command)
    }

    @Test
    fun `input, output and context wrap as recorded`() {
        assertEquals("<bash-input>c</bash-input>", BashEscape.input("c"))
        assertEquals("<bash-stdout>o</bash-stdout><bash-stderr>e</bash-stderr>", BashEscape.output("o", "e"))
        assertEquals("<bash-input>c</bash-input>\n<bash-stdout>o</bash-stdout><bash-stderr></bash-stderr>", BashEscape.context("c", "o", ""))
        assertEquals(content("bash-mode", 5), BashEscape.context("echo bash-mode-probe && printf err >&2", "bash-mode-probe\nerr", ""))
    }

    @Test
    fun `a non-empty stderr is shown after stdout`() {
        assertEquals("a\nb", BashLine(null, "a", "b").shown())
        assertEquals("a\nb", BashLine(null, "a\n", "b").shown())
        assertEquals("a", BashLine(null, "a", "").shown())
        assertEquals("a", BashLine(null, "a", null).shown())
        assertEquals("b", BashLine(null, null, "b").shown())
    }
}
