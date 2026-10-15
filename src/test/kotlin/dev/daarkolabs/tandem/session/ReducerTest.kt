package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducerTest {
    private fun replay(fixture: String, initial: ChatState = ChatState()): List<ChatState> =
        Fixtures.decode(fixture).runningFold(initial) { state, message -> reduce(state, SessionEvent.Cli(message)) }

    private fun cli(json: String): SessionEvent = SessionEvent.Cli(Ndjson.decode(json))

    private fun ChatState.assistants(): List<ChatEntry.Assistant> = entries.filterIsInstance<ChatEntry.Assistant>()

    private fun ChatState.text(): String =
        assistants().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Text>().joinToString("") { it.text }

    @Test
    fun `plain reply streams a draft, the complete message replaces it, and there is never a second bubble`() {
        val states = replay("plain-reply")
        for (state in states) {
            assertTrue(state.assistants().size <= 1, "partial and complete must never render twice")
        }
        val streamed = states.first { it.assistants().singleOrNull()?.blocks?.isNotEmpty() == true }
        assertTrue(streamed.assistants().single().streaming)
        val final = states.last()
        assertEquals("pong", final.text())
        assertFalse(final.assistants().single().streaming)
        assertFalse(final.busy)
        assertEquals("claude-sonnet-5-5", final.activeModel)
        assertNull(final.modelWarning)
        assertTrue(final.entries.none { it is ChatEntry.Notice })
    }

    @Test
    fun `replay is deterministic`() {
        assertEquals(replay("permission").last(), replay("permission").last())
    }

    @Test
    fun `permission requests are pending until answered and tool results land in the transcript`() {
        val states = replay("permission")
        val asked = states.first { it.pendingPermissions.isNotEmpty() }
        assertEquals("Bash", asked.pendingPermissions.single().toolName)

        val answered = reduce(asked, SessionEvent.PermissionAnswered(asked.pendingPermissions.single().requestId))
        assertTrue(answered.pendingPermissions.isEmpty())

        val final = states.last()
        assertEquals(listOf(false, true), final.entries.filterIsInstance<ChatEntry.ToolResult>().map { it.isError })
        val recorded = Fixtures.decode("permission").filterIsInstance<CliMessage.Assistant>().flatMap { it.content }
            .filterIsInstance<dev.daarkolabs.tandem.protocol.ContentBlock.Text>().joinToString("") { it.text }
        assertEquals(recorded, final.text())
    }

    @Test
    fun `the CLI cancelling its own request closes the prompt`() {
        val asked = reduce(ChatState(), cli("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{}}}"""))
        assertEquals(1, asked.pendingPermissions.size)
        assertTrue(reduce(asked, cli("""{"type":"control_cancel_request","request_id":"r1"}""")).pendingPermissions.isEmpty())
    }

    @Test
    fun `interrupt shows Stopped and no error`() {
        val final = replay("interrupt").last()
        val notices = final.entries.filterIsInstance<ChatEntry.Notice>()
        assertEquals(listOf("Stopped."), notices.map { it.text })
        assertFalse(final.busy)
        assertTrue(final.assistants().none { it.streaming })
    }

    @Test
    fun `a requested model switch confirmed by init produces no warning`() {
        var state = ChatState(requestedModel = "claude-sonnet-5-5")
        for (message in Fixtures.decode("model-switch")) {
            if (message is CliMessage.ControlResponse && message.payload == null) state = reduce(state, SessionEvent.ModelRequested("claude-haiku-5-5"))
            state = reduce(state, SessionEvent.Cli(message))
            assertNull(state.modelWarning, "unexpected warning: ${state.modelWarning}")
        }
        assertEquals("claude-haiku-5-5", state.activeModel)
    }

    @Test
    fun `a model the user did not pick is never silent`() {
        // The user picked Opus, but the recorded session ran Sonnet then Haiku.
        val final = replay("model-switch", ChatState(requestedModel = "claude-opus-5-5")).last()
        assertNotNull(final.modelWarning)
        assertTrue(final.modelWarning!!.contains("claude-haiku-5-5"))
        val warnings = final.entries.filterIsInstance<ChatEntry.Notice>().filter { it.level == NoticeLevel.WARNING }
        assertEquals(2, warnings.size, "one notice per distinct mismatch: $warnings")
    }

    @Test
    fun `aliases resolve without a warning`() {
        assertTrue(modelMatches("opus", "claude-opus-5-5"))
        assertTrue(modelMatches("claude-opus-5-5[1m]", "claude-opus-5-5"))
        assertTrue(modelMatches("default", "claude-sonnet-5-5"))
        assertFalse(modelMatches("opus", "claude-sonnet-5-5"))
    }

    @Test
    fun `refusal fallback retracts the refused reply and warns`() {
        var state = ChatState(requestedModel = "claude-opus-5-5")
        state = reduce(state, cli("""{"type":"assistant","uuid":"a1","message":{"id":"m1","model":"claude-opus-5-5","content":[{"type":"text","text":"refused"}]}}"""))
        state = reduce(
            state,
            cli(
                """{"type":"system","subtype":"model_refusal_fallback","original_model":"claude-opus-5-5","fallback_model":"claude-sonnet-5-5",""" +
                    """"scope":"session","retracted_message_uuids":["a1"]}""",
            ),
        )
        assertTrue(state.assistants().isEmpty())
        assertEquals("claude-sonnet-5-5", state.activeModel)
        assertNotNull(state.modelWarning)
    }

    @Test
    fun `overload fallback seen only on the assistant message still warns`() {
        var state = ChatState(requestedModel = "claude-opus-5-5")
        state = reduce(state, cli("""{"type":"system","subtype":"init","session_id":"s","model":"claude-opus-5-5"}"""))
        assertNull(state.modelWarning)
        state = reduce(state, cli("""{"type":"assistant","message":{"id":"m1","model":"claude-sonnet-5-5","content":[{"type":"text","text":"hi"}]}}"""))
        assertTrue(state.modelWarning!!.contains("claude-sonnet-5-5"))
    }

    @Test
    fun `retries, errors and process exit are visible`() {
        var state = reduce(ChatState(), cli("""{"type":"system","subtype":"api_retry","attempt":2,"max_retries":10,"retry_delay_ms":4000,"error":"overloaded"}"""))
        assertEquals("API request failed (overloaded), retrying 2/10 in 4s.", (state.entries.last() as ChatEntry.Notice).text)
        state = reduce(state, cli("""{"type":"assistant","error":"authentication_failed","message":{"id":"m","content":[]}}"""))
        assertTrue((state.entries.last() as ChatEntry.Notice).text.contains("not signed in"))
        state = reduce(state, cli("""{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["boom"]}"""))
        assertEquals(NoticeLevel.ERROR, (state.entries.last() as ChatEntry.Notice).level)
        state = reduce(state, SessionEvent.Ended("Claude Code exited with code 1.", isError = true))
        assertTrue(state.ended)
    }
}
