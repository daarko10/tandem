package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.protocol.AgentUsage
import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.protocol.ContextCategory
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.McpInstructionsUsage
import dev.daarkolabs.tandem.protocol.McpToolUsage
import dev.daarkolabs.tandem.protocol.MemoryFileUsage
import dev.daarkolabs.tandem.session.ContextUsage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The context popover's model (spec 3.1, 4.3) from `parity-context:17`, the breakdown after a turn. The `mcpInstructions` and `agents`
 * entries are docs-only shapes (no recording has one).
 */
class ContextBreakdownModelTest {
    private val b17: ContextBreakdown =
        ControlPayloads.contextUsage((fixtureLine("parity-context", 17) as CliMessage.ControlResponse).payload)!!
    private val m: ContextPopoverModel = contextPopover(b17, null, null, null)

    @Test
    fun `rows follow the desktop's order with shares of the window`() {
        assertEquals(
            listOf(
                Triple("System tools", "21.4k", "2.1%"),
                Triple("Messages", "6.4k", "0.6%"),
                Triple("Memory files", "4.3k", "0.4%"),
                Triple("Skills", "3.2k", "0.3%"),
                Triple("System prompt", "2.8k", "0.3%"),
                Triple("Autocompact buffer", "33.0k", "3.3%"),
                Triple("Free space", "929.0k", "92.9%"),
                Triple("System tools (deferred)", "22.3k", "—"),
            ),
            m.rows.map { Triple(it.name, it.tokens, it.share) },
        )
    }

    @Test
    fun `tones follow the category id`() {
        val n = ContextTone.NEUTRAL
        assertEquals(
            listOf(n, ContextTone.MESSAGES, ContextTone.MEMORY_FILES, n, ContextTone.SYSTEM_PROMPT, n, ContextTone.FREE, n),
            m.rows.map { it.tone },
        )
        val novel = b17.copy(categories = listOf(ContextCategory("something_new", "Messages", 5, "used", false)))
        assertEquals(ContextTone.NEUTRAL, contextPopover(novel, null, null, null).rows.single().tone)
        assertEquals("Messages, 6.4k tokens, 0.6%", m.rows[1].description)
        assertEquals("System tools (deferred), 22.3k tokens, deferred", m.rows.last().description)
    }

    @Test
    fun `an unknown or missing kind sorts last in the CLI's order`() {
        val extra = b17.copy(
            categories = b17.categories + ContextCategory("x", "x", 7, "later", false) + ContextCategory("y", "y", 9, null, false),
        )
        val names = contextPopover(extra, null, null, null).rows.map { it.name }
        assertEquals(listOf("System tools (deferred)", "x", "y"), names.takeLast(3))
    }

    @Test
    fun `zero-token rows are hidden`() {
        val zero = b17.copy(categories = b17.categories + ContextCategory("mcp_tools", "MCP tools", 0, "used", false))
        assertTrue(contextPopover(zero, null, null, null).rows.none { it.name == "MCP tools" })
        assertEquals(8, contextPopover(zero, null, null, null).rows.size)
    }

    @Test
    fun `the bar has one segment per used category`() {
        val n = ContextTone.NEUTRAL
        assertEquals(listOf(n, ContextTone.MESSAGES, ContextTone.MEMORY_FILES, n, ContextTone.SYSTEM_PROMPT), m.segments.map { it.tone })
        assertEquals(0.006448f, m.segments[1].fraction)
    }

    @Test
    fun `the header reads the CLI's total, window and percentage`() {
        assertEquals("38.0k / 1M (4%)", m.value)
        assertEquals("38.0k / 1M (4%)", contextPopover(null, ContextUsage(38032, 1_000_000), null, null).value)
        assertNull(contextPopover(null, null, null, null).value)
        assertEquals("38.0k / 1M (4%)", contextPopover(b17.copy(percentage = null), null, null, null).value)
    }

    @Test
    fun `a window of zero shows no shares and no bar`() {
        val zero = contextPopover(b17.copy(maxTokens = 0), ContextUsage(38032, 1_000_000), null, null)
        assertTrue(zero.rows.all { it.share == "—" })
        assertTrue(zero.segments.isEmpty())
        assertEquals("38.0k / 1M (4%)", zero.value)
    }

    @Test
    fun `the MCP tools group names server and tool`() {
        val group = m.groups.single { it.key == "mcp_tools" }
        assertEquals("MCP tools", group.title)
        assertEquals(1, group.count)
        assertEquals("0", group.total)
        assertEquals(listOf(GroupItem("echo · echo", "0", dim = true, tooltip = null)), group.items)
        val plain = contextPopover(b17.copy(mcpTools = listOf(McpToolUsage("plain", "s", 5, true))), null, null, null)
        assertEquals("s · plain", plain.groups.single { it.key == "mcp_tools" }.items.single().label)
        val alone = contextPopover(b17.copy(mcpTools = listOf(McpToolUsage("plain", null, 5, null))), null, null, null)
        assertEquals(GroupItem("plain", "5", dim = false, tooltip = null), alone.groups.single { it.key == "mcp_tools" }.items.single())
    }

