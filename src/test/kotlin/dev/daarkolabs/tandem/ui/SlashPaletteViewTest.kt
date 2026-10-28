package dev.daarkolabs.tandem.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.daarkolabs.tandem.chat.CommandScope
import dev.daarkolabs.tandem.chat.PaletteItem
import dev.daarkolabs.tandem.chat.slashPalette
import dev.daarkolabs.tandem.protocol.SlashCommand
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The palette's desktop form (MCP spec 14): its widths, where the description goes, what a row and the box read, and the filter hint. */
class SlashPaletteViewTest {
    private fun item(
        name: String,
        description: String = "",
        hint: String = "",
        scope: CommandScope = CommandScope.BUILTIN,
        via: String? = null,
    ): PaletteItem = PaletteItem(name, description, hint, scope, via, intercepted = false)

    @Test
    fun `the list fits its rows, at least its minimum, never wider than the box`() {
        assertEquals(240, paletteGeometry(240, 900, 200, 6, 140, 260).listWidth)
        assertEquals(200, paletteGeometry(120, 900, 200, 6, 140, 260).listWidth)
        assertEquals(300, paletteGeometry(420, 300, 200, 6, 140, 260).listWidth)
        // A box narrower than the minimum: the box's width.
        assertEquals(150, paletteGeometry(120, 150, 200, 6, 140, 260).listWidth)
        assertEquals(0, paletteGeometry(120, -5, 200, 6, 140, 260).listWidth)
    }

    @Test
    fun `the insets are counted as the paddings lay them out, each side rounded on its own`() {
        // At 125 %: 4 dp is 5 px, 10 dp is 12.5, so 13 px a side; 14 dp is 17.5, so 18 px a side. 28 dp at once would be 35.
        with(Density(1.25f)) {
            assertEquals(36, rowInsetsPx())
            assertEquals(36, footerInsetsPx())
            assertEquals(36 + 19 + 0, rowInsetsPx(scrollbarPadding = 15.dp))
        }
        // At 175 %: 7 + 18 a side for the rows, 25 a side for the footer, against 49 for 28 dp at once.
        with(Density(1.75f)) {
            assertEquals(50, rowInsetsPx())
            assertEquals(50, footerInsetsPx())
            assertEquals(50 + 25, rowInsetsPx(scrollbarTakenOut = 14.dp))
        }
        with(Density(1f)) { assertEquals(28, rowInsetsPx()) }
    }

    @Test
    fun `only macOS with scroll bars always shown takes the track out of the rows`() {
        assertEquals(14.dp, scrollbarTakenOut(mac = true, alwaysVisible = true, thickness = 14.dp))
        assertEquals(0.dp, scrollbarTakenOut(mac = true, alwaysVisible = false, thickness = 14.dp))
        assertEquals(0.dp, scrollbarTakenOut(mac = false, alwaysVisible = true, thickness = 14.dp))
    }

    @Test
    fun `the description goes beside the list when the box leaves room, else under it`() {
        assertEquals(PaletteGeometry(240, DescriptionPlace.BESIDE, 260), paletteGeometry(240, 900, 200, 6, 140, 260))
        // Exactly the minimum left: beside, as wide as what is left.
        assertEquals(PaletteGeometry(240, DescriptionPlace.BESIDE, 140), paletteGeometry(240, 386, 200, 6, 140, 260))
        // One pixel short: under, as wide as the list.
        assertEquals(PaletteGeometry(240, DescriptionPlace.UNDER, 240), paletteGeometry(240, 385, 200, 6, 140, 260))
        assertEquals(PaletteGeometry(300, DescriptionPlace.UNDER, 300), paletteGeometry(420, 300, 200, 6, 140, 260))
    }

    @Test
    fun `a row shows its hint only while highlighted and its alias always`() {
        val effort = item("effort", "Set effort level", hint = "<low|medium|high>")
        assertEquals("", rowTrailing(effort, selected = false))
        assertEquals(" <low|medium|high>", rowTrailing(effort, selected = true))
        val review = item("code-review", via = "review")
        assertEquals(" · via /review", rowTrailing(review, selected = false))
        assertEquals(" [pr] · via /review", rowTrailing(review.copy(argumentHint = "[pr]"), selected = true))
    }

