package dev.daarkolabs.tandem.ui

import dev.daarkolabs.tandem.chat.RowGap
import dev.daarkolabs.tandem.chat.RowLabel
import dev.daarkolabs.tandem.chat.ToolBody
import dev.daarkolabs.tandem.chat.ToolCall
import dev.daarkolabs.tandem.chat.ToolKind
import dev.daarkolabs.tandem.chat.ToolRow
import dev.daarkolabs.tandem.chat.ToolState
import dev.daarkolabs.tandem.ui.rows.canExpand
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRowItemTest {
    @Test
    fun `a running group cannot expand`() {
        assertFalse(canExpand(group(ToolState.RUNNING, call("a", ToolState.DONE, MONO), call("b", ToolState.RUNNING, ToolBody.None))))
        assertTrue(canExpand(group(ToolState.DONE, call("a", ToolState.DONE, MONO), call("b", ToolState.DONE, ToolBody.None))))
        assertFalse(canExpand(lone(call("a", ToolState.DONE, ToolBody.None))))
        assertTrue(canExpand(lone(call("a", ToolState.DENIED, MONO))))
    }

    private fun call(id: String, state: ToolState, body: ToolBody): ToolCall =
        ToolCall(id, "Read", ToolKind.READ, state, RowLabel("Read $id", persistentListOf()), null, body, persistentListOf(), null)

    private fun group(state: ToolState, vararg calls: ToolCall): ToolRow =
        ToolRow("tool:${calls.first().id}", persistentListOf(*calls), RowLabel("Read 2 files", persistentListOf()), state, null, RowGap.ROW)

    private fun lone(call: ToolCall): ToolRow = ToolRow("tool:${call.id}", persistentListOf(call), call.label, call.state, null, RowGap.ROW)

    private companion object {
        val MONO: ToolBody = ToolBody.Mono(persistentListOf("The user denied this request."), totalLines = 1)
    }
}