    @Test
    fun `groups appear only with items and keep the CLI's order`() {
        assertEquals(listOf("mcp_tools", "memory_files", "skills"), m.groups.map { it.key })
        val skills = m.groups.single { it.key == "skills" }
        assertEquals("Skills", skills.title)
        assertEquals(36, skills.count)
        assertEquals("3.2k", skills.total)
        assertEquals("actions", skills.items[0].label)
        assertTrue(skills.items.any { it.label == "probe-kit:greet" && it.tokens == "22" })
        assertEquals(0, skills.more)
    }

    @Test
    fun `a group shows at most 100 items and counts the rest`() {
        val tools = (1..250).map { McpToolUsage("mcp__s__t$it", "s", 1, true) }
        val group = contextPopover(b17.copy(mcpTools = tools), null, null, null).groups.single { it.key == "mcp_tools" }
        assertEquals(GROUP_ITEM_CAP, group.items.size)
        assertEquals(150, group.more)
        assertEquals(250, group.count)
        assertEquals("250", group.total)
    }

    @Test
    fun `memory paths show the home as tilde and project files relative`() {
        assertEquals("~/.claude/CLAUDE.md", displayPath("/home/u/.claude/CLAUDE.md", "/home/u", "/home/u/p"))
        assertEquals("CLAUDE.md", displayPath("/home/u/p/CLAUDE.md", "/home/u", "/home/u/p"))
        assertEquals("sub\\CLAUDE.md", displayPath("C:\\p\\sub\\CLAUDE.md", null, "C:\\p"))
        val files = b17.copy(memoryFiles = listOf(MemoryFileUsage("/home/u/p/CLAUDE.md", "Project", 40), MemoryFileUsage("/x", null, 1)))
        val group = contextPopover(files, null, "/home/u", "/home/u/p").groups.single { it.key == "memory_files" }
        assertEquals("Memory files", group.title)
        assertEquals(GroupItem("CLAUDE.md", "40", dim = false, tooltip = "/home/u/p/CLAUDE.md · Project"), group.items[0])
        assertEquals(GroupItem("/x", "1", dim = false, tooltip = "/x"), group.items[1])
        assertEquals(GroupItem("<path>", "4.3k", dim = false, tooltip = "<path> · Project"), m.groups.single { it.key == "memory_files" }.items.single())
    }

    @Test
    fun `a path that only shares a prefix with home or the project stays absolute`() {
        assertEquals("/home/alex/x", displayPath("/home/alex/x", "/home/al", null))
        assertEquals("/p/app2/CLAUDE.md", displayPath("/p/app2/CLAUDE.md", null, "/p/app"))
        assertEquals("~", displayPath("/home/al", "/home/al", null))
    }

    @Test
    fun `the instructions and agents groups follow the SDK's shape`() {
        // Docs-only shapes: no recording has mcpInstructions or agents; the token values are taken from parity-context:17.
        val more = b17.copy(
            mcpInstructions = listOf(McpInstructionsUsage("echo", 26), McpInstructionsUsage("docs", 22)),
            agents = listOf(AgentUsage("reviewer", "projectSettings", 919)),
        )
        val groups = contextPopover(more, null, null, null).groups
        assertEquals(listOf("mcp_tools", "mcp_instructions", "memory_files", "skills", "agents"), groups.map { it.key })
        val instructions = groups.single { it.key == "mcp_instructions" }
        assertEquals("MCP server instructions", instructions.title)
        assertEquals("48", instructions.total)
        assertEquals(2, instructions.count)
        assertEquals(listOf("echo", "docs"), instructions.items.map { it.label })
        val agents = groups.single { it.key == "agents" }
        assertEquals("Custom agents", agents.title)
        assertEquals("919", agents.total)
        assertEquals(1, agents.count)
        assertEquals(GroupItem("reviewer", "919", dim = false, tooltip = "projectSettings"), agents.items[0])
    }

    @Test
    fun `the popover status follows the fetch`() {
        assertEquals(PopoverStatus(null, "Loading the breakdown…", false, false), popoverStatus(true, null, false, hasRows = false))
        assertEquals(PopoverStatus(" · Updating…", null, false, false), popoverStatus(true, null, false, hasRows = true))
        val failed = "Could not load the breakdown: x"
        assertEquals(PopoverStatus(null, failed, error = true, dimRows = true), popoverStatus(false, failed, false, hasRows = true))
        assertEquals(PopoverStatus(null, failed, error = true, dimRows = false), popoverStatus(false, failed, false, hasRows = false))
        val stopped = PopoverStatus(null, "The session is not running. The breakdown updates with the next prompt.", false, true)
        assertEquals(stopped, popoverStatus(false, null, stopped = true, hasRows = true))
        assertEquals(stopped, popoverStatus(false, failed, stopped = true, hasRows = true))
        assertEquals(PopoverStatus(null, null, false, false), popoverStatus(false, null, false, false))
    }

    @Test
    fun `percentOf has one decimal and a dash without a window`() {
        assertEquals("0.3%", ContextFormat.percentOf(3155, 1_000_000))
        assertEquals("0.0%", ContextFormat.percentOf(120, 1_000_000))
        assertEquals("92.9%", ContextFormat.percentOf(928968, 1_000_000))
        assertEquals("—", ContextFormat.percentOf(5, 0))
    }
}
