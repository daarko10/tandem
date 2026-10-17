package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.PermissionMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReducerTest {
    private fun replay(fixture: String, initial: ChatState = ChatState()): List<ChatState> =
        Fixtures.decode(fixture).runningFold(initial) { state, message -> reduce(state, SessionEvent.Cli(message)) }

    private fun cli(json: String): SessionEvent = SessionEvent.Cli(Ndjson.decode(json))

    /** A complete `assistant` line with one text block; [fields] go in ahead of `message`, such as `"supersedes":["a1"],`. */
    private fun reply(uuid: String, messageId: String, text: String, fields: String = ""): SessionEvent =
        cli("""{"type":"assistant","uuid":"$uuid",$fields"message":{"id":"$messageId","content":[{"type":"text","text":"$text"}]}}""")

    private fun ChatState.assistants(): List<ChatEntry.Assistant> = entries.filterIsInstance<ChatEntry.Assistant>()

    private fun ChatState.text(): String =
        assistants().flatMap { it.blocks }.filterIsInstance<AssistantBlock.Text>().joinToString("") { it.text }

    /** What [ChatState.entryIndex] has to hold: the last position of each id in [ChatState.entries]. */
    private fun ChatState.positions(): Map<String, Int> = entries.withIndex().associate { (position, entry) -> entry.id to position }

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
    fun `the last result says how the turn ended`() {
        assertNull(ChatState().lastTurnEnd, "no turn yet")
        assertEquals(TurnEnd.COMPLETED, replay("plain-reply").last().lastTurnEnd)
        // The recorded interrupt: an error_during_execution result whose terminal_reason is aborted_streaming.
        assertEquals(TurnEnd.INTERRUPTED, replay("interrupt").last().lastTurnEnd)
        val failed = reduce(
            ChatState(busy = true),
            cli("""{"type":"result","subtype":"error_max_turns","is_error":true,"session_id":"s","errors":["max turns"]}"""),
        )
        assertEquals(TurnEnd.FAILED, failed.lastTurnEnd)
    }

    @Test
    fun `a recorded error result is a failed turn`() {
        // Recorded with --model claude-nonexistent-0: a result with is_error and terminal_reason api_error.
        val final = replay("invalid-model").last()
        assertEquals(TurnEnd.FAILED, final.lastTurnEnd)
        assertEquals(1, final.turnsEnded)
        assertFalse(final.busy)
        val errors = final.entries.filterIsInstance<ChatEntry.Notice>().filter { it.level == NoticeLevel.ERROR }
        // First the API error, on a line the CLI wrote itself with the model <synthetic>: it names the model that was picked.
        assertEquals("The CLI rejected the model claude-nonexistent-0. Pick another model.", errors.first().text)
        // Its subtype is "success", which says nothing here: the result's notice names the terminal reason.
        val notice = errors.last()
        assertEquals(
            "The turn failed (api_error): There's an issue with the selected model (claude-nonexistent-0). " +
                "It may not exist or you may not have access to it.",
            notice.text,
        )
    }

    @Test
    fun `an interrupt without a terminal reason is still a stopped turn`() {
        // As a CLI that does not send terminal_reason would: the recorded interrupt, its result without the field.
        val lines = Fixtures.lines("interrupt").map { line ->
            val json = Json.parseToJsonElement(line).jsonObject
            if (json["type"]?.jsonPrimitive?.content == "result") JsonObject(json - "terminal_reason").toString() else line
        }
        val final = lines.fold(ChatState()) { state, line -> reduce(state, cli(line)) }
        assertEquals(TurnEnd.INTERRUPTED, final.lastTurnEnd)
        assertTrue(final.entries.none { it is ChatEntry.Notice && it.text.startsWith("The turn failed") }, final.entries.toString())
        // The next turn starts afresh: its result alone says how it ended.
        val result = Fixtures.lines("plain-reply").first { "\"type\":\"result\"" in it }
        val next = reduce(reduce(final, SessionEvent.UserSent("u2", "again")), cli(result))
        assertEquals(TurnEnd.COMPLETED, next.lastTurnEnd)
    }

    @Test
    fun `turns end with a result, or with a process that fails during one`() {
        val result = Fixtures.lines("plain-reply").first { "\"type\":\"result\"" in it }
        assertEquals(0, ChatState().turnsEnded)
        val one = reduce(reduce(ChatState(), SessionEvent.UserSent("u1", "hi")), cli(result))
        assertEquals(1, one.turnsEnded)
        // A process that dies during a turn: the turn failed.
        val crashed = reduce(reduce(one, SessionEvent.UserSent("u2", "more")), SessionEvent.Ended("exited with code 137", isError = true))
        assertEquals(2, crashed.turnsEnded)
        assertEquals(TurnEnd.FAILED, crashed.lastTurnEnd)
        // A clean exit during a turn ends it too, without its result: it failed.
        val exited = reduce(reduce(one, SessionEvent.UserSent("u2", "more")), SessionEvent.Ended("exited", isError = false))
        assertEquals(2, exited.turnsEnded)
        assertEquals(TurnEnd.FAILED, exited.lastTurnEnd)
        // Between turns, an exit ends no turn, error or not.
        assertEquals(1, reduce(one, SessionEvent.Ended("exited with code 1", isError = true)).turnsEnded)
        assertEquals(1, reduce(one, SessionEvent.Ended("exited", isError = false)).turnsEnded)
    }

    @Test
    fun `a permission answered outside a turn ends no turn`() {
        val request = Fixtures.lines("permission").first { "\"can_use_tool\"" in it }
        val waiting = reduce(ChatState(), cli(request))
        assertEquals(1, waiting.pendingPermissions.size)
        val answered = reduce(waiting, SessionEvent.PermissionAnswered(waiting.pendingPermissions.single().requestId))
        assertEquals(0, answered.turnsEnded)
        assertNull(answered.lastTurnEnd)
    }

    @Test
    fun `a model the user did not pick is never silent`() {
        // The user picked Opus, but the recorded session ran Sonnet then Haiku.
        val final = replay("model-switch", ChatState(requestedModel = "claude-opus-5-5")).last()
        assertTrue(assertNotNull(final.modelWarning).contains("claude-haiku-5-5"))
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
    fun `entry ids stay unique after a retraction`() {
        var state = ChatState()
        state = reduce(state, cli("""{"type":"assistant","uuid":"a1","message":{"id":"m1","content":[{"type":"text","text":"x"}]}}"""))
        state = reduce(state, cli("""{"type":"system","subtype":"api_retry","attempt":1,"max_retries":3}"""))
        state = reduce(
            state,
            cli("""{"type":"system","subtype":"model_refusal_fallback","original_model":"a","fallback_model":"b","retracted_message_uuids":["a1"]}"""),
        )
        state = reduce(state, cli("""{"type":"system","subtype":"api_retry","attempt":2,"max_retries":3}"""))
        val ids = state.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
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
        assertEquals(
            "The claude CLI (Claude Code) is not signed in. Run `claude` in a terminal to sign in, then retry.",
            (state.entries.last() as ChatEntry.Notice).text,
        )
        state = reduce(state, cli("""{"type":"result","subtype":"error_during_execution","is_error":true,"errors":["boom"]}"""))
        assertEquals(NoticeLevel.ERROR, (state.entries.last() as ChatEntry.Notice).level)
        state = reduce(state, SessionEvent.Ended("The claude CLI exited with code 1.", isError = true))
        assertTrue(state.ended)
    }

    @Test
    fun `a notice from the host is shown and changes nothing else`() {
        val before = reduce(ChatState(), SessionEvent.UserSent("u1", "hi"))
        val text = "Permission mode auto is not supported yet; using default."
        val after = reduce(before, SessionEvent.HostNotice(NoticeLevel.INFO, text))
        assertEquals(ChatEntry.Notice("notice-0", NoticeLevel.INFO, text), after.entries.last())
        assertEquals(before.copy(entries = after.entries, noticeCount = 1, entryIndex = after.entryIndex), after)
        assertEquals(after.positions(), after.entryIndex)
    }

    @Test
    fun `a synthetic model on a line the CLI wrote itself is neither the active model nor a mismatch`() {
        var state = ChatState(requestedModel = "opus")
        state = reduce(state, cli("""{"type":"system","subtype":"init","session_id":"s","model":"claude-opus-5-5"}"""))
        // The CLI writes an API error as an assistant line of its own, naming a model like "<synthetic>".
        state = reduce(
            state,
            cli(
                """{"type":"assistant","uuid":"a1","isApiErrorMessage":true,""" +
                    """"message":{"id":"m1","model":"<synthetic>","content":[{"type":"text","text":"API Error: 500"}]}}""",
            ),
        )
        assertEquals("claude-opus-5-5", state.activeModel)
        assertNull(state.modelWarning)
        assertTrue(state.entries.none { it is ChatEntry.Notice })
        assertEquals("API Error: 500", state.text())
    }

    @Test
    fun `reduces 32,000 events in linear time`() {
        // A long session: 16,000 turns of a prompt and a one-block reply, decoded up front so that only reduce is timed, cold.
        val events = (1..16_000).flatMap { i ->
            listOf(
                SessionEvent.UserSent("u$i", "prompt $i"),
                cli(
                    """{"type":"assistant","uuid":"a$i",""" +
                        """"message":{"id":"m$i","model":"claude-sonnet-5-5","content":[{"type":"text","text":"reply $i"}]}}""",
                ),
            )
        }
        val final: ChatState
        val millis = measureTimeMillis { final = events.fold(ChatState()) { state, event -> reduce(state, event) } }
        assertEquals(32_000, final.entries.size)
        assertEquals(listOf("u16000", "m16000"), final.entries.takeLast(2).map { it.id })
        assertTrue(millis < 2_000, "reducing took $millis ms")
    }

    @Test
    fun `a state built without the reducer still finds its entries`() {
        // Tests and the missing-CLI screen build states by hand, with no entry index.
        val reply = ChatEntry.Assistant(
            id = "m1",
            messageId = "m1",
            parentToolUseId = null,
            model = "claude-sonnet-5-5",
            blocks = listOf(AssistantBlock.Text("one")),
            completedBlocks = 1,
            streaming = false,
            uuids = setOf("a1"),
        )
        var state = ChatState(entries = listOf(ChatEntry.User("u1", "hi"), reply))
        state = reduce(state, reply("a2", "m1", " two"))
        state = reduce(state, SessionEvent.UserSent("u2", "and?"))
        state = reduce(state, reply("a3", "m1", " three"))
        assertEquals(listOf("u1", "m1", "u2"), state.entries.map { it.id })
        assertEquals("one two three", state.text())
    }

    @Test
    fun `entries changed outside the reducer are found where they are now`() {
        var state = reduce(ChatState(), reply("a1", "m1", "one"))
        state = reduce(state, reply("a2", "m2", "two"))
        // The entries change, the index does not: it still has m2 at position 1.
        state = state.copy(entries = state.entries.drop(1))
        state = reduce(state, reply("a3", "m2", " more"))
        assertEquals(listOf("m2"), state.entries.map { it.id })
        assertEquals("two more", state.text())
    }

    @Test
    fun `a requested permission mode shows at once and the CLI confirms it`() {
        var state = reduce(ChatState(), SessionEvent.PermissionModeRequested(PermissionMode.PLAN))
        assertEquals(PermissionMode.PLAN, state.permissionMode)
        for (m in Fixtures.decode("permission-mode")) state = reduce(state, SessionEvent.Cli(m))
        assertEquals(PermissionMode.ACCEPT_EDITS, state.permissionMode)
    }

    @Test
    fun `a confirmed permission mode change leaves nothing waiting for an answer`() {
        // The fixture's own request ids: its success responses answer them, the first with plan, the second with acceptEdits.
        var state = reduce(ChatState(), SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "<request_id-21>"))
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.ACCEPT_EDITS, "<request_id-47>"))
        assertEquals(2, state.permissionModeChanges.size)
        for (m in Fixtures.decode("permission-mode")) state = reduce(state, SessionEvent.Cli(m))
        assertEquals(PermissionMode.ACCEPT_EDITS, state.permissionMode)
        assertEquals(emptyMap(), state.permissionModeChanges)
        assertTrue(state.entries.none { it is ChatEntry.Notice })
    }

    /** A `control_response` error for [requestId], in the envelope of docs/protocol.md 4.1 (no recorded rejection exists). */
    private fun rejection(requestId: String, error: String): SessionEvent =
        cli("""{"type":"control_response","response":{"subtype":"error","request_id":"$requestId","error":"$error"}}""")

    @Test
    fun `a rejected permission mode change goes back to the mode before it and says which change was rejected`() {
        var state = ChatState(permissionMode = PermissionMode.DEFAULT)
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "tandem-7"))
        assertEquals(PermissionMode.PLAN, state.permissionMode, "shown at once")
        state = reduce(state, rejection("tandem-7", "Cannot switch modes now"))
        assertEquals(PermissionMode.DEFAULT, state.permissionMode)
        assertEquals(emptyMap(), state.permissionModeChanges)
        val notice = state.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertEquals("The CLI rejected the switch to permission mode plan (Cannot switch modes now); it stays default.", notice.text)
    }

    @Test
    fun `a rejection after a later pick keeps the later pick`() {
        var state = ChatState(permissionMode = PermissionMode.DEFAULT)
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "tandem-7"))
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.ACCEPT_EDITS, "tandem-8"))
        state = reduce(state, rejection("tandem-7", "busy"))
        assertEquals(PermissionMode.ACCEPT_EDITS, state.permissionMode)
        assertEquals(setOf("tandem-8"), state.permissionModeChanges.keys)
        assertTrue((state.entries.last() as ChatEntry.Notice).text.startsWith("The CLI rejected the switch to permission mode plan"))
    }

    @Test
    fun `two picks both rejected go back to the mode the CLI kept, in either order`() {
        for (order in listOf(listOf("tandem-1", "tandem-2"), listOf("tandem-2", "tandem-1"))) {
            var state = ChatState(permissionMode = PermissionMode.DEFAULT)
            state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "tandem-1"))
            state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.ACCEPT_EDITS, "tandem-2"))
            for (id in order) state = reduce(state, rejection(id, "busy"))
            assertEquals(PermissionMode.DEFAULT, state.permissionMode, "rejected in the order $order")
            assertEquals(emptyMap(), state.permissionModeChanges)
        }
    }

    @Test
    fun `an early rejection keeps a later pick still waiting for its answer, and that one's rejection goes back to the kept mode`() {
        var state = ChatState(permissionMode = PermissionMode.DEFAULT)
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "tandem-1"))
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.ACCEPT_EDITS, "tandem-2"))
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "tandem-3"))
        state = reduce(state, rejection("tandem-1", "busy"))
        assertEquals(PermissionMode.PLAN, state.permissionMode, "tandem-3 still waits")
        assertTrue((state.entries.last() as ChatEntry.Notice).text.endsWith("; it stays default."), "what the CLI is in")
        state = reduce(state, cli("""{"type":"control_response","response":{"subtype":"success","request_id":"tandem-2","response":{"mode":"acceptEdits"}}}"""))
        state = reduce(state, rejection("tandem-3", "busy"))
        assertEquals(PermissionMode.ACCEPT_EDITS, state.permissionMode, "the CLI took tandem-2 and kept it")
    }

    @Test
    fun `a rejected request that is no mode change keeps the general notice`() {
        val state = reduce(ChatState(permissionMode = PermissionMode.PLAN), rejection("tandem-9", "Unknown subtype"))
        assertEquals(PermissionMode.PLAN, state.permissionMode)
        assertEquals("The CLI rejected a request: Unknown subtype", (state.entries.last() as ChatEntry.Notice).text)
    }

    @Test
    fun `a mode change still unanswered when the process ends is dropped with the settled state`() {
        val state = reduce(ChatState(), SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "tandem-7"))
        assertEquals(emptyMap(), state.settled().permissionModeChanges)
        assertEquals(emptyMap(), reduce(state, SessionEvent.Ended("exited", isError = false)).permissionModeChanges)
    }

    @Test
    fun `the initialize response fills the model list`() {
        val state = Fixtures.decode("permission").fold(ChatState()) { s, m -> reduce(s, SessionEvent.Cli(m)) }
        assertEquals("default", state.models.first().value)
        assertTrue(state.models.size > 1)
    }

    @Test
    fun `model entries without a value are skipped and a missing display name falls back to the value`() {
        val state = reduce(
            ChatState(),
            cli(
                """{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"models":[""" +
                    """{"value":"opus","displayName":"Opus","description":"Most capable"},{"value":"haiku"},""" +
                    """{"displayName":"No value"},"sonnet"]}}}""",
            ),
        )
        assertEquals(listOf(ModelOption("opus", "Opus", "Most capable"), ModelOption("haiku", "haiku", null)), state.models)
    }

    @Test
    fun `odd model lists are read as far as they make sense`() {
        val earlier = listOf(ModelOption("default", "Default", null))
        fun modelsAfter(models: String): List<ModelOption> = reduce(
            ChatState(models = earlier),
            cli("""{"type":"control_response","response":{"subtype":"success","request_id":"r1","response":{"models":$models}}}"""),
        ).models
        assertEquals(emptyList(), modelsAfter("[]"), "an empty list: the CLI offers none")
        assertEquals(earlier, modelsAfter("\"opus\""), "not a list: no model list at all")
        assertEquals(earlier, modelsAfter("""{"value":"opus"}"""))
        assertEquals(emptyList(), modelsAfter("""[{"value":5},{"value":null},{"value":["opus"]}]"""), "a value that is no string")
        assertEquals(
            listOf(ModelOption("opus", "opus", null), ModelOption("haiku", "Haiku", "Fast")),
            modelsAfter("""[{"value":"opus","displayName":7,"description":{"text":"x"}},{"value":"haiku","displayName":"Haiku","description":"Fast"}]"""),
            "a display name or description that is no string counts as missing",
        )
    }

    @Test
    fun `responses without a model list leave it as it is`() {
        val models = listOf(ModelOption("default", "Default", null))
        val acknowledged = reduce(
            ChatState(models = models),
            cli("""{"type":"control_response","response":{"subtype":"success","request_id":"r2","response":{"mode":"plan"}}}"""),
        )
        assertEquals(models, acknowledged.models)
        val rejected = reduce(
            acknowledged,
            cli("""{"type":"control_response","response":{"subtype":"error","request_id":"r3","error":"Unknown model"}}"""),
        )
        assertEquals(models, rejected.models)
        assertEquals("The CLI rejected a request: Unknown model", (rejected.entries.single() as ChatEntry.Notice).text)
    }

    @Test
    fun `the entry index stays in step through every fixture`() {
        for (fixture in Fixtures.names()) {
            for (state in replay(fixture)) assertEquals(state.positions(), state.entryIndex, fixture)
        }
    }

    @Test
    fun `the entry index stays in step when replies are retracted or superseded`() {
        var state = reduce(ChatState(), reply("a1", "m1", "refused"))
        state = reduce(state, SessionEvent.UserSent("u1", "next"))
        state = reduce(state, reply("a2", "m2", "first try"))
        state = reduce(
            state,
            cli(
                """{"type":"system","subtype":"model_refusal_fallback","original_model":"a","fallback_model":"b",""" +
                    """"retracted_message_uuids":["a1"]}""",
            ),
        )
        assertEquals(listOf("u1", "m2", "notice-0"), state.entries.map { it.id })
        assertEquals(state.positions(), state.entryIndex)
        state = reduce(state, reply("a3", "m3", "second try", fields = """"supersedes":["a2"],"""))
        assertEquals(listOf("u1", "notice-0", "m3"), state.entries.map { it.id })
        assertEquals(state.positions(), state.entryIndex)
        // A later line of a reply that moved up still lands in its own entry.
        state = reduce(state, reply("a4", "m3", ", done"))
        assertEquals(listOf("u1", "notice-0", "m3"), state.entries.map { it.id })
        assertEquals("second try, done", state.text())
    }

    @Test
    fun `the entry index is rebuilt after a removal that appends nothing`() {
        var state = reduce(ChatState(), reply("a1", "m1", "one"))
        state = reduce(state, SessionEvent.UserSent("u1", "next"))
        state = reduce(state, reply("a2", "m2", "two"))
        // This line supersedes m1 and adds a block to m2, which moves up a place.
        state = reduce(state, reply("a3", "m2", " more", fields = """"supersedes":["a1"],"""))
        assertEquals(listOf("u1", "m2"), state.entries.map { it.id })
        assertEquals("two more", state.text())
        assertEquals(state.positions(), state.entryIndex)
    }
}
