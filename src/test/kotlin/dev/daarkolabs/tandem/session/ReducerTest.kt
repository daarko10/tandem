package dev.daarkolabs.tandem.session

import dev.daarkolabs.tandem.protocol.CliMessage
import dev.daarkolabs.tandem.protocol.ContextBreakdown
import dev.daarkolabs.tandem.protocol.ControlPayloads
import dev.daarkolabs.tandem.protocol.Fixtures
import dev.daarkolabs.tandem.protocol.Ndjson
import dev.daarkolabs.tandem.protocol.Delta
import dev.daarkolabs.tandem.protocol.McpServerInfo
import dev.daarkolabs.tandem.protocol.McpStatus
import dev.daarkolabs.tandem.protocol.PermissionMode
import dev.daarkolabs.tandem.protocol.StreamEvent
import dev.daarkolabs.tandem.protocol.UsageAnswer
import dev.daarkolabs.tandem.session.transcript.TranscriptReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
            // The recorded set_model answer (<request_id-25>) goes through the set_model answer matching (ModelChange).
            if (message is CliMessage.ControlResponse && message.payload == null) {
                state = reduce(state, SessionEvent.ModelRequested("claude-haiku-5-5", message.requestId))
            }
            state = reduce(state, SessionEvent.Cli(message))
            assertNull(state.modelWarning, "unexpected warning: ${state.modelWarning}")
        }
        assertEquals("claude-haiku-5-5", state.activeModel)
        assertEquals(emptyMap(), state.modelChanges, "the recorded success answered the switch")
        assertTrue(state.entries.none { it is ChatEntry.Notice })
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
        assertTrue(modelMatches("fable", "claude-fable-5-1"))
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

    private fun success(requestId: String): SessionEvent =
        cli("""{"type":"control_response","response":{"subtype":"success","request_id":"$requestId"}}""")

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
    fun `an init whose mode differs from the spawn's --permission-mode says so`() {
        val init = SessionEvent.Cli(at("permission-mode", 5)) // "permissionMode":"default"
        val state = reduce(ChatState(launchPermissionMode = PermissionMode.AUTO), init)
        val warnings = state.entries.filterIsInstance<ChatEntry.Notice>().filter { it.level == NoticeLevel.WARNING }
        assertEquals(listOf("The CLI started in permission mode default, not auto as picked."), warnings.map { it.text })
        assertEquals(PermissionMode.DEFAULT, state.permissionMode)
        assertNull(state.launchPermissionMode)

        val same = reduce(ChatState(launchPermissionMode = PermissionMode.DEFAULT), init)
        assertTrue(same.entries.none { it is ChatEntry.Notice }, "the CLI started as picked")
        assertNull(same.launchPermissionMode)

        val second = reduce(state, init)
        assertEquals(1, second.entries.count { it is ChatEntry.Notice }, "only the first init after the spawn is checked")
    }

    @Test
    fun `a pick before the first init cancels the launch-mode check`() {
        var state = ChatState(launchPermissionMode = PermissionMode.ACCEPT_EDITS)
        state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.PLAN, "r1"))
        state = reduce(state, success("r1"))
        state = reduce(state, edited("permission-mode", 5) { it["permissionMode"] = JsonPrimitive("plan") })
        assertTrue(state.entries.none { it is ChatEntry.Notice && it.level == NoticeLevel.WARNING })
        assertEquals(PermissionMode.PLAN, state.permissionMode)
        assertNull(state.launchPermissionMode)

        val silent = reduce(ChatState(launchPermissionMode = PermissionMode.ACCEPT_EDITS), edited("permission-mode", 5) { it.remove("permissionMode") })
        assertTrue(silent.entries.none { it is ChatEntry.Notice && it.level == NoticeLevel.WARNING }, "an init without a mode says nothing")
        assertNull(silent.launchPermissionMode)
    }

    @Test
    fun `a refused mode joins rejectedPermissionModes, a reported one leaves, Ended clears them`() {
        var state = ChatState()
        for (line in Fixtures.lines("permission-mode-rejected")) {
            val message = Ndjson.decode(line)
            if (message is CliMessage.ControlResponse && !message.isSuccess) {
                state = reduce(state, SessionEvent.PermissionModeRequested(PermissionMode.BYPASS_PERMISSIONS, message.requestId))
            }
            state = reduce(state, SessionEvent.Cli(message))
        }
        assertEquals(setOf(PermissionMode.BYPASS_PERMISSIONS), state.rejectedPermissionModes)
        val notice = state.entries.filterIsInstance<ChatEntry.Notice>().single { it.level == NoticeLevel.ERROR }
        assertEquals(
            "The CLI rejected the switch to permission mode bypassPermissions (Cannot set permission mode to bypassPermissions " +
                "because the session was not launched with --dangerously-skip-permissions); it stays default.",
            notice.text,
        )

        val reported = reduce(state, cli("""{"type":"system","subtype":"status","status":null,"permissionMode":"bypassPermissions","uuid":"u","session_id":"s"}"""))
        assertEquals(emptySet(), reported.rejectedPermissionModes)

        val auto = ChatState(rejectedPermissionModes = setOf(PermissionMode.AUTO))
        assertEquals(emptySet(), reduce(auto, SessionEvent.Ended("x", isError = false)).rejectedPermissionModes)
        assertEquals(emptySet(), auto.settled().rejectedPermissionModes)
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
    fun `a refused set_model puts back the model shown before, ends the pending switch and says so`() {
        val before = ChatState(
            requestedModel = "sonnet",
            activeModel = "claude-haiku-5-5",
            modelWarning = "You picked sonnet, but the CLI is using claude-haiku-5-5.",
        )
        val requested = reduce(before, SessionEvent.ModelRequested("opus", "r1"))
        assertTrue(requested.modelSwitchPending)
        assertNull(requested.modelWarning)
        assertEquals(
            mapOf("r1" to ModelChange("opus", "sonnet", previousPending = false, previousWarning = before.modelWarning)),
            requested.modelChanges,
        )

        val accepted = reduce(requested, success("r1"))
        assertEquals("opus", accepted.requestedModel)
        assertTrue(accepted.modelSwitchPending, "an accepted switch still waits for its init")
        assertEquals(emptyMap(), accepted.modelChanges)
        assertTrue(accepted.entries.none { it is ChatEntry.Notice })

        val refused = reduce(requested, rejection("r1", "Model opus is not available"))
        assertEquals("sonnet", refused.requestedModel)
        assertFalse(refused.modelSwitchPending, "the footer leaves its pending state")
        assertEquals(before.modelWarning, refused.modelWarning)
        assertEquals(emptyMap(), refused.modelChanges)
        // The sonnet pick was confirmed, but the last system/init reported haiku (the footer shows it): the CLI stays on haiku.
        val text = "The CLI rejected the switch to model opus (Model opus is not available); it stays on model claude-haiku-5-5."
        assertEquals(ChatEntry.Notice("notice-0", NoticeLevel.ERROR, text), refused.entries.last())

        val defaultRefused = reduce(reduce(ChatState(), SessionEvent.ModelRequested(null, "r2")), rejection("r2", "no"))
        assertEquals(
            "The CLI rejected the switch to the default model (no); it stays on the default model.",
            (defaultRefused.entries.last() as ChatEntry.Notice).text,
        )
        assertFalse(defaultRefused.modelSwitchPending)
        // No pick before: the model the session runs, from its last system/init.
        val running = ChatState(activeModel = "claude-haiku-5-5")
        val runningRefused = reduce(reduce(running, SessionEvent.ModelRequested("opus", "r3")), rejection("r3", "no"))
        assertEquals(
            "The CLI rejected the switch to model opus (no); it stays on model claude-haiku-5-5.",
            (runningRefused.entries.last() as ChatEntry.Notice).text,
        )
        // The default pick, accepted and still waiting for its init: the CLI is on the default model, not the one before it.
        val onSonnet = ChatState(requestedModel = "sonnet", activeModel = "claude-sonnet-5-5")
        val defaultAccepted = reduce(reduce(onSonnet, SessionEvent.ModelRequested(null, "r1")), success("r1"))
        val haikuRefused = reduce(reduce(defaultAccepted, SessionEvent.ModelRequested("haiku", "r4")), rejection("r4", "no"))
        assertEquals(
            "The CLI rejected the switch to model haiku (no); it stays on the default model.",
            (haikuRefused.entries.last() as ChatEntry.Notice).text,
        )
        assertNull(haikuRefused.requestedModel)
        assertTrue(haikuRefused.modelSwitchPending, "the default pick still waits for its init")
    }

    @Test
    fun `a refused set_model hands what was shown before it to the next pick waiting`() {
        // opus accepted and waiting for its init; then haiku (r2) and fable (r3), both sent before the CLI answers either.
        val opus = reduce(reduce(ChatState(requestedModel = "sonnet"), SessionEvent.ModelRequested("opus", "r1")), success("r1"))
        val sent = reduce(reduce(opus, SessionEvent.ModelRequested("haiku", "r2")), SessionEvent.ModelRequested("fable", "r3"))

        val firstRefused = reduce(sent, rejection("r2", "no"))
        assertEquals("fable", firstRefused.requestedModel, "the later pick still shows")
        assertTrue(firstRefused.modelSwitchPending)
        assertEquals(mapOf("r3" to ModelChange("fable", "opus", previousPending = true, previousWarning = null)), firstRefused.modelChanges)
        // The footer shows fable, still waiting, so the notice says what the CLI runs meanwhile.
        assertEquals(
            "The CLI rejected the switch to model haiku (no); it stays on model opus.",
            (firstRefused.entries.last() as ChatEntry.Notice).text,
        )

        val bothRefused = reduce(firstRefused, rejection("r3", "no"))
        assertEquals("opus", bothRefused.requestedModel)
        assertTrue(bothRefused.modelSwitchPending, "opus, accepted, still waits for its init")
        assertEquals(emptyMap(), bothRefused.modelChanges)
        assertEquals(
            "The CLI rejected the switch to model fable (no); it stays on model opus.",
            (bothRefused.entries.last() as ChatEntry.Notice).text,
        )
    }

    @Test
    fun `settled and an exit clear the model changes, and a pick for a stopped session records none`() {
        val state = reduce(ChatState(), SessionEvent.ModelRequested("opus", "r1"))
        assertEquals(emptyMap(), state.settled().modelChanges)
        assertEquals(emptyMap(), reduce(state, SessionEvent.Ended("exited", isError = false)).modelChanges)
        val stopped = reduce(ChatState(), SessionEvent.ModelRequested("opus"))
        assertEquals("opus", stopped.requestedModel)
        assertEquals(emptyMap(), stopped.modelChanges)
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

    @Test
    fun `an mcp_status answer replaces the servers and marks them detailed`() {
        val answer = Ndjson.decode(Fixtures.lines("mcp-status")[18 - 1]) as CliMessage.ControlResponse
        val servers = assertNotNull(ControlPayloads.mcpStatus(answer.payload)).servers
        // What an earlier answer or init left: a server no longer listed, and the inits counted so far.
        val earlier = ChatState(mcp = McpState(servers = listOf(McpServerInfo("gone", McpStatus.FAILED, "failed")), initCount = 2))

        val state = reduce(earlier, SessionEvent.McpServersListed(servers))
        assertEquals(listOf("echo", "broken", "authy"), state.mcp.servers.map { it.name })
        assertEquals(servers, state.mcp.servers)
        assertTrue(state.mcp.detailed)
        assertEquals(2, state.mcp.initCount)
        assertEquals(earlier.entries, state.entries)
    }

    /** The lines [from]..[to] (1-based, inclusive) of fixture [name], folded into [state] in order. */
    private fun fold(state: ChatState, name: String, from: Int, to: Int): ChatState =
        (from..to).fold(state) { s, line -> reduce(s, SessionEvent.Cli(at(name, line))) }

    /** The servers of the `mcp_status` answer at [line] of fixture [name]. */
    private fun listedAt(name: String, line: Int): List<McpServerInfo> =
        assertNotNull(ControlPayloads.mcpStatus((at(name, line) as CliMessage.ControlResponse).payload)).servers

    private fun ChatState.localNotices(): List<ChatEntry.Notice> =
        entries.filterIsInstance<ChatEntry.Notice>().filter { it.kind == NoticeKind.LOCAL_COMMAND }

    private fun ChatState.server(name: String): McpServerInfo = mcp.servers.single { it.name == name }

    @Test
    fun `an init merges the thin list and counts inits`() {
        val first = reduce(ChatState(), SessionEvent.Cli(at("mcp-status", 6)))
        assertEquals(
            listOf(
                McpServerInfo("echo", McpStatus.CONNECTED, "connected", source = "dynamic"),
                McpServerInfo("broken", McpStatus.FAILED, "failed", source = "dynamic"),
                McpServerInfo("authy", McpStatus.NEEDS_AUTH, "needs-auth", source = "dynamic"),
            ),
            first.mcp.servers,
        )
        assertFalse(first.mcp.detailed)
        assertEquals(1, first.mcp.initCount)

        // An answer's details stay on a name whose status the next init repeats; the inits keep counting.
        val detailed = reduce(first, SessionEvent.McpServersListed(listedAt("mcp-status", 18)))
        val second = reduce(detailed, SessionEvent.Cli(at("mcp-status", 48)))
        assertEquals(2, second.mcp.initCount)
        assertTrue(second.mcp.detailed)
        assertEquals(detailed.mcp.servers, second.mcp.servers)
        assertNotNull(second.server("echo").config)

        // The command names of init replace the ones before, empty lists included.
        val init = at("mcp-slash", 4) as CliMessage.SystemInit
        val named = reduce(second, SessionEvent.Cli(init))
        assertEquals(73, named.slashCommandNames.size)
        assertEquals(init.slashCommands, named.slashCommandNames)
        assertEquals(setOf("doctor", "color", "focus", "reload-plugins"), named.terminalCommands)
        assertEquals(3, named.mcp.initCount)
        val emptied = reduce(named, SessionEvent.Cli(at("mcp-status", 23)))
        assertEquals(emptyList(), emptied.slashCommandNames)
        assertEquals(emptySet(), emptied.terminalCommands)
    }

    @Test
    fun `a status change in init drops the stale error, tools and version`() {
        val listed = reduce(ChatState(), SessionEvent.McpServersListed(listedAt("mcp-status", 18)))
        assertNotNull(listed.server("echo").tools)
        assertNotNull(listed.server("echo").serverInfo)
        // :19 answers the toggle, :20 the refresh after it; reaching the reducer as lines, neither changes the servers.
        val toggled = fold(listed, "mcp-status", 19, 20)
        assertEquals(listed.mcp, toggled.mcp)

        val state = reduce(toggled, SessionEvent.Cli(at("mcp-status", 23)))
        val echo = state.server("echo")
        assertEquals(McpStatus.DISABLED, echo.status)
        assertEquals("disabled", echo.statusText)
        assertNull(echo.tools)
        assertNull(echo.serverInfo)
        assertNull(echo.error)
        assertEquals(listed.server("echo").config, echo.config)
        // broken's status is unchanged, so its error stays until the next mcp_status.
        assertEquals("Connection closed", state.server("broken").error)
        assertEquals(listed.server("authy"), state.server("authy"))
        assertTrue(state.mcp.detailed)
    }

    @Test
    fun `the initialize answer fills the commands`() {
        val state = reduce(ChatState(), SessionEvent.Cli(at("mcp-slash", 1)))
        assertEquals(73, state.commands.size)
        assertEquals(ControlPayloads.initialize((at("mcp-slash", 1) as CliMessage.ControlResponse).payload)?.commands, state.commands)
        assertTrue(state.entries.isEmpty())
        // mcp-status's answer lists no commands (the redactor emptied them) but still its models.
        val emptied = reduce(state, SessionEvent.Cli(at("mcp-status", 1)))
        assertEquals(emptyList(), emptied.commands)
        assertTrue(emptied.models.isNotEmpty())
        // A success without a commands array keeps them.
        assertEquals(state.commands, reduce(state, SessionEvent.Cli(at("mcp-status", 19))).commands)
    }

    @Test
    fun `an init without a connector keeps it until the next mcp_status`() {
        // Synthetic, docs only (probes 2.5): a claude.ai connector that the init's thin list leaves out.
        val connector = McpServerInfo("claude.ai Example", McpStatus.CONNECTED, "connected", source = "claudeai")
        val before = reduce(ChatState(), SessionEvent.Cli(at("mcp-status", 6)))
        val withConnector = before.copy(mcp = before.mcp.copy(servers = before.mcp.servers + connector))
        // :23 without broken: a dynamic name missing from init is dropped.
        val line = Json.parseToJsonElement(Fixtures.lines("mcp-status")[23 - 1]).jsonObject
        val servers = JsonArray(line.getValue("mcp_servers").let { it as JsonArray }.filter { it.jsonObject["name"]?.jsonPrimitive?.content != "broken" })
        val withoutBroken = JsonObject(line + ("mcp_servers" to servers))

        val state = reduce(withConnector, SessionEvent.Cli(Ndjson.decode(withoutBroken.toString())))
        assertEquals(listOf("echo", "authy", "claude.ai Example"), state.mcp.servers.map { it.name })
        assertEquals(connector, state.server("claude.ai Example"))

        val listed = reduce(state, SessionEvent.McpServersListed(listedAt("mcp-status", 18)))
        assertEquals(listOf("echo", "broken", "authy"), listed.mcp.servers.map { it.name })
    }

    @Test
    fun `a local command is a notice and its result ends the turn without a footer`() {
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "/mcp", atMillis = 1_000L))
        val state = fold(sent, "mcp-slash", 2, 6)
        assertEquals(listOf("/mcp"), state.entries.filterIsInstance<ChatEntry.User>().map { it.text })
        val notice = state.localNotices().single()
        assertEquals(NoticeLevel.INFO, notice.level)
        assertEquals("mcp", notice.command)
        assertEquals("mcp", notice.runCommand)
        assertEquals((at("mcp-slash", 5) as CliMessage.Assistant).localCommand?.stdout, notice.text)
        assertEquals(listOf("user", "notice"), state.entries.map { if (it is ChatEntry.User) "user" else if (it is ChatEntry.Notice) "notice" else "other" })
        assertTrue(state.assistants().isEmpty())
        assertTrue(state.turnEnds().isEmpty())
        assertFalse(state.busy)
        assertNull(state.turn)
        assertEquals(0, state.turnsEnded)
        assertNull(state.lastTurnEnd)
        assertEquals("<session_id-4>", state.sessionId)

        // While the turn runs, it is flagged local once the CLI's own line is in.
        val answered = fold(sent, "mcp-slash", 2, 5)
        assertEquals(true, answered.turn?.localCommand)
        assertTrue(answered.busy)
    }

    @Test
    fun `an unavailable command is an info notice named from the prompt`() {
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "/help"))
        val state = fold(sent, "mcp-slash", 14, 18)
        val notice = state.localNotices().single()
        assertEquals(NoticeLevel.INFO, notice.level)
        assertEquals("/help isn't available in this environment.", notice.text)
        assertEquals("help", notice.command)
        assertNull(notice.runCommand)
        // Its result names no command: the turn's flag ends it the same way.
        assertNull((at("mcp-slash", 18) as CliMessage.Result).localCommand)
        assertTrue(state.turnEnds().isEmpty())
        assertFalse(state.busy)
        assertNull(state.turn)
        assertEquals(0, state.turnsEnded)
        assertEquals(1, state.entries.count { it is ChatEntry.Notice })
    }

    @Test
    fun `a local command after a prompt that is not a command takes runCommand`() {
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "Reply with the single word: pong"))
        val state = fold(sent, "mcp-slash", 2, 6)
        val notice = state.localNotices().single()
        assertEquals("mcp", notice.command)
        assertEquals("mcp", notice.runCommand)
        assertEquals((at("mcp-slash", 5) as CliMessage.Assistant).localCommand?.stdout, notice.text)

        // No prompt at all (a stream folded without UserSent): runCommand too, and null when the line names none.
        assertEquals("mcp", fold(ChatState(), "mcp-slash", 2, 6).localNotices().single().command)
        assertNull(fold(ChatState(), "mcp-slash", 14, 18).localNotices().single().command)
    }

    @Test
    fun `a local command without its output takes the text of its line`() {
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "/compact"))
        val line = """{"type":"assistant","uuid":"l1","local_command_run":{"command":"compact","args":""},""" +
            """"message":{"id":"l1","model":"<synthetic>","content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}}"""
        val state = reduce(sent, cli(line))
        val notice = state.localNotices().single()
        assertEquals("one\ntwo", notice.text)
        assertEquals("compact", notice.command)
        assertTrue(state.assistants().isEmpty())
        // A result that names the command ends the turn even when no line flagged it.
        val other = reduce(ChatState(), SessionEvent.UserSent("u2", "/cost"))
        val ended = reduce(other, cli("""{"type":"result","subtype":"success","is_error":false,"local_command":"cost","session_id":"s1"}"""))
        assertTrue(ended.turnEnds().isEmpty())
        assertNull(ended.turn)
        assertEquals("s1", ended.sessionId)
    }

    @Test
    fun `a project command is an ordinary turn`() {
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "/hello"))
        val state = fold(sent, "mcp-slash", 20, 34)
        assertEquals("hello from the hello command", state.text())
        assertEquals(TurnEnd.COMPLETED, state.turnEnds().single().end)
        assertTrue(state.localNotices().isEmpty())
        assertEquals(1, state.turnsEnded)
        assertFalse(state.busy)
    }

    @Test
    fun `a command with arguments names the notice from its first token`() {
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "/mcp reconnect x"))
        assertEquals("mcp", fold(sent, "mcp-slash", 2, 6).localNotices().single().command)
        val tabbed = reduce(ChatState(), SessionEvent.UserSent("u1", "/help\tme\nnow"))
        assertEquals("help", fold(tabbed, "mcp-slash", 14, 18).localNotices().single().command)
    }

    @Test
    fun `local command output goes through the same rule as the stream`() {
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "/model"))
        val state = reduce(sent, SessionEvent.LocalCommandOutput("Current model: x", runCommand = "model"))
        val notice = state.localNotices().single()
        assertEquals(ChatEntry.Notice("notice-0", NoticeLevel.INFO, "Current model: x", NoticeKind.LOCAL_COMMAND, "model", "model"), notice)
        assertEquals(true, state.turn?.localCommand)
        // Outside a turn there is nothing to flag.
        val idle = reduce(ChatState(), SessionEvent.LocalCommandOutput("out", runCommand = null))
        assertNull(idle.turn)
        assertNull(idle.localNotices().single().command)
    }

    @Test
    fun `a failed local command says so once, and not after Stop`() {
        // Synthetic (no fixture has a failed local command): its result says so (hard rule 5), still without a footer.
        val failure = """{"type":"result","subtype":"error_during_execution","is_error":true,"local_command":"x","result":"boom","session_id":"s1"}"""
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "/x"))
        val failed = reduce(sent, cli(failure))
        val errors = failed.entries.filterIsInstance<ChatEntry.Notice>().filter { it.level == NoticeLevel.ERROR }
        assertEquals(listOf("The turn failed (error_during_execution): boom"), errors.map { it.text })
        assertTrue(failed.turnEnds().isEmpty())
        assertEquals(0, failed.turnsEnded)
        assertNull(failed.lastTurnEnd)
        assertFalse(failed.busy)
        assertNull(failed.turn)

        // After Stop the interrupt already said "Stopped.": by the marker line, or by the result's terminal reason.
        val marker = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"""
        val stopped = reduce(reduce(sent, cli(marker)), cli(failure))
        assertEquals(listOf(NoticeLevel.INFO to "Stopped."), stopped.entries.filterIsInstance<ChatEntry.Notice>().map { it.level to it.text })
        assertFalse(stopped.turnInterrupted)
        assertTrue(stopped.turnEnds().isEmpty())
        assertEquals(0, stopped.turnsEnded)
        val aborted = reduce(sent, cli(failure.replace("\"is_error\":true", "\"is_error\":true,\"terminal_reason\":\"aborted_streaming\"")))
        assertTrue(aborted.entries.none { it is ChatEntry.Notice })
        assertTrue(aborted.turnEnds().isEmpty())
        assertFalse(aborted.busy)
    }

    @Test
    fun `an init entry without a source keeps the stored connector source`() {
        val connector = McpServerInfo("claude.ai Example", McpStatus.CONNECTED, "connected", source = "claudeai")
        val stored = ChatState().let { it.copy(mcp = it.mcp.copy(servers = listOf(connector))) }
        fun init(servers: String) = cli("""{"type":"system","subtype":"init","session_id":"s","model":"claude-opus-5-5","mcp_servers":[$servers]}""")

        // The same status without a source: the entry stays as it was.
        val same = reduce(stored, init("""{"name":"claude.ai Example","status":"connected"}"""))
        assertEquals(listOf(connector), same.mcp.servers)
        // A new status without a source: the source stays too, so a later init that leaves it out still keeps it.
        val failed = reduce(same, init("""{"name":"claude.ai Example","status":"failed"}"""))
        assertEquals("claudeai", failed.server("claude.ai Example").source)
        assertEquals(McpStatus.FAILED, failed.server("claude.ai Example").status)
        val later = reduce(failed, init("""{"name":"echo","status":"connected","source":"dynamic"}"""))
        assertEquals(listOf("echo", "claude.ai Example"), later.mcp.servers.map { it.name })
        assertEquals("claudeai", later.server("claude.ai Example").source)
        assertEquals(3, later.mcp.initCount)
    }

    /** Line [line] (1-based) of fixture [name] as a JSON object, edited by [edit], decoded. */
    private fun edited(name: String, line: Int, edit: (MutableMap<String, JsonElement>) -> Unit): SessionEvent {
        val fields = Json.parseToJsonElement(Fixtures.lines(name)[line - 1]).jsonObject.toMutableMap()
        edit(fields)
        return SessionEvent.Cli(Ndjson.decode(JsonObject(fields).toString()))
    }

    @Test
    fun `a shell escape is one entry that runs, prints and ends`() {
        val started = reduce(ChatState(), SessionEvent.ShellStarted("shell-1", "x", 0))
        assertEquals("shell-1", started.runningShell)
        val printed = listOf(SessionEvent.ShellOutput("shell-1", "a"), SessionEvent.ShellOutput("shell-1", "b")).fold(started, ::reduce)
        assertEquals("shell-1", printed.runningShell)
        val ended = reduce(printed, SessionEvent.ShellEnded("shell-1", ShellEnd.Exited(0), 5))
        val shell = ended.entries.single() as ChatEntry.Shell
        assertEquals("ab", shell.output)
        assertFalse(shell.running)
        assertEquals(ShellEnd.Exited(0), shell.end)
        assertEquals(5L, shell.durationMs)
        assertNull(ended.runningShell)
        assertEquals(ended.positions(), ended.entryIndex)
    }

    @Test
    fun `the quiet result of a shell send ends no turn`() {
        var state = reduce(ChatState(), SessionEvent.ShellSent("shell-1", listOf("<command_uuid-4>", "<command_uuid-6>"), null, 0))
        assertEquals(setOf("<command_uuid-4>", "<command_uuid-6>"), state.quietSends)
        state = fold(state, "bash-mode", 3, 8)
        assertTrue(state.entries.none { it is ChatEntry.TurnEnded })
        assertFalse(state.busy)
        assertTrue(state.quietSends.isEmpty())
        assertEquals("<session_id-2>", state.sessionId)
        assertEquals(0, state.turnsEnded)
    }

    @Test
    fun `the one context frame's quiet result empties the quiet sends`() {
        val sent = reduce(ChatState(), SessionEvent.ShellSent("shell-1", listOf("<command_uuid-4>"), null, 0))
        val quiet = fold(sent, "bash-mode-context", 1, 6)
        assertTrue(quiet.quietSends.isEmpty())
        assertTrue(quiet.entries.none { it is ChatEntry.TurnEnded })
        assertFalse(quiet.busy)
        val prompted = reduce(quiet, SessionEvent.UserSent("<command_uuid-10>", "Reply with only the text the command printed to stdout", 0))
        val answered = fold(prompted, "bash-mode-context", 7, 21)
        assertTrue(answered.quietSends.isEmpty())
        assertFalse(answered.busy)
        assertEquals(1, answered.entries.count { it is ChatEntry.TurnEnded })
    }

    @Test
    fun `a responding shell send opens a turn that its reply's result ends`() {
        val sent = reduce(ChatState(), SessionEvent.ShellSent("shell-1", listOf("<command_uuid-4>"), "<command_uuid-10>", 0))
        assertTrue(sent.busy)
        val quiet = fold(sent, "bash-mode-respond", 3, 6)
        assertTrue(quiet.busy, "the quiet result leaves the reply's turn open")
        assertTrue(quiet.entries.none { it is ChatEntry.TurnEnded })
        assertTrue(quiet.quietSends.isEmpty())
        val answered = fold(quiet, "bash-mode-respond", 7, 42)
        assertFalse(answered.busy)
        assertEquals(1, answered.entries.count { it is ChatEntry.TurnEnded })
        assertEquals(sent.turnsEnded + 1, answered.turnsEnded)
    }

    @Test
    fun `a result that names a quiet send and a prompt is the prompt's`() {
        val state = listOf(
            SessionEvent.ShellSent("shell-1", listOf("a"), null, 0),
            SessionEvent.UserSent("p", "x", 0),
            edited("plain-reply", 15) { it["user_message_uuids"] = Json.parseToJsonElement("[\"a\",\"p\"]") },
        ).fold(ChatState(), ::reduce)
        assertEquals(1, state.entries.count { it is ChatEntry.TurnEnded })
        assertTrue(state.quietSends.isEmpty())
        assertFalse(state.busy)
    }

    @Test
    fun `a process that ends before the quiet result leaves no quiet send`() {
        val sent = reduce(ChatState(), SessionEvent.ShellSent("shell-1", listOf("a"), null, 0))
        assertTrue(reduce(sent, SessionEvent.Ended("The claude CLI exited.", isError = false)).quietSends.isEmpty())
        assertTrue(ChatState(quietSends = setOf("a")).settled().quietSends.isEmpty())
    }

    @Test
    fun `a zero-turn result without uuids ends the quiet send without a footer`() {
        val state = listOf(
            SessionEvent.ShellSent("shell-1", listOf("a"), null, 0),
            edited("bash-mode", 8) { it.remove("user_message_uuids") },
        ).fold(ChatState(), ::reduce)
        assertTrue(state.quietSends.isEmpty())
        assertTrue(state.entries.none { it is ChatEntry.TurnEnded })
        assertFalse(state.busy)
        assertEquals("<session_id-2>", state.sessionId)
    }

    @Test
    fun `an error result for a quiet send says the turn failed`() {
        val state = listOf(
            SessionEvent.ShellSent("shell-1", listOf("a"), null, 0),
            edited("bash-mode", 8) {
                it["user_message_uuids"] = Json.parseToJsonElement("[\"a\"]")
                it["is_error"] = JsonPrimitive(true)
                it["result"] = JsonPrimitive("boom")
            },
        ).fold(ChatState(), ::reduce)
        assertTrue(state.quietSends.isEmpty())
        val notice = state.entries.filterIsInstance<ChatEntry.Notice>().single()
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue(notice.text.startsWith("The turn failed"), notice.text)
        assertTrue("boom" in notice.text, notice.text)
        assertTrue(state.entries.none { it is ChatEntry.TurnEnded })
        assertFalse(state.busy)
    }

    @Test
    fun `an error zero-turn result without uuids is a failed result`() {
        val state = listOf(
            SessionEvent.ShellSent("shell-1", listOf("a"), null, 0),
            edited("bash-mode", 8) {
                it.remove("user_message_uuids")
                it["is_error"] = JsonPrimitive(true)
                it["result"] = JsonPrimitive("boom")
            },
        ).fold(ChatState(), ::reduce)
        assertTrue(state.quietSends.isEmpty())
        assertEquals(TurnEnd.FAILED, state.entries.filterIsInstance<ChatEntry.TurnEnded>().single().end)
        val notice = state.entries.filterIsInstance<ChatEntry.Notice>().single()
        assertEquals(NoticeLevel.ERROR, notice.level)
        assertTrue(notice.text.startsWith("The turn failed") && "boom" in notice.text, notice.text)
    }

    @Test
    fun `a dropped shell reply ends the turn without a footer`() {
        val state = listOf(
            SessionEvent.ShellStarted("shell-1", "echo hi", 0),
            SessionEvent.ShellEnded("shell-1", ShellEnd.Exited(0), 3),
            SessionEvent.ShellSent("shell-1", listOf("a"), "q", 0),
            SessionEvent.ShellReplyDropped("shell-1"),
        ).fold(ChatState(), ::reduce)
        assertFalse(state.busy)
        assertNull(state.turn)
        assertTrue(state.entries.none { it is ChatEntry.TurnEnded })
        val last = state.entries.last() as ChatEntry.Notice
        assertEquals("Stopped.", last.text)
        assertEquals(NoticeKind.INTERRUPTED, last.kind)
        assertTrue((state.entries.first() as ChatEntry.Shell).sent)
    }

    @Test
    fun `the first prompt of a shell escape session is the command`() {
        val state = reduce(ChatState(), SessionEvent.ShellStarted("shell-1", "echo hi", 0))
        assertEquals("! echo hi", state.firstPrompt())
        assertEquals("! echo hi", reduce(state, SessionEvent.UserSent("u1", "later", 0)).firstPrompt())
    }

    @Test
    fun `a loaded shell escape is a finished entry without an end`() {
        val state = reduce(ChatState(), SessionEvent.ShellLoaded("line-1", "ls", "a.txt", 7))
        val shell = state.entries.single() as ChatEntry.Shell
        assertFalse(shell.running)
        assertNull(shell.end)
        assertEquals("a.txt", shell.output)
        assertEquals(7L, shell.startedAtMillis)
        assertNull(state.runningShell)
    }

    // --- Desktop parity (spec 4.2, 9.2): plugins, skills and folders from init, the usage card, the context breakdown ---

    private fun usageAt(line: Int): UsageAnswer =
        assertNotNull(ControlPayloads.usage((at("parity-usage", line) as CliMessage.ControlResponse).payload))

    private fun breakdownAt(line: Int): ContextBreakdown =
        assertNotNull(ControlPayloads.contextUsage((at("parity-context", line) as CliMessage.ControlResponse).payload))

    private fun ChatState.usageNotices(): List<ChatEntry.Notice> =
        entries.filterIsInstance<ChatEntry.Notice>().filter { it.kind == NoticeKind.LOCAL_COMMAND }

    /** A state with one usage notice for `u1` whose card is [card]. */
    private fun withCard(card: UsageCard, breakdown: ContextBreakdown? = null): ChatState {
        val line = Fixtures.lines("parity-usage")[21].replace("<uuid-28>", "u1")
        return reduce(ChatState(contextBreakdown = breakdown), cli(line)).let { state ->
            val notice = state.usageNotices().single()
            state.copy(entries = state.entries.map { if (it === notice) notice.copy(usage = card) else it }, entryIndex = emptyMap())
        }
    }

    private fun ChatState.card(): UsageCard = assertNotNull(usageNotices().single().usage)

    @Test
    fun `an init sets the plugins and added directories`() {
        val states = replay("parity-add-dir")
        assertEquals(4, states[5].plugins.size)
        assertEquals(emptyList(), states[5].additionalDirectories)
        assertEquals(listOf("<path>"), states[17].additionalDirectories)
    }

    @Test
    fun `an init replaces the skills`() {
        val states = replay("parity-add-dir")
        assertEquals(35, states[5].skills.size)
        assertTrue("probe-kit:greet" in states[5].skills)
        val withoutSkills = edited("parity-add-dir", 5) { it.remove("skills") }
        assertEquals(emptyList(), reduce(states[5], withoutSkills).skills)
    }

    @Test
    fun `a stream usage line gets a pending card`() {
        val state = replay("parity-usage")[22]
        val notice = state.entries.filterIsInstance<ChatEntry.Notice>().last()
        assertEquals(NoticeKind.LOCAL_COMMAND, notice.kind)
        assertEquals("usage", notice.runCommand)
        assertEquals("<uuid-28>", notice.sourceUuid)
        assertEquals(UsageCard(UsagePartState.Loading, UsagePartState.Loading), notice.usage)
    }

    // spec: /cost gets the same card
    @Test
    fun `cost gets the same card`() {
        val notices = replay("parity-usage")[28].usageNotices()
        assertEquals(2, notices.size)
        assertEquals("<uuid-36>", notices[1].sourceUuid)
        assertEquals(UsageCard(UsagePartState.Loading, UsagePartState.Loading), notices[1].usage)
    }

    @Test
    fun `a transcript's usage line has no card`() = runBlocking {
        val notices = TranscriptReader.read(Fixtures.transcript("parity-usage")).state.usageNotices()
        assertEquals(2, notices.size)
        assertTrue(notices.all { it.usage == null && it.sourceUuid == null }, notices.toString())
    }

    @Test
    fun `usage answers fill their own card`() {
        val u16 = usageAt(16)
        val u17 = usageAt(17)
        val through = replay("parity-usage")[28]
        val first = reduce(through, SessionEvent.UsageFetched("<uuid-28>", UsagePart.SESSION, McpResult.Ok(u16)))
        val (one, two) = first.usageNotices()
        assertEquals(UsagePartState.Loaded(u16.session!!), one.usage!!.session)
        assertEquals(UsagePartState.Loading, one.usage!!.history)
        assertEquals(UsageCard(UsagePartState.Loading, UsagePartState.Loading), two.usage)

        val second = reduce(first, SessionEvent.UsageFetched("<uuid-36>", UsagePart.HISTORY, McpResult.Ok(u17)))
        assertEquals(UsagePartState.Loaded(u17.history), second.usageNotices()[1].usage!!.history)
        assertEquals(UsagePartState.Loading, second.usageNotices()[1].usage!!.session)
        assertEquals(one, second.usageNotices()[0])
    }

    @Test
    fun `a usage answer for an unknown uuid changes nothing`() {
        val state = replay("parity-usage")[28]
        assertSame(state, reduce(state, SessionEvent.UsageFetched("nope", UsagePart.SESSION, McpResult.Ok(usageAt(16)))))
        assertSame(state, reduce(state, SessionEvent.UsageRetry("nope", UsagePart.SESSION)))
    }

    @Test
    fun `a session answer without session fails the part`() {
        val state = reduce(
            withCard(UsageCard(UsagePartState.Loading, UsagePartState.Loading)),
            SessionEvent.UsageFetched("u1", UsagePart.SESSION, McpResult.Ok(UsageAnswer(null, null))),
        )
        assertEquals(UsagePartState.Failed(McpError.Rejected("the CLI's answer had no session")), state.card().session)
        val failed = reduce(state, SessionEvent.UsageFetched("u1", UsagePart.HISTORY, McpResult.Failed(McpError.TimedOut(60))))
        assertEquals(UsagePartState.Failed(McpError.TimedOut(60)), failed.card().history)
    }

    @Test
    fun `retry sets the parts loading`() {
        val failed = UsagePartState.Failed(McpError.TimedOut(15))
        val both = reduce(withCard(UsageCard(failed, failed)), SessionEvent.UsageRetry("u1", UsagePart.SESSION))
        assertEquals(UsageCard(UsagePartState.Loading, UsagePartState.Loading), both.card())

        val loaded = UsagePartState.Loaded(usageAt(16).session!!)
        val history = reduce(withCard(UsageCard(loaded, failed)), SessionEvent.UsageRetry("u1", UsagePart.HISTORY))
        assertEquals(UsageCard(loaded, UsagePartState.Loading), history.card())
    }

    @Test
    fun `a breakdown answer replaces the last one`() {
        val b2 = breakdownAt(2)
        val b17 = breakdownAt(17)
        val state = reduce(reduce(ChatState(), SessionEvent.ContextBreakdownListed(b2)), SessionEvent.ContextBreakdownListed(b17))
        assertEquals(b17, state.contextBreakdown)
    }

    @Test
    fun `a process end turns loading usage parts into ended`() {
        val loaded = UsagePartState.Loaded(usageAt(16).session!!)
        val ended = reduce(withCard(UsageCard(loaded, UsagePartState.Loading)), SessionEvent.Ended("x", isError = false))
        assertEquals(UsageCard(loaded, UsagePartState.Failed(McpError.Ended)), ended.card())
    }

    @Test
    fun `a stop settles loading usage parts`() {
        val b17 = breakdownAt(17)
        val settled = withCard(UsageCard(UsagePartState.Loading, UsagePartState.Loading), breakdown = b17).settled()
        assertEquals(UsageCard(UsagePartState.Failed(McpError.Ended), UsagePartState.Failed(McpError.Ended)), settled.card())
        assertEquals(b17, settled.contextBreakdown)
    }

    @Test
    fun `a usage line inside a subagent gets no card`() {
        val line = edited("parity-usage", 22) { it["parent_tool_use_id"] = JsonPrimitive("toolu_1") }
        val notice = reduce(ChatState(), line).usageNotices().single()
        assertNull(notice.usage)
        assertNull(notice.sourceUuid)
    }

    // --- Sending while Claude works (spec 12.1 to 12.6, 9.7) ---

    /** A message the host sent to a live process, as [ClaudeSession.sendUserMessage] reduces it. */
    private fun live(uuid: String, text: String, later: Boolean = false, held: Boolean = false): SessionEvent =
        SessionEvent.UserSent(uuid, text, live = true, later = later, held = held)

    /**
     * Fixture [name] replayed with the host's own sends: the events [sends] lists under `n` are reduced right after line `n` (0: before
     * the first line), and the lines in [skip] are left out. Element `n` is the state after line `n` and its sends.
     */
    private fun hostReplay(name: String, sends: Map<Int, List<SessionEvent>>, skip: Set<Int> = emptySet()): List<ChatState> {
        val lines = Fixtures.lines(name)
        var state = sends[0].orEmpty().fold(ChatState()) { s, e -> reduce(s, e) }
        val states = arrayListOf(state)
        for (n in 1..lines.size) {
            if (n !in skip) state = reduce(state, SessionEvent.Cli(Ndjson.decode(lines[n - 1])))
            state = sends[n].orEmpty().fold(state) { s, e -> reduce(s, e) }
            states += state
        }
        return states
    }

    private fun ChatState.stages(): List<Pair<String, PendingStage>> = pendingSends.map { it.uuid to it.stage }

    private fun ChatState.noticeTexts(level: NoticeLevel): List<String> =
        entries.filterIsInstance<ChatEntry.Notice>().filter { it.level == level }.map { it.text }

    /** A turn open for prompt `p`, with F waiting in the CLI's queue. */
    private fun waitingKiwi(): ChatState = reduce(reduce(ChatState(), SessionEvent.UserSent("p", "prompt")), live(QF, KIWI))

    // spec: queue-fold, replayed with the host's sends: F waits, then joins the turn after the tool result
    @Test
    fun `queue-fold, replayed with the host's sends - F waits, then joins the turn after the tool result`() {
        val states = hostReplay("queue-fold", mapOf(0 to listOf(live(QP, "Run it")), 18 to listOf(live(QF, KIWI))))
        val waiting = states[19]
        assertEquals(listOf(QF to PendingStage.QUEUED), waiting.stages())
        assertFalse(waiting.pendingSends.single().later)
        assertEquals(states[18].entries, waiting.entries)

        val joined = states[22]
        assertTrue(joined.pendingSends.isEmpty())
        val folded = joined.entries.last() as ChatEntry.User
        assertEquals(KIWI, folded.text)
        assertTrue(folded.folded)
        assertIs<ChatEntry.ToolResult>(joined.entries[joined.entries.lastIndex - 1], "right after the tool result that ended the round")
        assertNotNull(joined.turn, "the turn is still open")

        val end = states[53]
        assertEquals(1, end.turnsEnded)
        assertFalse(end.busy)
        assertEquals(1, end.entries.count { it is ChatEntry.User && !it.folded })
        assertEquals(1, end.entries.count { it is ChatEntry.TurnEnded })
    }

    // spec: queue-later: F opens its own turn after the first result
    @Test
    fun `queue-later - F opens its own turn after the first result`() {
        val states = hostReplay("queue-later", mapOf(0 to listOf(live(QP, "Run it")), 18 to listOf(live(QF, KIWI, later = true))))
        assertEquals(listOf(QF to PendingStage.QUEUED), states[19].stages())
        assertTrue(states[19].pendingSends.single().later)

        val first = states[51]
        assertEquals(1, first.turnsEnded)
        assertFalse(first.busy)
        assertEquals(listOf(QF), first.pendingSends.map { it.uuid })

        val second = states[53]
        assertTrue(second.pendingSends.isEmpty())
        assertTrue(second.busy)
        val user = second.entries.last() as ChatEntry.User
        assertEquals(QF, user.id)
        assertFalse(user.folded)
        assertEquals(2, states[63].turnsEnded)
    }

    // spec: queue-cancel: a withdrawn later message leaves; Edit returns it, Cancel drops it, no notice
    @Test
    fun `queue-cancel - a withdrawn later message leaves, Edit returns it, Cancel drops it, no notice`() {
        val text = "Reply with only the word KIWI."
        val states = hostReplay(
            "queue-cancel",
            mapOf(0 to listOf(live(QP1, "Run the command")), 22 to listOf(live(QF1, text, later = true))),
        )
        for (restore in listOf(true, false)) {
            val asked = reduce(states[23], SessionEvent.WithdrawRequested(QF1, restore))
            assertEquals(listOf(QF1 to PendingStage.WITHDRAWING), asked.stages())
            val notices = asked.entries.count { it is ChatEntry.Notice }
            val cancelled = reduce(asked, SessionEvent.Cli(at("queue-cancel", 24)))
            assertTrue(cancelled.pendingSends.isEmpty())
            assertEquals(if (restore) listOf(ReturnedSend(QF1, text, emptyList())) else emptyList(), cancelled.returnedSends, "restore=$restore")
            assertEquals(notices, cancelled.entries.count { it is ChatEntry.Notice }, "no notice")
            // The answer comes after the cancelled line (queue-cancel:25): nothing waits any more.
            assertSame(cancelled, reduce(cancelled, SessionEvent.SendWithdrawn(QF1, restore, null)))
            assertEquals(1, fold(cancelled, "queue-cancel", 25, 36).turnsEnded)
        }
    }

    // spec: queue-cancel: Stop's cancelled lifecycle returns F2 with one INFO notice and no further turn
    @Test
    fun `queue-cancel - Stop's cancelled lifecycle returns F2 with one INFO notice and no further turn`() {
        val text = "Reply with only the word MANGO."
        val notice = "Not sent: \"Reply with only the word MANGO.\". It is back in the composer."
        val states = hostReplay(
            "queue-cancel",
            mapOf(
                0 to listOf(live(QP1, "Run the command")),
                37 to listOf(live(QP2, "Run the second command")),
                53 to listOf(live(QF2, text, later = true)),
            ),
        )
        assertEquals(listOf(QF2 to PendingStage.QUEUED), states[54].stages())
        val cancelled = states[55]
        assertEquals(listOf(ReturnedSend(QF2, text, emptyList())), cancelled.returnedSends)
        assertTrue(cancelled.pendingSends.isEmpty())
        assertEquals(listOf(notice), cancelled.noticeTexts(NoticeLevel.INFO))
        // The receipt names F2 too (queue-cancel:56): its cancelled line came first, so nothing changes.
        assertSame(cancelled, reduce(cancelled, SessionEvent.SendWithdrawn(QF2, true, notice)))
        val end = states[61]
        assertFalse(end.busy)
        assertNull(end.turn)
        assertEquals(2, end.turnsEnded, "no further turn")
        assertEquals(1, end.noticeTexts(NoticeLevel.INFO).count { it == notice })
    }

    // D8's recordings replayed with the host's sends where the recordings wrote them (Review Focus 12; probes 5.1).

    @Test
    fun `queue-cancel-steer, replayed with the host's sends - the withdrawn steer leaves and Stop returns the second`() {
        // withdrawable, and Stop withdraws a steer
        val s1 = "<command_uuid-25>"
        val s2 = "<command_uuid-47>"
        val mango = "Also say the word MANGO at the end."
        val notice = "Not sent: \"Also say the word MANGO at the end.\". It is back in the composer."
        val states = hostReplay(
            "queue-cancel-steer",
            mapOf(
                0 to listOf(SessionEvent.UserSent("<command_uuid-4>", "Run the two commands")),
                18 to listOf(live(s1, KIWI), SessionEvent.WithdrawRequested(s1, restore = true)),
                35 to listOf(live(s2, mango)),
            ),
        )
        assertEquals(listOf(s1 to PendingStage.WITHDRAWING), states[18].stages())
        val withdrawn = states[20]
        assertTrue(withdrawn.pendingSends.none { it.uuid == s1 })
        assertEquals(listOf(ReturnedSend(s1, KIWI, emptyList())), withdrawn.returnedSends)
        assertEquals(emptyList(), withdrawn.noticeTexts(NoticeLevel.INFO))
        assertEquals(listOf(s2 to PendingStage.QUEUED), states[35].stages())
        val stopped = states[37]
        assertEquals(listOf(ReturnedSend(s1, KIWI, emptyList()), ReturnedSend(s2, mango, emptyList())), stopped.returnedSends)
        assertTrue(stopped.pendingSends.isEmpty())
        assertEquals(listOf(notice), stopped.noticeTexts(NoticeLevel.INFO))
        val end = states.last()
        assertFalse(end.busy)
        assertNull(end.turn)
        assertEquals(1, end.turnsEnded, "no further turn")
        assertEquals(1, end.entries.count { it is ChatEntry.User })
        assertEquals(1, end.noticeTexts(NoticeLevel.INFO).count { it == notice })
    }

    @Test
    fun `queue-during-card, replayed with the host's sends - the message waits through the card and opens its own turn`() {
        // waits
        val c = "<command_uuid-26>"
        val kiwi = "Reply with only the word KIWI."
        val states = hostReplay(
            "queue-during-card",
            mapOf(
                0 to listOf(SessionEvent.UserSent("<command_uuid-4>", "Touch the file")),
                18 to listOf(live(c, kiwi, later = true)),
            ),
        )
        val carded = states[18]
        assertTrue(carded.pendingPermissions.isNotEmpty(), "the permission card is in the state")
        assertEquals(listOf(c to PendingStage.QUEUED), carded.stages())
        assertEquals(listOf(c to PendingStage.QUEUED), states[21].stages())
        val first = states[31]
        assertEquals(1, first.turnsEnded)
        assertEquals(listOf(c), first.pendingSends.map { it.uuid })
        val started = states[33]
        assertTrue(started.pendingSends.isEmpty())
        assertTrue(started.busy)
        val user = assertIs<ChatEntry.User>(started.entries.last())
        assertEquals(c, user.id)
        assertEquals(kiwi, user.text)
        assertFalse(user.folded)
        assertEquals(2, states[43].turnsEnded)
    }

    @Test
    fun `the turn's own lifecycle lines change nothing`() {
        val state = reduce(ChatState(), live("<command_uuid-1>", "Count to a hundred"))
        for (line in listOf(5, 7, 20)) {
            val message = at("interrupt", line)
            assertIs<CliMessage.CommandLifecycle>(message)
            assertSame(state, reduce(state, SessionEvent.Cli(message)), "interrupt:$line")
        }
    }

    @Test
    fun `a live send while a turn runs appends no entry and opens no turn`() {
        val running = reduce(ChatState(), SessionEvent.UserSent("p", "prompt"))
        val sent = reduce(running, SessionEvent.UserSent("x", "hi", live = true))
        assertSame(running.entries, sent.entries)
        assertSame(running.turn, sent.turn)
        assertEquals(listOf("x" to PendingStage.QUEUED), sent.stages())
    }

    @Test
    fun `a loaded transcript's UserSent still replaces the open turn`() {
        val first = reduce(ChatState(), SessionEvent.UserSent("a", "one", atMillis = 1))
        val second = reduce(first, SessionEvent.UserSent("b", "two", atMillis = 2))
        assertEquals("b", second.turn?.userUuid)
        assertEquals(listOf("one", "two"), second.entries.filterIsInstance<ChatEntry.User>().map { it.text })
        assertTrue(second.pendingSends.isEmpty())
    }

    @Test
    fun `a result naming a waiting uuid folds it when no lifecycle came`() {
        val states = hostReplay("queue-fold", mapOf(0 to listOf(live(QP, "Run it")), 18 to listOf(live(QF, KIWI))), skip = setOf(19, 22))
        assertEquals(listOf(QF to PendingStage.QUEUED), states[52].stages())
        val end = states[53]
        assertTrue(end.pendingSends.isEmpty())
        val folded = end.entries[end.entries.lastIndex - 1] as ChatEntry.User
        assertEquals(QF, folded.id)
        assertTrue(folded.folded)
        assertIs<ChatEntry.TurnEnded>(end.entries.last())
    }

    @Test
    fun `the process ending returns waiting messages with a WARNING`() {
        val ended = reduce(waitingKiwi(), SessionEvent.Ended("x", isError = false))
        assertEquals(listOf(ReturnedSend(QF, KIWI, emptyList())), ended.returnedSends)
        assertTrue(ended.pendingSends.isEmpty())
        assertEquals(
            listOf("Not sent: \"Also say the word KIWI at the end.\": the CLI ended before reading it. It is back in the composer."),
            ended.noticeTexts(NoticeLevel.WARNING),
        )
    }

    @Test
    fun `settled returns them with an INFO`() {
        val waiting = reduce(fold(ChatState(), "queue-fold", 1, 6), SessionEvent.UserSent("p", "prompt")).let { reduce(it, live(QF, KIWI)) }
        assertNotNull(waiting.cliCapabilities)
        val settled = waiting.settled()
        assertEquals(listOf(ReturnedSend(QF, KIWI, emptyList())), settled.returnedSends)
        assertTrue(settled.pendingSends.isEmpty())
        assertEquals(listOf("Not sent: \"Also say the word KIWI at the end.\". It is back in the composer."), settled.noticeTexts(NoticeLevel.INFO))
        assertNull(settled.cliCapabilities)
    }

    @Test
    fun `a refused withdraw puts the message back and says so`() {
        val asked = reduce(waitingKiwi(), SessionEvent.WithdrawRequested(QF, restore = true))
        assertEquals(listOf(QF to PendingStage.WITHDRAWING), asked.stages())
        val refused = reduce(asked, SessionEvent.WithdrawRefused(QF, null, KIWI))
        assertEquals(listOf(QF to PendingStage.QUEUED), refused.stages())
        assertEquals(listOf("Claude had already taken \"Also say the word KIWI at the end.\"; it was not withdrawn."), refused.noticeTexts(NoticeLevel.INFO))
    }

    @Test
    fun `a refusal after the message started only says so`() {
        val states = hostReplay("queue-fold", mapOf(0 to listOf(live(QP, "Run it")), 18 to listOf(live(QF, KIWI))))
        var state = reduce(states[19], SessionEvent.WithdrawRequested(QF, restore = true))
        state = fold(state, "queue-fold", 20, 22)
        val started = state.entries.last() as ChatEntry.User
        assertEquals(QF, started.id)
        assertTrue(started.folded)
        assertTrue(state.pendingSends.isEmpty())
        val refused = reduce(state, SessionEvent.WithdrawRefused(QF, null, KIWI))
        assertEquals(listOf("Claude had already taken \"Also say the word KIWI at the end.\"; it was not withdrawn."), refused.noticeTexts(NoticeLevel.INFO))
        assertTrue(refused.pendingSends.isEmpty())
        assertTrue(refused.returnedSends.isEmpty())
        assertEquals(state.entries, refused.entries.dropLast(1))
    }

    @Test
    fun `a lifecycle for an unknown uuid changes nothing`() {
        val state = waitingKiwi()
        assertSame(state, reduce(state, cli("""{"type":"command_lifecycle","command_uuid":"nope","state":"started"}""")))
    }

    @Test
    fun `an init keeps its capabilities, Ended clears them`() {
        val state = fold(ChatState(), "queue-fold", 1, 6)
        val capabilities = assertNotNull(state.cliCapabilities)
        assertTrue("msg_lifecycle_v1" in capabilities)
        assertTrue("interrupt_cancel_queued_v1" in capabilities)
        assertNull(ChatState().cliCapabilities, "none before an init")
        assertNull(reduce(state, SessionEvent.Ended("x", isError = false)).cliCapabilities)
        val init = Json.parseToJsonElement(Fixtures.lines("plain-reply")[3]).jsonObject
        val bare = reduce(ChatState(), SessionEvent.Cli(Ndjson.decode(JsonObject(init - "capabilities").toString())))
        assertEquals(emptySet(), bare.cliCapabilities, "an init without the key lists none")
    }

    @Test
    fun `the first line is cut at 60 characters`() {
        assertEquals("a".repeat(60) + "…", firstLine("a".repeat(61)))
        assertEquals("one", firstLine("one\ntwo"))
        assertEquals("a".repeat(60), firstLine("a".repeat(60)))
    }

    @Test
    fun `ReturnedTaken clears what the composer took back`() {
        val a = ReturnedSend("a", "one", emptyList())
        val b = ReturnedSend("b", "two", emptyList())
        assertEquals(listOf(b), reduce(ChatState(returnedSends = listOf(a, b)), SessionEvent.ReturnedTaken(listOf("a"))).returnedSends)
    }

    @Test
    fun `forkHistory drops waiting and returned messages without a notice`() {
        val source = waitingKiwi().copy(returnedSends = listOf(ReturnedSend("g", "older", emptyList())))
        val fork = source.forkHistory()
        assertTrue(fork.pendingSends.isEmpty())
        assertTrue(fork.returnedSends.isEmpty())
        assertTrue(fork.entries.none { it is ChatEntry.Notice })
        assertEquals(source.copy(pendingSends = emptyList(), returnedSends = emptyList()).settled(), fork)
        assertEquals(listOf(QF), source.pendingSends.map { it.uuid }, "the source keeps its waiting message")
        assertEquals(listOf("g"), source.returnedSends.map { it.uuid })
    }

    // --- Fork from any reply (spec 13.1, 13.2, 9.7) ---

    private fun ChatState.assistant(messageId: String): ChatEntry.Assistant = assistants().single { it.messageId == messageId }

    // spec: lastUuid is the latest complete line
    @Test
    fun `lastUuid is the latest complete line`() {
        val forkAt = replay("fork-at")
        assertEquals("<uuid-14>", forkAt[11].assistants().single().lastUuid)
        assertNull(forkAt[10].assistants().single().lastUuid, "a message drafted from deltas only has no fork point")

        // One message over two complete lines (its thinking line, then its text line), as recorded.
        val mode = replay("permission-mode")
        assertEquals("<uuid-36>", mode[33].assistant("<id-28>").lastUuid)
        assertEquals("<uuid-41>", mode[34].assistant("<id-28>").lastUuid)

        val loaded = runBlocking { TranscriptReader.read(Fixtures.transcript("fork-at-origin")) }.state
        assertEquals("<uuid-14>", loaded.assistants().first().lastUuid, "the transcript's uuid is the stream's")
    }

    /** `fork-at`'s origin (:1 to :55) with its three prompts sent before their inits, as the host would have sent them. */
    private fun forkAtOrigin(): ChatState {
        val sends = mapOf(
            5 to listOf(SessionEvent.UserSent("<command_uuid-4>", ALPHA_PROMPT)),
            18 to listOf(SessionEvent.UserSent("<command_uuid-21>", BRAVO_PROMPT)),
            44 to listOf(SessionEvent.UserSent("<command_uuid-53>", CHARLIE_PROMPT)),
        )
        return hostReplay("fork-at", sends, skip = (56..Fixtures.lines("fork-at").size).toSet())[55]
    }

    // spec: forkedAt keeps the turn and stops at the next User or Shell
    @Test
    fun `forkedAt keeps the turn and stops at the next User or Shell`() {
        val bravoTask = TaskInfo("t-bravo", "<id-28>", null, null, null, null, null, null, null, null)
        val origin = forkAtOrigin().copy(tasks = mapOf("t-bravo" to bravoTask))
        assertEquals(listOf(ALPHA_PROMPT, BRAVO_PROMPT, CHARLIE_PROMPT), origin.entries.filterIsInstance<ChatEntry.User>().map { it.text })
        assertNotNull(origin.context, "the original's ring has a value")

        val fork = assertNotNull(origin.forkedAt("<uuid-14>"))
        assertEquals(listOf(ALPHA_PROMPT), fork.entries.filterIsInstance<ChatEntry.User>().map { it.text })
        val end = assertIs<ChatEntry.TurnEnded>(fork.entries.last(), "the turn and its footer stay")
        assertEquals(origin.entries.filterIsInstance<ChatEntry.TurnEnded>().first(), end)
        assertEquals("OK", fork.text())
        assertTrue(fork.entryIndex.isEmpty())
        assertNull(fork.context)
        assertTrue(fork.tasks.values.none { it.toolUseId == "<id-28>" }, "no task of BRAVO's Bash call")
        assertFalse(fork.busy)
        assertEquals(origin.entries.subList(0, fork.entries.size), fork.entries)

        // A shell escape right after turn one ends the cut as a prompt does.
        val afterTurnOne = origin.entries.indexOf(end) + 1
        val shell = ChatEntry.Shell("sh-1", "ls", "", running = false)
        val withShell = origin.copy(
            entries = origin.entries.subList(0, afterTurnOne) + shell + origin.entries.subList(afterTurnOne, origin.entries.size),
            entryIndex = emptyMap(),
        )
        assertEquals(fork.entries, assertNotNull(withShell.forkedAt("<uuid-14>")).entries)

        assertNull(origin.forkedAt("nope"))
        val agents = replay("agent-sync").last()
        assertTrue(agents.assistants().any { "<uuid-50>" in it.uuids && it.parentToolUseId != null }, "agent-sync:42 is a subagent's line")
        assertNull(agents.forkedAt("<uuid-50>"))
    }

    // spec: EditPhase events set edits and clear clears them
    // The "clear" half is a fork cut, not a /clear: Tandem decodes no conversation_reset and keeps the entries over a /clear, and
    // spec 4.2 says the reviews are cleared with the entries, so they go only where the entries go (plan deviation 11).
    @Test
    fun `EditPhase events set edits and clear clears them`() {
        val queued = EditReview("hello.txt", EditPhase.QUEUED)
        var state = reduce(ChatState(), SessionEvent.EditPhase("t1", queued))
        assertEquals(mapOf("t1" to queued), state.edits)
        val done = EditReview("hello.txt", EditPhase.DONE, EditOutcome.ACCEPTED_CHANGED)
        state = reduce(reduce(state, SessionEvent.EditPhase("t1", EditReview("hello.txt", EditPhase.OPEN))), SessionEvent.EditPhase("t1", done))
        assertEquals(mapOf("t1" to done), state.edits, "each phase replaces the call's review")
        assertEquals(state.edits, state.settled().edits, "a stopped process keeps the reviews with the entries")

        // A fork cut drops the reviews of the calls it leaves out.
        val origin = forkAtOrigin().copy(edits = mapOf("<id-28>" to done))
        assertEquals(emptyMap(), assertNotNull(origin.forkedAt("<uuid-14>")).edits)
    }

    // spec: AcceptAll sets and the result resets acceptAll
    @Test
    fun `AcceptAll sets and the result resets acceptAll`() {
        val on = reduce(ChatState(busy = true), SessionEvent.AcceptAll(true))
        assertTrue(on.acceptAll)
        assertFalse(reduce(on, SessionEvent.AcceptAll(false)).acceptAll)
        val result = Fixtures.lines("ide-edit")[71 - 1]
        assertFalse(reduce(on, cli(result)).acceptAll, "the turn's result ends it (ide-edit:71)")
        assertFalse(reduce(on, SessionEvent.Ended("The claude CLI exited.", isError = false)).acceptAll, "so does the process's end")
        assertFalse(on.settled().acceptAll)
    }

    // spec: the Tandem can_use_tool is dropped
    @Test
    fun `the Tandem can_use_tool is dropped`() {
        val request = Fixtures.lines("ide-edit")[37 - 1]
        val state = reduce(ChatState(busy = true), cli(request))
        assertEquals(emptyList(), state.pendingPermissions)
        // Another MCP tool's request still raises its card.
        val foreign = request.replace("mcp__tandem__edit_file", "mcp__echo__echo")
        assertEquals(listOf("mcp__echo__echo"), reduce(ChatState(busy = true), cli(foreign)).pendingPermissions.map { it.toolName })
    }

    // spec: a user-rejected result without a decision keeps its kind
    @Test
    fun `a user-rejected result without a decision keeps its kind`() {
        val result = reduce(ChatState(), cli(Fixtures.lines("ide-edit-stop")[44 - 1])).entries.filterIsInstance<ChatEntry.ToolResult>().single()
        assertEquals("user-rejected", result.nonExecutionKind)
        assertNull(result.decision)
        assertTrue(result.isError)
        // An accepted call keeps its decision and has no kind (ide-edit:41).
        val accepted = reduce(ChatState(), cli(Fixtures.lines("ide-edit")[41 - 1])).entries.filterIsInstance<ChatEntry.ToolResult>().single()
        assertEquals("accept", accepted.decision?.decision)
        assertNull(accepted.nonExecutionKind)
    }

    // spec: a sent message keeps its chips
    @Test
    fun `a sent message keeps its chips`() {
        val chip = ContextChip(ContextChipKind.UNSAVED_FILE, "A.kt (unsaved)", "src/A.kt")
        val sent = reduce(ChatState(), SessionEvent.UserSent("u1", "fix src/A.kt", live = true, context = listOf(chip)))
        assertEquals(listOf(chip), sent.entries.filterIsInstance<ChatEntry.User>().single().context)
        // One sent while a turn runs waits with its chips, and its entry takes them when it starts, or folds.
        val waiting = reduce(sent, SessionEvent.UserSent("u2", "again", live = true, context = listOf(chip)))
        assertEquals(listOf(chip), waiting.pendingSends.single().context)
        val started = reduce(waiting, cli("""{"type":"command_lifecycle","command_uuid":"u2","state":"started"}"""))
        assertEquals(listOf(chip), started.entries.filterIsInstance<ChatEntry.User>().last().context)
        val folded = reduce(ChatState(), SessionEvent.UserSent("u3", "loaded", folded = true, context = listOf(chip)))
        assertEquals(listOf(chip), folded.entries.filterIsInstance<ChatEntry.User>().single().context)
        // Without chips, none.
        assertEquals(emptyList(), reduce(ChatState(), SessionEvent.UserSent("u4", "plain")).entries.filterIsInstance<ChatEntry.User>().single().context)
    }

    // spec 3.5: a withdrawn send comes back as typed, so the next send swaps against the buffer as it is then
    @Test
    fun `a cancelled send with context comes back to the composer as typed`() {
        val chip = ContextChip(ContextChipKind.UNSAVED_FILE, "A.kt (unsaved)", "src/A.kt")
        val busy = reduce(ChatState(), SessionEvent.UserSent("u1", "first", live = true))
        val waiting = reduce(busy, SessionEvent.UserSent("u2", "fix src/A.kt", live = true, context = listOf(chip), typed = "fix @src/A.kt"))
        assertEquals("fix @src/A.kt", waiting.pendingSends.single().typed)
        // Edit withdraws it through command_lifecycle: the composer gets the `@` back, not the bare path the CLI was sent.
        val asked = reduce(waiting, SessionEvent.WithdrawRequested("u2", restore = true))
        val cancelled = reduce(asked, cli("""{"type":"command_lifecycle","command_uuid":"u2","state":"cancelled"}"""))
        assertTrue(cancelled.pendingSends.isEmpty())
        assertEquals(listOf(ReturnedSend("u2", "fix @src/A.kt", emptyList())), cancelled.returnedSends)
        // Stop's cancelled line, with no withdraw asked, returns it as typed too; its notice quotes what was sent.
        val stopped = reduce(waiting, cli("""{"type":"command_lifecycle","command_uuid":"u2","state":"cancelled"}"""))
        assertEquals(listOf(ReturnedSend("u2", "fix @src/A.kt", emptyList())), stopped.returnedSends)
        assertEquals(listOf(notSentText("fix src/A.kt")), stopped.noticeTexts(NoticeLevel.INFO))
        // A send without a dirty mention has the same text both ways.
        val plain = reduce(busy, SessionEvent.UserSent("u3", "again", live = true))
        assertEquals("again", plain.pendingSends.single().typed)
        // One that starts keeps the sent text in its entry.
        val started = reduce(waiting, cli("""{"type":"command_lifecycle","command_uuid":"u2","state":"started"}"""))
        assertEquals("fix src/A.kt", started.entries.filterIsInstance<ChatEntry.User>().last().text)
    }
}

/** A complete main-thread `assistant` line with one text block. */
private const val REPLY_LINE: String =
    """{"type":"assistant","uuid":"a1","message":{"id":"m1","model":"claude-sonnet-5-5","content":[{"type":"text","text":"pong"}]}}"""

// The queue fixtures' prompts and the messages written while they ran (plan, Global Constraints).
private const val QP: String = "<command_uuid-4>"
private const val QF: String = "<command_uuid-25>"
private const val QP1: String = "<command_uuid-4>"
private const val QF1: String = "<command_uuid-29>"
private const val QP2: String = "<command_uuid-47>"
private const val QF2: String = "<command_uuid-68>"
private const val KIWI: String = "Also say the word KIWI at the end."

// The fork-at origin's three prompts (transcripts/fork-at-origin.jsonl:5, :27, :35).
private const val ALPHA_PROMPT: String = "Remember the word ALPHA. Reply with exactly one word: OK"
private const val BRAVO_PROMPT: String =
    "Remember the word BRAVO. First run the shell command `echo hi` with the Bash tool, then reply with exactly one word: OK"
private const val CHARLIE_PROMPT: String = "Remember the word CHARLIE. Reply with exactly one word: OK"