    @Test
    fun `the description box reads the scope, then the description`() {
        val dim = Color.Gray
        assertEquals("Manage MCP servers", descriptionText(item("mcp", "Manage MCP servers"), dim).text)
        val project = descriptionText(item("review", "Review the diff", scope = CommandScope.PROJECT), dim)
        assertEquals("project · Review the diff", project.text)
        assertEquals(listOf(dim), project.spanStyles.map { it.item.color })
        assertEquals(0 until "project · ".length, project.spanStyles.single().let { it.start until it.end })
        assertEquals("plugin", descriptionText(item("p:x", scope = CommandScope.PLUGIN), dim).text)
        assertEquals("user · Mine", descriptionText(item("mine", "Mine", scope = CommandScope.USER), dim).text)
    }

    @Test
    fun `only a row with a description or a scope has a box`() {
        assertTrue(hasDescription(item("mcp", "Manage MCP servers")))
        assertTrue(hasDescription(item("review", scope = CommandScope.PROJECT)))
        assertFalse(hasDescription(item("compact")))
    }

    @Test
    fun `the highlighted row's top follows the scroll and stays in the viewport`() {
        // Rows 26 px tall, 10 in a 260 px viewport.
        assertEquals(78, selectedRowTop(selected = 3, firstIndex = 0, firstOffset = 0, rowHeight = 26, viewportHeight = 260))
        assertEquals(68, selectedRowTop(selected = 5, firstIndex = 2, firstOffset = 10, rowHeight = 26, viewportHeight = 260))
        // Scrolled out of view, above and below: the nearest edge's row.
        assertEquals(0, selectedRowTop(selected = 1, firstIndex = 4, firstOffset = 0, rowHeight = 26, viewportHeight = 260))
        assertEquals(234, selectedRowTop(selected = 40, firstIndex = 0, firstOffset = 0, rowHeight = 26, viewportHeight = 260))
        // A viewport shorter than a row.
        assertEquals(0, selectedRowTop(selected = 2, firstIndex = 0, firstOffset = 0, rowHeight = 26, viewportHeight = 10))
    }

    @Test
    fun `the box beside the list is level with its row and ends with the popup`() {
        assertEquals(78, descriptionTop(rowTop = 78, boxHeight = 50, height = 268))
        assertEquals(218, descriptionTop(rowTop = 250, boxHeight = 50, height = 268))
        // Taller than the popup's other content: the popup is the box's height, the box at its top.
        assertEquals(0, descriptionTop(rowTop = 4, boxHeight = 50, height = 50))
        assertEquals(0, descriptionTop(rowTop = -3, boxHeight = 20, height = 268))
    }

    @Test
    fun `the composer reads Type to filter after a lone slash while the palette is open`() {
        val commands = listOf(SlashCommand("context", "Show current context usage"))
        val open = slashPalette("/", commands, emptyList(), emptySet())
        assertTrue(filterHintShown("/", open))
        assertFalse(filterHintShown("/", null), "Esc closed the palette, or the text area lost the focus")
        assertFalse(filterHintShown("/c", slashPalette("/c", commands, emptyList(), emptySet())))
        assertFalse(filterHintShown("", open))
        assertEquals("Type to filter", FILTER_HINT)
    }

    @Test
    fun `scopeLabel names every source`() {
        // spec 17.6
        val labels = mapOf(
            CommandScope.BUILTIN to null,
            CommandScope.PROJECT to "project",
            CommandScope.USER to "user",
            CommandScope.PLUGIN to "plugin",
            CommandScope.CLAUDE_AI to "claude.ai",
            CommandScope.MANAGED to "managed",
            CommandScope.CLI_FLAG to "CLI flag",
            CommandScope.MCP to "MCP",
            CommandScope.OTHER to null,
        )
        assertEquals(CommandScope.entries.toSet(), labels.keys)
        for ((scope, label) in labels) assertEquals(label, scopeLabel(scope), scope.name)
    }
}
