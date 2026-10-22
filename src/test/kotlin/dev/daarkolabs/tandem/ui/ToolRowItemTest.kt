package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.RowLabel
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.chat.ToolKind
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.ui.rows.CommandPart
import dev.daarkolabs.tandem.ui.rows.Marker
import dev.daarkolabs.tandem.ui.rows.canExpand
import dev.daarkolabs.tandem.ui.rows.clampOwners
import dev.daarkolabs.tandem.ui.rows.commandHeader
import dev.daarkolabs.tandem.ui.rows.commandParts
import dev.daarkolabs.tandem.ui.rows.headerMarker
import dev.daarkolabs.tandem.ui.rows.marker
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRowItemTest {
    @Test
    fun `a running group can expand, a lone running call only with a body`() {
        assertTrue(canExpand(group(ToolState.RUNNING, call("a", ToolState.DONE, MONO), call("b", ToolState.RUNNING, ToolBody.None))))
        assertTrue(canExpand(group(ToolState.DONE, call("a", ToolState.DONE, MONO), call("b", ToolState.DONE, ToolBody.None))))
        assertTrue(canExpand(lone(call("a", ToolState.RUNNING, COMMAND))))
        assertFalse(canExpand(lone(call("a", ToolState.RUNNING, ToolBody.None))))
        assertFalse(canExpand(lone(call("a", ToolState.DONE, ToolBody.None))))
        assertTrue(canExpand(lone(call("a", ToolState.DENIED, MONO))))
    }

    @Test
    fun `a live sub-row is marked running or waiting`() {
        assertEquals(Marker.RUNNING, marker(call("a", ToolState.PENDING, ToolBody.None)))
        assertEquals(Marker.RUNNING, marker(call("a", ToolState.RUNNING, ToolBody.None)))
        assertEquals(Marker.WAITING, marker(call("a", ToolState.WAITING, ToolBody.None)))
        for (state in listOf(ToolState.DONE, ToolState.FAILED, ToolState.DENIED, ToolState.INTERRUPTED)) {
            assertEquals(Marker.NONE, marker(call("a", state, MONO)), state.name)
        }
    }

    @Test
    fun `a running lone row is marked, a group header is not`() {
        assertEquals(Marker.RUNNING, headerMarker(lone(call("a", ToolState.RUNNING, ToolBody.None))))
        assertEquals(
            Marker.NONE,
            headerMarker(group(ToolState.RUNNING, call("a", ToolState.DONE, MONO), call("b", ToolState.RUNNING, ToolBody.None))),
        )
    }

    @Test
    fun `a row that cannot open has no chevron, live or finished`() {
        assertFalse(canExpand(call("a", ToolState.RUNNING, ToolBody.None)))
        assertTrue(canExpand(call("a", ToolState.RUNNING, COMMAND)))
        assertFalse(canExpand(call("a", ToolState.DONE, ToolBody.None)))
    }

    @Test
    fun `a command body without output has no output part`() {
        assertEquals(
            listOf(CommandPart.HEADER, CommandPart.COMMAND),
            commandParts(ToolBody.Command("ls", persistentListOf(), 0, false), framed = true),
        )
        assertEquals(listOf(CommandPart.COMMAND), commandParts(ToolBody.Command("ls", persistentListOf(), 0, false), framed = false))
        assertEquals(
            listOf(CommandPart.HEADER, CommandPart.COMMAND, CommandPart.OUTPUT),
            commandParts(ToolBody.Command("ls", persistentListOf("a"), 1, false), framed = true),
        )
    }

    @Test
    fun `a group sub-row's Bash body has no header and no frame`() {
        assertEquals(
            listOf(CommandPart.COMMAND, CommandPart.OUTPUT),
            commandParts(ToolBody.Command("ls", persistentListOf("a"), 1, false), framed = false),
        )
        assertEquals(
            listOf(CommandPart.HEADER, CommandPart.COMMAND, CommandPart.OUTPUT),
            commandParts(ToolBody.Command("ls", persistentListOf("a"), 1, false), framed = true),
        )
    }

    @Test
    fun `a PowerShell body is headed PowerShell`() {
        assertEquals("PowerShell", commandHeader("PowerShell"))
        assertEquals("Bash", commandHeader("Bash"))
    }

    @Test
    fun `the clamp owners of a group are the row and its calls`() {
        assertEquals(listOf("c:a", "c:b"), clampOwners(group(ToolState.DONE, call("a", ToolState.DONE, MONO), call("b", ToolState.DONE, MONO))))
        val nested = lone(call("n", ToolState.DONE, MONO))
        val agent = ToolCall(
            "agent", "Agent", ToolKind.AGENT, ToolState.DONE, RowLabel("Agent", persistentListOf()), null, ToolBody.None,
            persistentListOf(nested), null,
        )
        assertEquals(listOf("c:agent", "c:n"), clampOwners(lone(agent)))
    }

    private fun call(id: String, state: ToolState, body: ToolBody): ToolCall =
        ToolCall(id, "Read", ToolKind.READ, state, RowLabel("Read $id", persistentListOf()), null, body, persistentListOf(), null)

    private fun group(state: ToolState, vararg calls: ToolCall): ToolRow =
        ToolRow("tool:${calls.first().id}", persistentListOf(*calls), RowLabel("Read 2 files", persistentListOf()), state, null, RowGap.ROW)

    private fun lone(call: ToolCall): ToolRow = ToolRow("tool:${call.id}", persistentListOf(call), call.label, call.state, null, RowGap.ROW)

    private companion object {
        val MONO: ToolBody = ToolBody.Mono(persistentListOf("The user denied this request."), totalLines = 1)
        val COMMAND: ToolBody = ToolBody.Command("ls", persistentListOf(), 0, firstLineError = false)
    }
}
