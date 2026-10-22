@file:OptIn(ExperimentalJewelApi::class)

package dev.daarkolabs.tandem.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.InlineMarkdown
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.InlinesStyling
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling
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

    @Test
    fun `inline code is the code tint on the box fill in the editor font at body − 1`() {
        assertEquals(
            SpanStyle(color = Color.Red, background = Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            inlineCodeStyle(palette(codeTint = Color.Red, boxFill = Color.Gray), FontFamily.Monospace, 13.sp),
        )
    }

    @Test
    fun `inline code in a heading is the pill one point under the heading's size`() {
        fun inlines(size: Int): InlinesStyling = InlinesStyling(
            textStyle = TextStyle(fontSize = size.sp),
            inlineCode = SpanStyle(color = Color.Blue),
            link = SpanStyle(color = Color.Cyan),
            linkDisabled = SpanStyle(),
            linkFocused = SpanStyle(),
            linkHovered = SpanStyle(),
            linkPressed = SpanStyle(),
            linkVisited = SpanStyle(),
            emphasis = SpanStyle(),
            strongEmphasis = SpanStyle(),
            inlineHtml = SpanStyle(),
        )
        val padding = PaddingValues(top = 3.dp)
        val bridge = MarkdownStyling.Heading(
            MarkdownStyling.Heading.H1(inlines(26), 1.dp, Color.Green, 2.dp, padding),
            MarkdownStyling.Heading.H2(inlines(20), 1.dp, Color.Green, 2.dp, padding),
            MarkdownStyling.Heading.H3(inlines(16), 0.dp, Color.Green, 0.dp, padding),
            MarkdownStyling.Heading.H4(inlines(14), 0.dp, Color.Green, 0.dp, padding),
            MarkdownStyling.Heading.H5(inlines(13), 0.dp, Color.Green, 0.dp, padding),
            MarkdownStyling.Heading.H6(inlines(12), 0.dp, Color.Green, 0.dp, padding),
        )
        val palette = palette(codeTint = Color.Red, boxFill = Color.Gray)
        val heading = headingWithInlineCode(bridge, palette, FontFamily.Monospace)
        val levels = with(heading) { listOf(h1, h2, h3, h4, h5, h6) }
        val before = with(bridge) { listOf(h1, h2, h3, h4, h5, h6) }
        assertEquals(listOf(25, 19, 15, 13, 12, 11).map { it.sp }, levels.map { it.inlinesStyling.inlineCode.fontSize })
        levels.zip(before).forEach { (level, old) ->
            val size = old.inlinesStyling.textStyle.fontSize
            assertEquals(inlineCodeStyle(palette, FontFamily.Monospace, size), level.inlinesStyling.inlineCode)
            // Everything but the inline code is the bridge's.
            assertEquals(old.inlinesStyling.textStyle, level.inlinesStyling.textStyle)
            assertEquals(old.inlinesStyling.link, level.inlinesStyling.link)
            assertEquals(listOf(old.underlineWidth, old.underlineGap), listOf(level.underlineWidth, level.underlineGap))
            assertEquals(old.underlineColor, level.underlineColor)
            assertEquals(old.padding, level.padding)
        }
    }

    private fun palette(codeTint: Color, boxFill: Color): ChatPalette {
        val none = Color.Unspecified
        return ChatPalette(
            bubbleFill = none,
            boxFill = boxFill,
            added = none,
            removed = none,
            insertedFill = none,
            deletedFill = none,
            dim = none,
            secondary = none,
            accent = none,
            shell = ShellColors(none, none, none, none, none, none),
            codeTint = codeTint,
        )
    }
}
