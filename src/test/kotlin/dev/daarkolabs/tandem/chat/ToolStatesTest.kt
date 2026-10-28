package dev.daarkolabs.tandem.chat

import dev.daarkolabs.tandem.session.AssistantBlock
import dev.daarkolabs.tandem.session.ChatEntry
import dev.daarkolabs.tandem.session.PermissionDecision
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One test per transition of the 4.6.3 state machine, through [ToolStates.of]. */
class ToolStatesTest {
    private val input = buildJsonObject { put("file_path", "<path>") }
    private val b = AssistantBlock.ToolUse("t1", "Read", input, partialInput = "")
    private val streaming = AssistantBlock.ToolUse("t1", "Read", input = null, partialInput = "{\"file_pa")
    private val ok = ChatEntry.ToolResult("r1", toolUseId = "t1", text = "fine", isError = false)
    private val err = ChatEntry.ToolResult("r1", toolUseId = "t1", text = "boom", isError = true, decision = null)
    private val rej = ChatEntry.ToolResult(
        "r1",
        toolUseId = "t1",
        text = "no",
        isError = true,
        decision = PermissionDecision("reject", "user_reject", null, "permission-rule"),
    )

    @Test
    fun `content_block_start yields PENDING`() {
        assertEquals(ToolState.PENDING, ToolStates.of(streaming, result = null, pending = false, turnOpen = true))
    }

    @Test
    fun `a complete line yields RUNNING`() {
        assertEquals(ToolState.RUNNING, ToolStates.of(b, result = null, pending = false, turnOpen = true))
    }

    @Test
    fun `can_use_tool yields WAITING`() {
        assertEquals(ToolState.WAITING, ToolStates.of(b, result = null, pending = true, turnOpen = true))
    }

    @Test
    fun `a request before the complete line yields WAITING`() {
        assertEquals(ToolState.WAITING, ToolStates.of(streaming, result = null, pending = true, turnOpen = true))
    }

    @Test
    fun `an answer returns to RUNNING`() {
        assertEquals(ToolState.WAITING, ToolStates.of(b, result = null, pending = true, turnOpen = true))
        assertEquals(ToolState.RUNNING, ToolStates.of(b, result = null, pending = false, turnOpen = true))
    }

    @Test
    fun `an ok result yields DONE`() {
        assertEquals(ToolState.DONE, ToolStates.of(b, ok, pending = false, turnOpen = true))
    }

    @Test
    fun `a reject decision yields DENIED`() {
        assertEquals(ToolState.DENIED, ToolStates.of(b, rej, pending = false, turnOpen = true))
    }

    @Test
    fun `another error yields FAILED`() {
        assertEquals(ToolState.FAILED, ToolStates.of(b, err, pending = false, turnOpen = true))
        val accepted = err.copy(decision = PermissionDecision("accept", "user_temporary", null, null))
        assertEquals(ToolState.FAILED, ToolStates.of(b, accepted, pending = false, turnOpen = true))
    }

    @Test
    fun `turn end yields INTERRUPTED`() {
        assertEquals(ToolState.INTERRUPTED, ToolStates.of(b, result = null, pending = false, turnOpen = false))
        assertEquals(ToolState.INTERRUPTED, ToolStates.of(streaming, result = null, pending = false, turnOpen = false))
    }

    @Test
    fun `a late result upgrades INTERRUPTED`() {
        assertEquals(ToolState.DONE, ToolStates.of(b, ok, pending = false, turnOpen = false))
        assertEquals(ToolState.FAILED, ToolStates.of(b, err, pending = false, turnOpen = false))
    }

    @Test
    fun `reload without meta still detects the denial text`() {
        for (text in listOf("The user denied this command.", "cancelled", "The user denied this request.")) {
            assertEquals(ToolState.DENIED, ToolStates.of(b, err.copy(text = text), pending = false, turnOpen = false), text)
        }
    }

    @Test
    fun `a settled state never yields RUNNING`() {
        assertEquals(ToolState.INTERRUPTED, ToolStates.of(b, result = null, pending = false, turnOpen = false))
    }

    // spec: a rejected edit is DENIED despite the accept decision
    @Test
    fun `a rejected edit is DENIED despite the accept decision`() {
        // ide-edit:105: the user's Reject, with the decision the CLI recorded for Tandem's allow.
        val rejected = toolResultAt("ide-edit", 105)
        assertEquals(PermissionDecision("accept", "user_temporary", null, null), rejected.decision)
        assertEquals(ToolState.DENIED, ToolStates.of(toolUseAt("ide-edit", 101), rejected, pending = false, turnOpen = true))
        val made = err.copy(text = "The user rejected this edit in the IDE; nothing was changed.", decision = PermissionDecision("accept", "user_temporary", null, null))
        assertEquals(ToolState.DENIED, ToolStates.of(b, made, pending = false, turnOpen = false))
    }

    // spec: a user-rejected result is INTERRUPTED
    @Test
    fun `a user-rejected result is INTERRUPTED`() {
        // ide-edit-stop:44: the CLI's synthetic result after Stop, no permission decision.
        val stopped = toolResultAt("ide-edit-stop", 44)
        assertNull(stopped.decision)
        assertEquals("user-rejected", stopped.nonExecutionKind)
        assertTrue(stopped.text.startsWith("The user doesn't want to proceed"))
        assertEquals(ToolState.INTERRUPTED, ToolStates.of(toolUseAt("ide-edit-stop", 36), stopped, pending = false, turnOpen = true))
        assertEquals(ToolState.INTERRUPTED, ToolStates.of(b, err.copy(nonExecutionKind = "user-rejected"), pending = false, turnOpen = false))
    }
}
