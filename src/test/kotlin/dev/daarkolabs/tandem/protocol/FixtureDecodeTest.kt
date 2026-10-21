package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Test
import java.time.Instant
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureDecodeTest {
    @Test
    fun `a transcript fixture that is not there fails at once, naming it`() {
        val failure = assertFailsWith<IllegalArgumentException> { Fixtures.transcript("no-such-fixture") }
        assertTrue("no-such-fixture" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `every fixture line decodes and core types are never Unknown`() {
        val core = setOf("assistant", "user", "result", "stream_event", "control_request", "control_response")
        for (name in Fixtures.names()) {
            for (line in Fixtures.lines(name)) {
                val message = Ndjson.decode(line)
                if (message is CliMessage.Unknown) {
                    assertTrue(message.type !in core, "$name: core type decoded as Unknown: ${message.type}")
                }
            }
        }
    }

    @Test
    fun `plain reply has init, streamed text, complete assistant before content_block_stop, and a result`() {
        val messages = Fixtures.decode("plain-reply")

        val init = messages.filterIsInstance<CliMessage.SystemInit>().single()
        assertEquals("claude-sonnet-5-5", init.model)
        assertEquals("2.1.300", init.claudeCodeVersion)

        val deltas = messages.filterIsInstance<CliMessage.StreamEvent>().map { it.event }
            .filterIsInstance<StreamEvent.ContentBlockDelta>().map { it.delta }
        assertEquals(listOf<Delta>(Delta.Text("pong")), deltas)

        val assistantIndex = messages.indexOfFirst { it is CliMessage.Assistant }
        val stopIndex = messages.indexOfFirst { it is CliMessage.StreamEvent && it.event is StreamEvent.ContentBlockStop }
        assertTrue(assistantIndex in 0 until stopIndex, "the fixture shows the complete message before content_block_stop")

        val assistant = messages[assistantIndex] as CliMessage.Assistant
        assertEquals(listOf<ContentBlock>(ContentBlock.Text("pong")), assistant.content)
        val start = messages.firstNotNullOf { (it as? CliMessage.StreamEvent)?.event as? StreamEvent.MessageStart }
        assertEquals(start.messageId, assistant.messageId)

        val result = messages.filterIsInstance<CliMessage.Result>().single()
        assertEquals("success", result.subtype)
        assertEquals(false, result.isError)
        assertEquals("pong", result.result)
    }

    @Test
    fun `stream frames carry the api message id of their message_start`() {
        val events = Fixtures.decode("plain-reply").filterIsInstance<CliMessage.StreamEvent>()
        val start = events.firstNotNullOf { it.event as? StreamEvent.MessageStart }
        assertTrue(events.all { it.apiMessageId == start.messageId })
    }

    @Test
    fun `permission fixture asks for each Bash call and the answers show up as tool results`() {
        val messages = Fixtures.decode("permission")

        val asks = messages.filterIsInstance<CliMessage.ControlRequest>().map { it.request }
        assertEquals(2, asks.size)
        val toolUses = messages.filterIsInstance<CliMessage.Assistant>().flatMap { it.content }.filterIsInstance<ContentBlock.ToolUse>()
        asks.zip(toolUses).forEach { (ask, toolUse) ->
            val canUseTool = assertIs<InboundRequest.CanUseTool>(ask)
            assertEquals("Bash", canUseTool.toolName)
            assertEquals(toolUse.id, canUseTool.toolUseId)
            assertEquals(toolUse.input, canUseTool.input)
            assertTrue(canUseTool.permissionSuggestions.isNotEmpty())
        }

        val results = messages.filterIsInstance<CliMessage.User>().flatMap { it.content }.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(listOf(false, true), results.map { it.isError })
    }

    @Test
    fun `interrupt fixture acknowledges the interrupt and ends with an error result`() {
        val messages = Fixtures.decode("interrupt")

        val receipt = messages.filterIsInstance<CliMessage.ControlResponse>().last()
        assertTrue(receipt.isSuccess)
        assertTrue(receipt.payload?.containsKey("still_queued") == true)

        val result = messages.filterIsInstance<CliMessage.Result>().single()
        assertEquals("error_during_execution", result.subtype)
        assertTrue(result.isError)
        assertEquals("aborted_streaming", result.terminalReason)
    }

    @Test
    fun `model switch fixture reports the new model in the next init and assistant message`() {
        val messages = Fixtures.decode("model-switch")

        assertEquals(listOf("claude-sonnet-5-5", "claude-haiku-5-5"), messages.filterIsInstance<CliMessage.SystemInit>().map { it.model })
        assertEquals(listOf("claude-sonnet-5-5", "claude-haiku-5-5"), messages.filterIsInstance<CliMessage.Assistant>().map { it.model })

        val setModelAck = messages.filterIsInstance<CliMessage.ControlResponse>().last()
        assertTrue(setModelAck.isSuccess)
        assertNull(setModelAck.payload, "set_model succeeds without a payload")
    }

    @Test
    fun `permission mode fixture shows how the CLI acknowledges set_permission_mode`() {
        val messages = Fixtures.decode("permission-mode")

        val responses = messages.filterIsInstance<CliMessage.ControlResponse>()
        assertEquals(3, responses.count { it.isSuccess }) // initialize + two acks
        assertEquals(
            listOf(PermissionMode.DEFAULT, PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS),
            messages.filterIsInstance<CliMessage.SystemInit>().map { it.permissionMode },
        )

        // Each ack echoes the mode, and a system/status line with that mode comes right after it.
        val acks = responses.drop(1)
        assertEquals(listOf("plan", "acceptEdits").map { mode -> buildJsonObject { put("mode", mode) } }, acks.map { it.payload })
        for ((ack, mode) in acks.zip(listOf(PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS))) {
            val status = assertIs<CliMessage.SystemStatus>(messages[messages.indexOf(ack) + 1])
            assertEquals(mode, status.permissionMode)
        }
    }

    // Plain branch: CLI 2.1.306 takes an effortLevel over stdio without checking it ("bogus" too, and with CLAUDE_CODE_EFFORT_LEVEL set),
    // so the scenario's refused request is one whose settings is not an object (scripts/drive_cli.py effort_switch, docs/protocol.md 4.4).
    @Test
    fun `an apply_flag_settings success and error decode`() {
        val responses = Fixtures.decode("effort-switch").filterIsInstance<CliMessage.ControlResponse>()
        assertEquals(3, responses.size) // initialize, then the two apply_flag_settings answers
        val high = responses[1]
        assertTrue(high.isSuccess)
        assertNull(high.payload, "the recorded success has no response field")
        val refused = responses[2]
        assertTrue(!refused.isSuccess)
        assertEquals("apply_flag_settings requires `settings` to be an object, got string", refused.error)
    }

    // The recording shows a success: {"mode":"auto"}, then a system/status line in auto and the next init in auto.
    @Test
    fun `set_permission_mode auto is answered as recorded`() {
        val messages = Fixtures.decode("permission-mode-auto")
        val ack = messages.filterIsInstance<CliMessage.ControlResponse>()[1]
        assertTrue(ack.isSuccess)
        assertEquals(buildJsonObject { put("mode", "auto") }, ack.payload)
        assertEquals(PermissionMode.AUTO, assertIs<CliMessage.SystemStatus>(messages[messages.indexOf(ack) + 1]).permissionMode)
        assertEquals(
            listOf(PermissionMode.DEFAULT, PermissionMode.AUTO),
            messages.filterIsInstance<CliMessage.SystemInit>().map { it.permissionMode },
        )
    }

    @Test
    fun `fork fixture resumes under a new session id`() {
        val ids = Fixtures.decode("fork").filterIsInstance<CliMessage.SystemInit>().map { it.sessionId }

        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `transcript fixtures decode line by line without throwing`() {
        for (name in listOf("history-reply", "history-permission", "history-interrupt", "fork", "fork-origin")) {
            val lines = Fixtures.transcript(name).readLines().filter { it.isNotBlank() }
            assertTrue(lines.isNotEmpty(), "$name: empty transcript")
            lines.forEach(Ndjson::decode)
        }
    }

    @Test
    fun `the original session's transcript keeps the title the host's rename_session gave it`() {
        val titles = Fixtures.transcript("fork-origin").readLines().filter { it.isNotBlank() }.map(Ndjson::decode)
            .filterIsInstance<CliMessage.Unknown>().filter { it.type == "custom-title" }
            .map { it.raw?.get("customTitle")?.jsonPrimitive?.content }
        assertEquals(listOf("Renamed by host"), titles)
    }

    @Test
    fun `undocumented types the fixtures show decode to Unknown with their type`() {
        val unknownTypes = Fixtures.names().flatMap(Fixtures::decode).filterIsInstance<CliMessage.Unknown>().map { it.type }.toSet()
        assertTrue("autocompact_state" in unknownTypes)
        assertTrue("command_lifecycle" in unknownTypes)
    }

    /** Line [line] (1-based) of fixture [name], decoded. */
    private fun at(name: String, line: Int): CliMessage = Ndjson.decode(Fixtures.lines(name)[line - 1])

    private fun request(name: String, line: Int): InboundRequest.CanUseTool =
        assertIs<InboundRequest.CanUseTool>(assertIs<CliMessage.ControlRequest>(at(name, line)).request)

    private fun event(name: String, line: Int): StreamEvent = assertIs<CliMessage.StreamEvent>(at(name, line)).event

    private fun JsonElement?.field(key: String): JsonElement = assertNotNull(assertIs<JsonObject>(this)[key], key)

    // spec: ask-single: the question request decodes with requires_user_interaction and the answer result carries tool_use_result
    @Test
    fun `ask-single - the question request decodes with requires_user_interaction and the answer result carries tool_use_result`() {
        val ask = request("ask-single", 22)
        assertTrue(ask.requiresUserInteraction)
        assertTrue(ask.permissionSuggestions.isEmpty())
        assertEquals(2, ask.input.field("questions").jsonArray[0].field("options").jsonArray.size)

        val answer = assertIs<CliMessage.User>(at("ask-single", 26))
        assertTrue("answers" in assertIs<JsonObject>(answer.toolUseResult))
        assertEquals("<id-12>", answer.toolResultMeta[0].id)
        assertEquals("accept", answer.toolResultMeta[0].decision)
        assertEquals("user_temporary", answer.toolResultMeta[0].source)
    }

    // spec: ask-deny: a denied question result is an error string with a reject decision
    @Test
    fun `ask-deny - a denied question result is an error string with a reject decision`() {
        val denied = assertIs<CliMessage.User>(at("ask-deny", 24))
        assertEquals(JsonPrimitive("Error: cancelled"), denied.toolUseResult)
        val meta = denied.toolResultMeta.single()
        assertEquals("permission-rule", meta.nonExecutionKind)
        assertEquals("reject", meta.decision)
        assertEquals("user_reject", meta.source)
    }

    // spec: ask-deny: result lists the denied call
    @Test
    fun `ask-deny - result lists the denied call`() {
        val result = assertIs<CliMessage.Result>(at("ask-deny", 40))
        assertEquals(listOf("AskUserQuestion"), result.permissionDenials.map { it.toolName })
        assertEquals("<id-12>", result.permissionDenials.single().toolUseId)
    }

    // spec: plan-approve: ExitPlanMode carries the plan and the status line names the new mode
    @Test
    fun `plan-approve - ExitPlanMode carries the plan and the status line names the new mode`() {
        val exit = request("plan-approve", 88)
        assertEquals("ExitPlanMode", exit.toolName)
        val plan = assertIs<JsonPrimitive>(exit.input["plan"])
        assertTrue(plan.isString && plan.content.isNotBlank())
        assertTrue(exit.permissionSuggestions.isEmpty())
        assertEquals(PermissionMode.ACCEPT_EDITS, assertIs<CliMessage.SystemStatus>(at("plan-approve", 90)).permissionMode)
    }

    // spec: plan-disallowed: ExitPlanMode with an empty input still decodes as a permission request
    @Test
    fun `plan-disallowed - ExitPlanMode with an empty input still decodes as a permission request`() {
        val exit = request("plan-disallowed", 173)
        assertEquals("ExitPlanMode", exit.toolName)
        assertTrue(exit.input.isEmpty())
        assertTrue(exit.requiresUserInteraction)
    }

    // spec: think-display-summarized: thinking deltas carry text and thinking_tokens lines decode
    @Test
    fun `think-display-summarized - thinking deltas carry text and thinking_tokens lines decode`() {
        val tokens = assertIs<CliMessage.ThinkingTokens>(at("think-display-summarized", 10))
        assertEquals(1, tokens.estimatedTokens)
        assertEquals(1, tokens.estimatedTokensDelta)
        assertEquals("<command_uuid-4>", tokens.userMessageUuid)
        assertEquals(StreamEvent.ContentBlockDelta(0, Delta.Thinking("47", estimatedTokens = null)), event("think-display-summarized", 11))
        val assistant = assertIs<CliMessage.Assistant>(at("think-display-summarized", 34))
        assertEquals(1991L, assistant.thinkingDurationMs)
        assertEquals("summarized", assistant.thinkingDisplay)
    }

    // spec: think-display-summarized: message_start names the thinking display
    @Test
    fun `think-display-summarized - message_start names the thinking display`() {
        assertEquals("summarized", assertIs<StreamEvent.MessageStart>(event("think-display-summarized", 8)).thinkingDisplay)
    }

    // spec: misc-state-emit: message_start input tokens sum the three input fields
    @Test
    fun `misc-state-emit - message_start input tokens sum the three input fields`() {
        val start = assertIs<StreamEvent.MessageStart>(event("misc-state-emit", 9))
        assertEquals(31260L, start.inputTokens) // 2 + 4720 + 26538
        assertEquals(assertNotNull(at("misc-state-emit", 16).raw).field("first_request_input_tokens").jsonPrimitive.long, start.inputTokens)
    }

    // spec: misc-image: message_delta carries thinking tokens
    @Test
    fun `misc-image - message_delta carries thinking tokens`() {
        val delta = assertIs<StreamEvent.MessageDelta>(event("misc-image", 48))
        assertEquals(62, delta.thinkingTokens)
        assertEquals(135, delta.outputTokens)
        assertEquals("tool_use", delta.stopReason)
    }

    // spec: agent-sync: subagent user lines carry subagent_type, task_description and a timestamp
    @Test
    fun `agent-sync - subagent user lines carry subagent_type, task_description and a timestamp`() {
        val user = assertIs<CliMessage.User>(at("agent-sync", 38))
        assertEquals("general-purpose", user.subagentType)
        assertEquals("Count lines in seed.txt", user.taskDescription)
        assertEquals(Instant.parse("2026-10-18T20:12:55.307Z").toEpochMilli(), user.timestampMillis)
    }

    // spec: agent-forwarded: the forwarded assistant line carries a timestamp
    @Test
    fun `agent-forwarded - the forwarded assistant line carries a timestamp`() {
        assertNotNull(assertIs<CliMessage.Assistant>(at("agent-forwarded", 38)).timestampMillis)
    }

    // spec: permission: result carries duration_api_ms and num_turns
    @Test
    fun `permission - result carries duration_api_ms and num_turns`() {
        val result = assertIs<CliMessage.Result>(at("permission", 35))
        assertEquals(3384L, result.durationApiMs)
        assertEquals(2, result.numTurns)
        assertEquals(5223L, result.durationMs)
    }

    // spec: misc-state-action: a default-mode request carries blocked_path and three suggestions
    @Test
    fun `misc-state-action - a default-mode request carries blocked_path and three suggestions`() {
        val ask = request("misc-state-action", 21)
        assertEquals("<path>", ask.blockedPath)
        assertEquals(listOf("addRules", "addDirectories", "setMode"), ask.permissionSuggestions.map { it.field("type").jsonPrimitive.content })
    }

    // spec: think-default: empty thinking deltas carry estimated_tokens
    @Test
    fun `think-default - empty thinking deltas carry estimated_tokens`() {
        assertEquals(StreamEvent.ContentBlockDelta(0, Delta.Thinking("", estimatedTokens = 50)), event("think-default", 10))
        val assistant = assertIs<CliMessage.Assistant>(at("think-default", 16))
        assertEquals(listOf<ContentBlock>(ContentBlock.Thinking("")), assistant.content)
        assertEquals(2143L, assistant.thinkingDurationMs)
    }

    // spec: think-summarized: a set_max_thinking_tokens success has no response and the full value is an error
    @Test
    fun `think-summarized - a set_max_thinking_tokens success has no response and the full value is an error`() {
        val success = assertIs<CliMessage.ControlResponse>(at("think-summarized", 25))
        assertTrue(success.isSuccess)
        assertNull(success.payload)
        val failure = assertIs<CliMessage.ControlResponse>(at("think-summarized", 73))
        assertTrue(!failure.isSuccess)
        assertTrue(assertNotNull(failure.error).startsWith("set_max_thinking_tokens:"), failure.error)
    }

    // spec: tools-read: Read results carry the file view
    @Test
    fun `tools-read - Read results carry the file view`() {
        val read = assertIs<CliMessage.User>(at("tools-read", 21))
        assertEquals(31, read.toolUseResult.field("file").field("numLines").jsonPrimitive.int)
        assertEquals("config", read.toolResultMeta[0].source)
        assertEquals("mode", read.toolResultMeta[0].reasonType)
        val offset = assertIs<CliMessage.User>(at("tools-read", 38))
        assertEquals(10, offset.toolUseResult.field("file").field("startLine").jsonPrimitive.int)
        assertEquals("other", assertIs<CliMessage.User>(at("plan-approve", 73)).toolResultMeta[0].reasonType)
        val bash = assertIs<CliMessage.User>(at("tools-bash", 23)).toolResultMeta[0]
        assertEquals("user_temporary", bash.source)
        assertNull(bash.reasonType)
    }

    // spec: tools-edit: Edit and Write results carry structuredPatch
    @Test
    fun `tools-edit - Edit and Write results carry structuredPatch`() {
        val patch = assertIs<CliMessage.User>(at("tools-edit", 44)).toolUseResult.field("structuredPatch").jsonArray
        assertEquals(1, patch.size)
        val lines = patch[0].field("lines").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("-line 07 golf" in lines && "+line 07 GOLF" in lines, lines.toString())
        assertEquals("create", assertIs<CliMessage.User>(at("tools-edit", 61)).toolUseResult.field("type").jsonPrimitive.content)
    }

    // spec: tools-bash: a Bash failure is an error string and the request names its reason type
    @Test
    fun `tools-bash - a Bash failure is an error string and the request names its reason type`() {
        assertEquals("subcommandResults", request("tools-bash", 20).decisionReasonType)
        val failed = assertIs<CliMessage.User>(at("tools-bash", 39))
        assertTrue(assertIs<ContentBlock.ToolResult>(failed.content.single()).isError)
        val result = assertIs<JsonPrimitive>(failed.toolUseResult)
        assertTrue(result.isString && result.content.startsWith("Error: Exit code 3"), result.content)
    }

    // spec: tools-read-unavailable: an unknown tool error has no meta
    @Test
    fun `tools-read-unavailable - an unknown tool error has no meta`() {
        val failed = assertIs<CliMessage.User>(at("tools-read-unavailable", 49))
        assertTrue(assertIs<ContentBlock.ToolResult>(failed.content.single()).isError)
        assertTrue(failed.toolResultMeta.isEmpty())
    }

    // spec: agent-sync: task lines decode and subagent lines carry agent ids
    @Test
    fun `agent-sync - task lines decode and subagent lines carry agent ids`() {
        val started = assertIs<CliMessage.TaskStarted>(at("agent-sync", 37))
        assertEquals("local_agent", started.taskType)
        assertEquals("Count lines in seed.txt", started.description)
        assertEquals("general-purpose", started.subagentType)
        assertEquals(false, started.isBackgrounded)
        assertEquals("<id-12>", started.toolUseId)

        val progress = assertIs<CliMessage.TaskProgress>(at("agent-sync", 41))
        assertEquals(started.taskId, progress.taskId)
        assertEquals("Bash", progress.lastToolName)
        assertEquals(TaskUsage(totalTokens = 21510L, toolUses = 1, durationMs = 2006L), progress.usage)
        assertEquals("Running wc -l seed.txt && tail -c1 seed.txt | xxd", progress.description)

        assertEquals("completed", assertIs<CliMessage.TaskUpdated>(at("agent-sync", 48)).status)

        val notification = assertIs<CliMessage.TaskNotification>(at("agent-sync", 49))
        assertNotNull(notification.summary)
        assertEquals("completed", notification.status)
        assertEquals("<id-12>", notification.toolUseId)
        assertEquals(TaskUsage(totalTokens = 21826L, toolUses = 2, durationMs = 5826L), notification.usage)

        assertNotNull(request("agent-sync", 43).agentId)
        assertEquals(started.taskId, assertIs<CliMessage.User>(at("agent-sync", 38)).agentId)

        val subagentBash = assertIs<CliMessage.User>(at("agent-sync", 47))
        assertNull(subagentBash.toolUseResult)
        assertEquals("subcommandResults", subagentBash.toolResultMeta[0].reasonType)
    }

    // spec: agent-bash-task: a Bash task line decodes with task_type local_bash
    @Test
    fun `agent-bash-task - a Bash task line decodes with task_type local_bash`() {
        val started = assertIs<CliMessage.TaskStarted>(at("agent-bash-task", 21))
        assertEquals("local_bash", started.taskType)
        assertNull(started.subagentType)
        assertNull(assertIs<CliMessage.TaskNotification>(at("agent-bash-task", 22)).usage)
    }

    // spec: agent-forwarded: the forwarded subagent text is an assistant line with agent_id
    @Test
    fun `agent-forwarded - the forwarded subagent text is an assistant line with agent_id`() {
        val forwarded = assertIs<CliMessage.Assistant>(at("agent-forwarded", 38))
        assertNotNull(forwarded.agentId)
        assertNotNull(forwarded.parentToolUseId)
        assertEquals(listOf<ContentBlock>(ContentBlock.Text("7")), forwarded.content)
        assertEquals("general-purpose", forwarded.subagentType)
        assertEquals("Count lines in seed.txt", forwarded.taskDescription)
    }

    // spec: misc-image: an image result is a block array and an image tool_use_result
    @Test
    fun `misc-image - an image result is a block array and an image tool_use_result`() {
        val user = assertIs<CliMessage.User>(at("misc-image", 26))
        val blocks = assertIs<JsonArray>(assertIs<ContentBlock.ToolResult>(user.content.single()).content)
        assertTrue(blocks.any { it.field("type").jsonPrimitive.content == "image" })
        assertEquals("image", user.toolUseResult.field("type").jsonPrimitive.content)
        val dimensions = user.toolUseResult.field("file").field("dimensions")
        assertEquals(8, dimensions.field("displayWidth").jsonPrimitive.int)
        assertEquals(8, dimensions.field("displayHeight").jsonPrimitive.int)
    }

    // spec: misc-web: ToolSearch, WebFetch and WebSearch results decode
    @Test
    fun `misc-web - ToolSearch, WebFetch and WebSearch results decode`() {
        val search = assertIs<ContentBlock.ToolResult>(assertIs<CliMessage.User>(at("misc-web", 17)).content.single())
        assertTrue(assertIs<JsonArray>(search.content).any { it.field("type").jsonPrimitive.content == "tool_reference" })
        assertTrue(assertIs<CliMessage.User>(at("misc-web", 34)).toolUseResult.field("result").jsonPrimitive.isString)
        val results = assertIs<CliMessage.User>(at("misc-web", 78)).toolUseResult.field("results").jsonArray
        assertIs<JsonArray>(results[0].field("content"))
    }

    // spec: permission: message_delta usage and result durations decode
    @Test
    fun `permission - message_delta usage and result durations decode`() {
        assertNotNull(assertIs<StreamEvent.MessageDelta>(event("permission", 22)).outputTokens)
        val result = assertIs<CliMessage.Result>(at("permission", 35))
        assertNotNull(result.durationMs)
        assertEquals(90, result.outputTokens)
        assertTrue(result.permissionDenials.isEmpty())
    }

    // spec: misc-state-emit: session_state_changed and rate_limit_event stay Unknown
    @Test
    fun `misc-state-emit - session_state_changed and rate_limit_event stay Unknown`() {
        assertEquals("session_state_changed", assertIs<CliMessage.Unknown>(at("misc-state-emit", 3)).subtype)
        assertEquals("rate_limit_event", assertIs<CliMessage.Unknown>(at("misc-state-emit", 5)).type)
    }

    @Test
    fun `invalid-model - the assistant error line carries api_error_status`() {
        val error = assertIs<CliMessage.Assistant>(at("invalid-model", 4))
        assertEquals(404, error.apiErrorStatus)
        assertEquals("model_not_found", error.error)
    }

    // spec: transcripts: on-disk tool results and thinking fields decode with their camelCase names
    @Test
    fun `transcripts - on-disk tool results and thinking fields decode with their camelCase names`() {
        fun transcript(name: String): List<CliMessage> = Fixtures.transcript(name).readLines().filter { it.isNotBlank() }.map(Ndjson::decode)

        val denied = assertIs<CliMessage.User>(transcript("ask-deny")[19])
        assertNotNull(denied.toolUseResult)
        val meta = denied.toolResultMeta.single()
        assertEquals("<id-12>", meta.id)
        assertEquals("reject", meta.decision)
        assertNotNull(meta.nonExecutionKind)
        assertNotNull(denied.timestampMillis)

        val users = transcript("tools-edit").filterIsInstance<CliMessage.User>()
        assertTrue(users.any { (it.toolUseResult as? JsonObject)?.containsKey("structuredPatch") == true })
        assertTrue(users.any { user -> user.toolResultMeta.any { it.reasonType == "mode" } }, "on-disk reasonType")

        assertTrue(transcript("think-default").filterIsInstance<CliMessage.Assistant>().any { it.thinkingDurationMs == 2143L })
    }
}
