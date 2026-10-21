@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The chat's Markdown parser and where a link in it leads (spec 4.4). Plain JUnit 5: [MarkdownProcessor] needs no LaF. */
class TandemMarkdownTest {
    @Test
    fun `tables and strikethrough parse with the extensions`() {
        val table = "| a | b |\n|---|---|\n| 1 | 2 |"
        // A table block.
        assertFalse(tandemMarkdownProcessor().processMarkdownDocument(table).single() is MarkdownBlock.Paragraph)
        // Without the extension: plain paragraphs.
        assertTrue(MarkdownProcessor().processMarkdownDocument(table).all { it is MarkdownBlock.Paragraph })
        val struck = tandemMarkdownProcessor().processMarkdownDocument("~~x~~").single() as MarkdownBlock.Paragraph
        // The strikethrough node.
        assertTrue(struck.inlineContent.any { it !is InlineMarkdown.Text })
        assertTrue(
            (MarkdownProcessor().processMarkdownDocument("~~x~~").single() as MarkdownBlock.Paragraph).inlineContent
                .all { it is InlineMarkdown.Text },
        )
    }

    // Review Focus 3 and 5.
    @Test
    fun `links open only http and project paths`() {
        val base = Path.of("/home/u/proj")
        assertEquals(LinkTarget.Browse("https://e.com/a?b=1"), linkTarget("https://e.com/a?b=1", base))
        assertEquals(LinkTarget.Browse("http://e.com"), linkTarget("http://e.com", null))
        assertEquals(LinkTarget.Open(base.resolve("src/A.kt")), linkTarget("src/A.kt", base))
        assertEquals(LinkTarget.Open(base.resolve("src/A.kt")), linkTarget("./src/A.kt", base))
        assertEquals(LinkTarget.None, linkTarget("../x", base))
        assertEquals(LinkTarget.None, linkTarget("/etc/passwd", base))
        assertEquals(LinkTarget.None, linkTarget("src/A.kt", null))
        assertEquals(LinkTarget.None, linkTarget("mailto:a@b.c", base))
        assertEquals(LinkTarget.None, linkTarget("", base))
        assertEquals(LinkTarget.Open(base.resolve("src/A.kt")), linkTarget("src/A.kt#L42", base))
        assertEquals(LinkTarget.Open(base.resolve("A.kt")), linkTarget("A.kt:42", base))
        assertEquals(LinkTarget.Browse("http://e.com:8080/x"), linkTarget("http://e.com:8080/x", base))
        // The InvalidPathException branch (Markdown never passes a NUL: CommonMark maps it to U+FFFD), no exception.
        assertEquals(LinkTarget.None, linkTarget("a\u0000b", base))
    }
}
