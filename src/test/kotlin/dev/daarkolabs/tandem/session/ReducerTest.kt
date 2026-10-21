package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        assertEquals(NoticeKind.INTERRUPTED, notices.single().kind)
        assertEquals(TurnEnd.INTERRUPTED, final.entries.filterIsInstance<ChatEntry.TurnEnded>().single().end)
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
        // A clean exit during a turn ends it too, without its result: it failed, and the chat says so as an error.
        val exited = reduce(reduce(one, SessionEvent.UserSent("u2", "more")), SessionEvent.Ended("The claude CLI exited.", isError = false))
        assertEquals(2, exited.turnsEnded)
        assertEquals(TurnEnd.FAILED, exited.lastTurnEnd)
        val said = exited.entries.last() as ChatEntry.Notice
        assertEquals(NoticeLevel.ERROR, said.level)
        assertEquals("The claude CLI exited. The turn ended without its result.", said.text)
        // A crash says so before the CLI's last words.
        val crashedSaid = reduce(
            reduce(one, SessionEvent.UserSent("u2", "more")),
            SessionEvent.Ended("The claude CLI exited with code 3.\nboom", isError = true),
        ).entries.last() as ChatEntry.Notice
        assertEquals("The claude CLI exited with code 3. The turn ended without its result.\nboom", crashedSaid.text)
        // A process Tandem itself ended (killed as the IDE exits) ends its turn as interrupted, not failed: the chat says nothing more.
        val killed = reduce(
            reduce(one, SessionEvent.UserSent("u2", "more")),
            SessionEvent.Ended("The claude CLI exited with code 137.", isError = true, endedByHost = true),
        )
        assertEquals(2, killed.turnsEnded)
        assertEquals(TurnEnd.INTERRUPTED, killed.lastTurnEnd)
        assertEquals("The claude CLI exited with code 137.", (killed.entries.last() as ChatEntry.Notice).text)
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
        assertEquals(ChatEntry.Notice("notice-0", NoticeLevel.INFO, "overloaded · Retrying in 4s · attempt 2/10"), state.entries.last())
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
    fun `a recorded rejection of bypassPermissions goes back to the mode before it`() {
        // Recorded: set_permission_mode bypassPermissions to a CLI not launched with --dangerously-skip-permissions.
        var state = ChatState()
        for (line in Fixtures.lines("permission-mode-rejected")) {
            val message = Ndjson.decode(line)
            if (message is CliMessage.ControlResponse && !message.isSuccess) {
                state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.BYPASS_PERMISSIONS, message.requestId))
                assertEquals(PermissionMode.BYPASS_PERMISSIONS, state.permissionMode, "shown at once")
            }
            state = reduce(state, SessionEvent.Cli(message))
        }
        assertEquals(PermissionMode.DEFAULT, state.permissionMode)
        assertEquals(emptyMap(), state.permissionModeChanges)
        val notice = state.entries.filterIsInstance<ChatEntry.Notice>().single { it.level == NoticeLevel.ERROR }
        assertEquals(
            "The CLI rejected the switch to permission mode bypassPermissions (Cannot set permission mode to bypassPermissions " +
                "because the session was not launched with --dangerously-skip-permissions); it stays default.",
            notice.text,
        )
        assertEquals(2, state.turnsEnded, "both turns ran")
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
    fun `the initialize models keep resolvedModel, supportsAutoMode and effort levels`() {
        val models = reduce(ChatState(), SessionEvent.Cli(Fixtures.decode("agent-sync")[1])).models
        val levels = listOf("low", "medium", "high", "xhigh", "max")
        assertEquals(ModelOption("default", "Default (recommended)", "Fable 5.1", "claude-fable-5-1", true, levels), models[0])
        val haiku = models.single { it.displayName == "Haiku 4.5" }
        assertEquals("claude-haiku-4-5-20251001", haiku.value)
        assertFalse(haiku.supportsAutoMode)
        assertEquals(emptyList(), haiku.effortLevels)
    }

    @Test
    fun `a model pick is pending until the next init`() {
        var state = reduce(ChatState(), SessionEvent.ModelRequested("opus"))
        assertTrue(state.modelSwitchPending)
        state = reduce(state, reply("a1", "m1", "x"))
        state = reduce(state, cli("""{"type":"assistant","uuid":"a2","message":{"id":"m2","model":"claude-sonnet-5-5","content":[{"type":"text","text":"x"}]}}"""))
        assertTrue(state.modelSwitchPending, "an assistant line keeps it")
        state = reduce(state, SessionEvent.Cli(Fixtures.decode("agent-sync")[5]))
        assertFalse(state.modelSwitchPending, "init clears it")

        state = reduce(state, SessionEvent.ModelRequested("opus"))
        fun fallback(scope: String): SessionEvent = cli(
            """{"type":"system","subtype":"model_refusal_fallback","original_model":"claude-opus-5-5","fallback_model":"claude-sonnet-5-5",""" +
                """"scope":"$scope","retracted_message_uuids":[]}""",
        )
        state = reduce(state, fallback("local"))
        assertTrue(state.modelSwitchPending, "a local fallback answers one request only")
        state = reduce(state, fallback("session"))
        assertFalse(state.modelSwitchPending, "the session switched")
    }

    @Test
    fun `apply_flag_settings success keeps the effort, an error restores it with a notice`() {
        val requested = reduce(ChatState(effort = "medium"), SessionEvent.EffortRequested("high", "r1"))
        assertEquals("high", requested.effort)
        assertEquals(mapOf("r1" to EffortChange("high", "medium")), requested.effortChanges)

        val kept = reduce(requested, cli("""{"type":"control_response","response":{"subtype":"success","request_id":"r1"}}"""))
        assertEquals("high", kept.effort)
        assertEquals(emptyMap(), kept.effortChanges)
        assertTrue(kept.entries.none { it is ChatEntry.Notice })

        val error = "CLAUDE_CODE_EFFORT_LEVEL overrides effort for this session"
        val restored = reduce(requested, rejection("r1", error))
        assertEquals("medium", restored.effort)
        assertEquals(emptyMap(), restored.effortChanges)
        assertEquals(ChatEntry.Notice("notice-0", NoticeLevel.ERROR, "Effort not changed: $error"), restored.entries.last())
    }

    @Test
    fun `a rejected effort change hands its previous level to the next one waiting`() {
        // From medium: high (r1), then max (r2), both sent before the CLI answers either.
        val sent = reduce(
            reduce(ChatState(effort = "medium"), SessionEvent.EffortRequested("high", "r1")),
            SessionEvent.EffortRequested("max", "r2"),
        )
        assertEquals(mapOf("r1" to EffortChange("high", "medium"), "r2" to EffortChange("max", "high")), sent.effortChanges)

        // r1 refused: max still shows, and r2 now starts from medium, where the CLI stayed.
        val firstRefused = reduce(sent, rejection("r1", "no"))
        assertEquals("max", firstRefused.effort)
        assertEquals(mapOf("r2" to EffortChange("max", "medium")), firstRefused.effortChanges)
        assertEquals(ChatEntry.Notice("notice-0", NoticeLevel.ERROR, "Effort not changed: no"), firstRefused.entries.last())

        // Both refused: the CLI runs at medium, and so does the footer.
        val bothRefused = reduce(firstRefused, rejection("r2", "no"))
        assertEquals("medium", bothRefused.effort)
        assertEquals(emptyMap(), bothRefused.effortChanges)

        // r1 refused, r2 accepted: the CLI runs at max.
        val secondAccepted = reduce(firstRefused, cli("""{"type":"control_response","response":{"subtype":"success","request_id":"r2"}}"""))
        assertEquals("max", secondAccepted.effort)
        assertEquals(emptyMap(), secondAccepted.effortChanges)

        // r1 accepted, r2 refused: the CLI runs at high.
        val firstAccepted = reduce(sent, cli("""{"type":"control_response","response":{"subtype":"success","request_id":"r1"}}"""))
        assertEquals("high", reduce(firstAccepted, rejection("r2", "no")).effort)
    }

    @Test
    fun `a rejected effort change leaves an effort set since then`() {
        val requested = reduce(ChatState(effort = "medium"), SessionEvent.EffortRequested("high", "r1"))
        val replaced = reduce(requested, SessionEvent.EffortRequested("low"))
        assertEquals("low", reduce(replaced, rejection("r1", "no")).effort)
    }

    @Test
    fun `settled clears the effort changes`() {
        val state = reduce(ChatState(effort = "low"), SessionEvent.EffortRequested("max", "r2"))
        assertEquals(emptyMap(), state.settled().effortChanges)
        val stopped = reduce(ChatState(effort = "low"), SessionEvent.EffortRequested("high"))
        assertEquals("high", stopped.effort)
        assertEquals(emptyMap(), stopped.effortChanges)
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

    /** A state with a turn open, as a send leaves it: no fixture line opens one. */
    private fun sent(): ChatState = reduce(ChatState(), SessionEvent.UserSent("u1", "prompt", atMillis = 0L))

    private fun ChatState.toolResults(): List<ChatEntry.ToolResult> = entries.filterIsInstance<ChatEntry.ToolResult>()

    private fun ChatState.turnEnds(): List<ChatEntry.TurnEnded> = entries.filterIsInstance<ChatEntry.TurnEnded>()

    /** The last thinking block of the last assistant entry that has one. */
    private fun ChatState.thinking(): AssistantBlock.Thinking? =
        assistants().lastOrNull { entry -> entry.blocks.any { it is AssistantBlock.Thinking } }?.blocks?.filterIsInstance<AssistantBlock.Thinking>()?.last()

    /** The last tool call of the last assistant entry that has one. */
    private fun ChatState.toolUse(): AssistantBlock.ToolUse? =
        assistants().lastOrNull { entry -> entry.blocks.any { it is AssistantBlock.ToolUse } }?.blocks?.filterIsInstance<AssistantBlock.ToolUse>()?.last()

    // spec: ask-single: the question is a pending permission before the block stops and the answer links to the call
    @Test
    fun `ask-single - the question is a pending permission before the block stops and the answer links to the call`() {
        val states = replay("ask-single")
        val asked = states[22].pendingPermissions.single()
        assertEquals("AskUserQuestion", asked.toolName)
        assertTrue(asked.requiresUserInteraction)
        assertTrue(states[22].assistants().last().streaming, "the block has not stopped yet")
        val answer = states[26].toolResults().single { it.toolUseId == "<id-12>" }
        assertTrue(answer.result!!.jsonObject["answers"] is JsonObject, answer.toString())
    }

    // spec: ask-deny: a cancelled question is a rejected result and the turn still completes
    @Test
    fun `ask-deny - a cancelled question is a rejected result and the turn still completes`() {
        val final = replay("ask-deny").last()
        val cancelled = final.toolResults().single()
        assertTrue(cancelled.isError)
        assertEquals("reject", cancelled.decision!!.decision)
        val end = final.turnEnds().single()
        assertEquals(TurnEnd.COMPLETED, end.end)
        assertEquals(1, end.denials.size)
    }

    @Test
    fun `a tool result before message_delta lands on the streaming message`() {
        val states = replay("ask-multi-array")
        assertTrue(states[30].toolResults().any { it.toolUseId == "<id-11>" })
        assertTrue(states[30].assistants().single().streaming)
        // message_delta (31) and message_stop (32): the message no longer streams once both are in.
        assertTrue(states[32].assistants().none { it.streaming })
        assertEquals(1, states[32].toolResults().size)
    }

    // spec: plan-approve: the status line after approval switches the mode before the tool result
    @Test
    fun `plan-approve - the status line after approval switches the mode before the tool result`() {
        val states = replay("plan-approve")
        assertEquals(PermissionMode.PLAN, states[89].permissionMode)
        assertEquals(PermissionMode.ACCEPT_EDITS, states[90].permissionMode)
        assertTrue(states[90].toolResults().none { it.toolUseId == "<id-92>" })
        assertNotNull(states[91].toolResults().single { it.toolUseId == "<id-92>" }.result!!.jsonObject["plan"])
    }

    // spec: plan-deny: a deny keeps plan mode and the second request is pending again
    @Test
    fun `plan-deny - a deny keeps plan mode and the second request is pending again`() {
        val requests = mapOf(79 to "ExitPlanMode", 143 to "ExitPlanMode", 175 to "Write")
        var state = ChatState()
        for ((index, message) in Fixtures.decode("plan-deny").withIndex()) {
            val line = index + 1
            state = reduce(state, SessionEvent.Cli(message))
            requests[line]?.let { tool ->
                assertEquals(tool, state.pendingPermissions.single().toolName, "line $line")
                state = reduce(state, SessionEvent.PermissionAnswered(state.pendingPermissions.single().requestId))
            }
            if (line in 5 until 145) assertEquals(PermissionMode.PLAN, state.permissionMode, "line $line")
            if (line == 145) assertEquals(PermissionMode.DEFAULT, state.permissionMode)
        }
    }

    // spec: think-display-summarized: thinking text accumulates then the complete block brings the duration
    @Test
    fun `think-display-summarized - thinking text accumulates then the complete block brings the duration`() {
        val messages = Fixtures.decode("think-display-summarized")
        val states = replay("think-display-summarized")
        val deltas = messages.withIndex().filter { (_, m) ->
            ((m as? CliMessage.StreamEvent)?.event as? StreamEvent.ContentBlockDelta)?.delta is Delta.Thinking
        }.map { it.index + 1 }
        assertEquals(11, deltas.size)
        for (line in deltas) {
            val before = states[line - 1].thinking()!!.text
            val after = states[line].thinking()!!.text
            assertTrue(after.length > before.length && after.startsWith(before), "line $line: '$before' -> '$after'")
            assertNull(states[line].thinking()!!.durationMs, "still a draft at line $line")
        }
        val complete = states[34].thinking()!!
        assertTrue(complete.text.startsWith("47×83"), complete.text)
        assertEquals(1991L, complete.durationMs)
    }

    @Test
    fun `a thinking block without thinking_duration_ms takes its duration from the host clock`() {
        val lines = Fixtures.lines("think-default").map { line ->
            val json = Json.parseToJsonElement(line).jsonObject
            if ("thinking_duration_ms" in json) JsonObject(json - "thinking_duration_ms").toString() else line
        }
        // The content_block_start (line 8) at 1000, the complete line (16) at 3000.
        val final = lines.foldIndexed(ChatState()) { index, state, line ->
            reduce(state, SessionEvent.Cli(Ndjson.decode(line), atMillis = if (index + 1 < 16) 1000L else 3000L))
        }
        val thinking = final.thinking()!!
        assertEquals(2000L, thinking.durationMs)
        assertEquals(1000L, thinking.startedAtMillis)
        assertEquals(3000L, thinking.completedAtMillis)
    }

    // spec: think-default: the thinking estimate rises with thinking_tokens and clears on message_delta
    @Test
    fun `think-default - the thinking estimate rises with thinking_tokens and clears on message_delta`() {
        val states = replay("think-default", sent())
        assertEquals(listOf(0, 50, 150, 285), states.take(45).map { it.turn!!.thinkingEstimate }.distinct())
        assertEquals(50, states[9].turn!!.thinkingEstimate)
        assertEquals(150, states[11].turn!!.thinkingEstimate)
        assertEquals(285, states[14].turn!!.thinkingEstimate)
        // The draft block carries the estimate too: the delta's own (line 12: 100), then the thinking_tokens line's (14: 285).
        assertEquals(100, states[12].thinking()!!.estimatedTokens)
        assertEquals(285, states[14].thinking()!!.estimatedTokens)
        val delta = (Fixtures.decode("think-default")[44] as CliMessage.StreamEvent).event as StreamEvent.MessageDelta
        assertEquals(0, states[45].turn!!.thinkingEstimate)
        assertEquals(delta.outputTokens, states[45].turn!!.outputTokens)
        assertEquals(324, states[45].turn!!.outputTokens)
    }

    // spec: tools-bash: results keep content, result and decision
    @Test
    fun `tools-bash - results keep content, result and decision`() {
        val states = replay("tools-bash", sent())
        assertEquals("subcommandResults", states[20].pendingPermissions.single().decisionReasonType)
        val (first, second) = states.last().toolResults()
        assertEquals(PermissionDecision("accept", "user_temporary", null, null), first.decision)
        assertNotNull(first.result!!.jsonObject["stdout"])
        assertNotNull(first.content)
        assertTrue(second.isError)
        assertTrue(second.result is JsonPrimitive, second.result.toString())
        val bash = states[18].toolUse()!!
        assertEquals("Bash", bash.name)
        assertEquals(bash.id, states[18].turn!!.lastToolUseId)
        assertEquals(1, states[18].turn!!.toolCount)
        assertEquals(2, states[34].turn!!.toolCount)
        assertEquals(states[34].toolUse()!!.id, states[34].turn!!.lastToolUseId)
    }

    // spec: permission: a denied call records the reject decision
    @Test
    fun `permission - a denied call records the reject decision`() {
        val denied = replay("permission")[56].toolResults().single { it.toolUseId == "<id-52>" }
        assertEquals("reject", denied.decision!!.decision)
    }

    // spec: agent-sync: task lines fill tasks and subagent entries carry the parent id
    @Test
    fun `agent-sync - task lines fill tasks and subagent entries carry the parent id`() {
        val states = replay("agent-sync")
        val final = states.last()
        val task = final.tasks.getValue("<task_id-42>")
        assertEquals("completed", task.status)
        assertNotNull(task.summary)
        assertEquals(2, task.toolUses)
        assertEquals("<id-12>", task.toolUseId)
        val nested = final.entries.filter {
            (it as? ChatEntry.Assistant)?.parentToolUseId == "<id-12>" || (it as? ChatEntry.ToolResult)?.parentToolUseId == "<id-12>"
        }
        assertEquals(4, nested.size, nested.toString())
        for (entry in nested) {
            val agent = (entry as? ChatEntry.Assistant)?.agentId ?: (entry as ChatEntry.ToolResult).agentId
            assertEquals("<task_id-42>", agent, entry.toString())
        }
        assertEquals("Running wc -l seed.txt && tail -c1 seed.txt | xxd", states[41].tasks.getValue("<task_id-42>").description)
        assertEquals("<task_id-42>", states[43].pendingPermissions.single().agentId)
    }

    // spec: agent-bash-task: a bash task is not stored
    @Test
    fun `agent-bash-task - a bash task is not stored`() {
        for (state in replay("agent-bash-task")) assertTrue(state.tasks.isEmpty(), state.tasks.toString())
    }

    // spec: misc-state-action: the pending permission keeps blocked_path, display name and suggestions
    @Test
    fun `misc-state-action - the pending permission keeps blocked_path, display name and suggestions`() {
        val asked = replay("misc-state-action")[21].pendingPermissions.single()
        assertEquals("<path>", asked.blockedPath)
        assertEquals("Bash", asked.displayName)
        assertEquals(3, asked.suggestions.size)
        assertFalse(asked.requiresUserInteraction)
    }

    @Test
    fun `a streaming tool_use has no input until its complete line`() {
        val states = replay("permission")
        val started = states[11].toolUse()!!
        assertNull(started.input)
        assertEquals("", started.partialInput)
        assertTrue(states[18].toolUse()!!.partialInput.length > states[13].toolUse()!!.partialInput.length)
        assertNull(states[18].toolUse()!!.input)
        assertEquals("touch tandem-fixture.txt", states[19].toolUse()!!.input!!["command"]!!.jsonPrimitive.content)
    }

    // spec: agent-forwarded: the forwarded text is a subagent assistant entry
    @Test
    fun `agent-forwarded - the forwarded text is a subagent assistant entry`() {
        val forwarded = replay("agent-forwarded").last().assistants().single { entry ->
            entry.agentId != null && entry.blocks.any { it == AssistantBlock.Text("7") }
        }
        assertEquals("<id-12>", forwarded.parentToolUseId)
        assertEquals("general-purpose", forwarded.subagentType)
        assertEquals("Count lines in seed.txt", forwarded.taskDescription)
    }

    // spec: misc-image: an image result keeps its content array
    @Test
    fun `misc-image - an image result keeps its content array`() {
        val image = replay("misc-image").last().toolResults().first { it.toolUseId == "<id-12>" }
        assertTrue(image.content is JsonArray, image.content.toString())
    }

    @Test
    fun `an image in assistant content is an Other block`() {
        val image = """{"type":"image","source":{"type":"base64","media_type":"image/png","data":"AAAA"}}"""
        val complete = reduce(ChatState(), cli("""{"type":"assistant","uuid":"a1","message":{"id":"m1","content":[$image]}}"""))
        assertEquals(listOf<AssistantBlock>(AssistantBlock.Other("image")), complete.assistants().single().blocks)

        var draft = reduce(ChatState(), cli("""{"type":"stream_event","event":{"type":"message_start","message":{"id":"m2"}}}"""))
        draft = reduce(draft, cli("""{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":$image}}"""))
        assertEquals(listOf<AssistantBlock>(AssistantBlock.Other("image")), draft.assistants().single().blocks)
    }

    @Test
    fun `a user message keeps its images on the entry`() {
        val images = listOf(UserImage("u1#0", "image/png", "AAAA"))
        val state = reduce(ChatState(), SessionEvent.UserSent("u1", "look", 5L, images))
        assertEquals(ChatEntry.User("u1", "look", 5L, listOf(UserImage("u1#0", "image/png", "AAAA"))), state.entries.single())
    }

    @Test
    fun `UserSent records the send time and result appends a TurnEnded entry`() {
        val initial = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 1234))
        assertEquals(1234L, initial.turn!!.startedAtMillis)
        assertEquals(TurnProgress("u1", 1234L, 0, 0, null, 0), initial.turn)
        assertEquals(1234L, (initial.entries.single() as ChatEntry.User).sentAtMillis)
        val final = replay("plain-reply", initial).last()
        val result = Fixtures.decode("plain-reply").filterIsInstance<CliMessage.Result>().single()
        val end = final.entries.last() as ChatEntry.TurnEnded
        assertEquals(TurnEnd.COMPLETED, end.end)
        assertEquals(result.durationMs, end.durationMs)
        assertEquals(result.outputTokens, end.outputTokens)
        assertNull(end.error)
        assertNull(final.turn)
        assertEquals(final.positions(), final.entryIndex)
    }

    @Test
    fun `Ended during a turn appends a FAILED turn end with the host duration`() {
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 1000))
        state = reduce(state, SessionEvent.Ended("exit 2", isError = true, atMillis = 4000))
        val end = state.turnEnds().single()
        assertEquals(TurnEnd.FAILED, end.end)
        assertEquals(3000L, end.durationMs)
        assertEquals("exit 2", end.error)
        assertEquals(emptyList(), end.denials)
        val error = state.entries.indexOfFirst { it is ChatEntry.Notice && it.level == NoticeLevel.ERROR }
        assertTrue(state.entries.indexOf(end) in 0 until error, state.entries.toString())
        assertNull(state.turn)
    }

    @Test
    fun `Ended by the host during a turn appends an INTERRUPTED turn end`() {
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 1000))
        state = reduce(state, SessionEvent.Ended("The claude CLI exited.", isError = false, endedByHost = true, atMillis = 4000))
        val end = state.turnEnds().single()
        assertEquals(TurnEnd.INTERRUPTED, end.end)
        assertEquals(3000L, end.durationMs)
        assertNull(end.error)
        assertEquals(NoticeLevel.INFO, (state.entries.last() as ChatEntry.Notice).level)
        assertNull(state.turn)
    }

    @Test
    fun `turn end ids stay unique across a host stop and a later result`() {
        val result = Fixtures.lines("plain-reply").first { "\"type\":\"result\"" in it }
        var state = reduce(ChatState(), SessionEvent.UserSent("u1", "hi", atMillis = 1000))
        state = reduce(state, SessionEvent.Ended("The claude CLI exited.", isError = false, endedByHost = true, atMillis = 2000))
        state = reduce(state, SessionEvent.UserSent("u2", "again", atMillis = 3000))
        state = reduce(state, cli(result))
        val ends = state.turnEnds()
        assertEquals(2, ends.size)
        assertEquals(2, ends.map { it.id }.distinct().size, ends.toString())
        assertEquals(state.entries.size, state.entryIndex.size)
        assertEquals(state.positions(), state.entryIndex)
    }

    // spec: invalid-model: the assistant error line sets the turn error and the failed result carries it
    @Test
    fun `invalid-model - the assistant error line sets the turn error and the failed result carries it`() {
        val states = replay("invalid-model", sent())
        assertEquals("API Error: 404 model_not_found", states[4].turn!!.lastError)
        val end = states.last().turnEnds().single()
        assertEquals(TurnEnd.FAILED, end.end)
        assertEquals("API Error: 404 model_not_found", end.error)
    }

    @Test
    fun `an api_retry line yields the reference wording`() {
        fun retry(fields: String): ChatEntry.Notice =
            reduce(ChatState(), cli("""{"type":"system","subtype":"api_retry",$fields}""")).entries.single() as ChatEntry.Notice
        assertEquals(
            ChatEntry.Notice("notice-0", NoticeLevel.INFO, "529 overloaded · Retrying in 4s · attempt 2/10"),
            retry(""""attempt":2,"max_retries":10,"retry_delay_ms":4000,"error":"overloaded","error_status":529"""),
        )
        assertEquals(
            "overloaded · Retrying in 4s · attempt 2/10",
            retry(""""attempt":2,"max_retries":10,"retry_delay_ms":4000,"error":"overloaded"""").text,
        )
        assertEquals("529 · Retrying in 4s", retry(""""retry_delay_ms":4999,"error_status":529""").text)
        // The lines `entry ids stay unique after a retraction` uses: no error, no delay.
        for (fields in listOf(""""attempt":1,"max_retries":3""", """"attempt":2,"max_retries":3""")) {
            val text = retry(fields).text
            assertFalse("null" in text, text)
            assertEquals("API error · attempt ${fields.substringAfter(":").substringBefore(",")}/3", text)
        }
        assertEquals("API error", retry(""""attempt":1""").text)
    }

    @Test
    fun `init stores cwd`() {
        assertEquals("<path>", replay("tools-read")[5].cwd)
        assertNull(replay("tools-read")[4].cwd)
    }

    /** The decoded line [line] (1-based) of fixture [name]. */
    private fun at(name: String, line: Int): CliMessage = Ndjson.decode(Fixtures.lines(name)[line - 1])

    private fun inputTokensAt(name: String, line: Int): Long? =
        ((at(name, line) as CliMessage.StreamEvent).event as StreamEvent.MessageStart).inputTokens

    // polish spec 2.3, 3.2: the context ring's tokens and window
    @Test
    fun `message_start sets the context tokens and result sets the window`() {
        val states = replay("misc-image")
        assertEquals(31_294L, inputTokensAt("misc-image", 8)) // 2 + 4754 + 26538
        assertEquals(ContextUsage(inputTokensAt("misc-image", 8), null), states[8].context)
        assertEquals(31_630L, inputTokensAt("misc-image", 52)) // 2 + 220 + 31408
        assertEquals(ContextUsage(inputTokensAt("misc-image", 52), 1_000_000L), states.last().context)
    }

    @Test
    fun `a subagent's message_start does not touch the context`() {
        val initial = ChatState(context = ContextUsage(10, 200_000L))
        fun start(parent: String?): SessionEvent = SessionEvent.Cli(
            CliMessage.StreamEvent(StreamEvent.MessageStart("m", null, null, 5L), parentToolUseId = parent, apiMessageId = null, raw = buildJsonObject {}),
        )
        assertEquals(ContextUsage(10, 200_000L), reduce(initial, start("toolu_1")).context)
        // The same event on the main thread sets the tokens: the parent guard, not a missing usage, is what keeps them above.
        assertEquals(ContextUsage(5, 200_000L), reduce(initial, start(null)).context)
        // A message_start without usage changes nothing either.
        val noUsage = SessionEvent.Cli(
            CliMessage.StreamEvent(StreamEvent.MessageStart("m", null, null, null), parentToolUseId = null, apiMessageId = null, raw = buildJsonObject {}),
        )
        assertEquals(ContextUsage(10, 200_000L), reduce(initial, noUsage).context)
    }

    @Test
    fun `a result without modelUsage keeps the context`() {
        val state = reduce(ChatState(context = ContextUsage(10, 200_000L)), cli("""{"type":"result","subtype":"success"}"""))
        assertEquals(ContextUsage(10, 200_000L), state.context)
    }

    @Test
    fun `a result before any message_start knows the window only`() {
        assertEquals(ContextUsage(null, 1_000_000L), reduce(ChatState(), SessionEvent.Cli(at("misc-image", 66))).context)
    }

    // Review Focus 3: a mid-turn fallback leaves the active model out of modelUsage.
    @Test
    fun `the window falls back to the largest listed when the active model is not in modelUsage`() {
        val opus = reduce(ChatState(activeModel = "claude-opus-5-5"), SessionEvent.Cli(at("misc-image", 66)))
        assertEquals(1_000_000L, opus.context?.window)
        val result = cli("""{"type":"result","subtype":"success","modelUsage":{"a":{"contextWindow":200000},"b":{"contextWindow":1000000}}}""")
        assertEquals(ContextUsage(null, 1_000_000L), reduce(ChatState(activeModel = "c"), result).context)
        // The active model's own window wins over a larger one.
        assertEquals(ContextUsage(null, 200_000L), reduce(ChatState(activeModel = "a"), result).context)
    }

    @Test
    fun `a line with two tool results attaches the object to neither`() {
        val line = """{"type":"user","message":{"role":"user","content":[""" +
            """{"type":"tool_result","tool_use_id":"t1","content":"one"},{"type":"tool_result","tool_use_id":"t2","content":"two"}]},""" +
            """"tool_use_result":{"stdout":"one"}}"""
        val results = reduce(ChatState(), cli(line)).toolResults()
        assertEquals(listOf("t1", "t2"), results.map { it.toolUseId })
        for (result in results) {
            assertNull(result.result, result.toString())
            assertNotNull(result.content)
        }
    }

    @Test
    fun `an assistant entry carries the time of its first line`() {
        val messages = Fixtures.decode("plain-reply")
        val state = messages.foldIndexed(ChatState()) { index, state, message -> reduce(state, SessionEvent.Cli(message, atMillis = index * 100L)) }
        val start = messages.indexOfFirst { it is CliMessage.StreamEvent && it.event is StreamEvent.MessageStart }
        assertTrue(start > 0)
        // Stamped at its message_start, and kept when its complete line comes later.
        assertEquals(start * 100L, state.assistants().single().atMillis)
        // A complete line without a draft stamps its own time.
        val complete = reduce(ChatState(), SessionEvent.Cli(Ndjson.decode(REPLY_LINE), atMillis = 1_234L))
        assertEquals(1_234L, complete.assistants().single().atMillis)
    }

    @Test
    fun `an assistant line stamped 0 carries no time`() {
        val state = reduce(ChatState(), SessionEvent.Cli(Ndjson.decode(REPLY_LINE), atMillis = 0L))
        assertNull(state.assistants().single().atMillis)
    }
}

/** A complete main-thread `assistant` line with one text block. */
private const val REPLY_LINE: String =
    """{"type":"assistant","uuid":"a1","message":{"id":"m1","model":"claude-sonnet-5-5","content":[{"type":"text","text":"pong"}]}}"""
