package dev.daarkolabs.tandem.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    fun `a user line with an image decodes a typed Image block`() {
        val user = Fixtures.transcript("user-image").readLines().filter { it.isNotBlank() }.map(Ndjson::decode)
            .filterIsInstance<CliMessage.User>().first()
        val image = assertIs<ContentBlock.Image>(user.content[1])
        assertEquals(listOf(ContentBlock.Text("What colour is this image? Reply with one word."), image), user.content)
        assertEquals("image/png", image.mediaType)
        val bytes = java.util.Base64.getDecoder().decode(image.data)
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), bytes.take(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `fork fixture resumes under a new session id`() {
        val ids = Fixtures.decode("fork").filterIsInstance<CliMessage.SystemInit>().map { it.sessionId }

        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `transcript fixtures decode line by line without throwing`() {
        val names = listOf(
            "history-reply", "history-permission", "history-interrupt", "fork", "fork-origin",
            "fork-at", "fork-at-origin", "queue-fold", "queue-later", "queue-cancel",
            "queue-steer-text", "queue-cancel-steer", "fork-at-live", "fork-at-live-origin", "queue-later-command",
            "queue-during-card", "fork-at-busy", "fork-at-busy-origin",
        )
        for (name in names) {
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
        assertTrue("rate_limit_event" in unknownTypes)
        assertTrue("command_lifecycle" !in unknownTypes)
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

    // polish spec 3.1: the result carries each model's context window
    @Test
    fun `the result carries each model's context window`() {
        val windows = mapOf("claude-haiku-5-5" to 1_000_000L, "claude-sonnet-5-5" to 1_000_000L)
        assertEquals(windows, assertIs<CliMessage.Result>(at("misc-image", 66)).contextWindows)
        val plain = assertIs<CliMessage.Result>(at("plain-reply", 15))
        assertEquals(windows, plain.contextWindows)
        assertEquals(setOf("claude-haiku-5-5", "claude-sonnet-5-5"), plain.modelUsage)
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

    private fun init(name: String, line: Int): CliMessage.SystemInit = assertIs<CliMessage.SystemInit>(at(name, line))

    private fun response(name: String, line: Int): CliMessage.ControlResponse = assertIs<CliMessage.ControlResponse>(at(name, line))

    private fun servers(name: String, line: Int): List<McpServerInfo> = assertNotNull(ControlPayloads.mcpStatus(response(name, line).payload)).servers

    // spec: mcp-status: init carries the thin server list and redacted command lists
    @Test
    fun `mcp-status - init carries the thin server list and redacted command lists`() {
        val init = init("mcp-status", 6)
        assertEquals(
            listOf(
                McpServerSummary("echo", McpStatus.CONNECTED, "connected", "dynamic"),
                McpServerSummary("broken", McpStatus.FAILED, "failed", "dynamic"),
                McpServerSummary("authy", McpStatus.NEEDS_AUTH, "needs-auth", "dynamic"),
            ),
            init.mcpServers,
        )
        assertTrue(init.slashCommands.isEmpty())
        assertTrue(init.terminalSlashCommands.isEmpty())
    }

    // spec: mcp-status: a disabled server leaves init.tools and comes back
    @Test
    fun `mcp-status - a disabled server leaves init dot tools and comes back`() {
        assertTrue("mcp__echo__echo" in init("mcp-status", 6).tools)

        val disabled = init("mcp-status", 23)
        assertEquals(McpStatus.DISABLED, disabled.mcpServers.single { it.name == "echo" }.status)
        assertTrue("mcp__echo__echo" !in disabled.tools)

        val back = init("mcp-status", 48)
        assertEquals(McpStatus.CONNECTED, back.mcpServers.single { it.name == "echo" }.status)
        assertTrue("mcp__echo__echo" in back.tools)
    }

    // spec: mcp-status: mcp_status decodes every entry shape
    @Test
    fun `mcp-status - mcp_status decodes every entry shape`() {
        val (echo, broken, authy) = servers("mcp-status", 18)
        assertEquals("echo", echo.name)
        assertEquals(McpStatus.CONNECTED, echo.status)
        assertEquals("0.1.0", assertNotNull(echo.serverInfo).version)
        val echoConfig = assertNotNull(echo.config)
        assertEquals("stdio", echoConfig.type)
        assertEquals("/usr/bin/python3", echoConfig.command)
        assertEquals(2, echoConfig.args.size)
        assertEquals(listOf(McpTool("echo")), echo.tools)
        assertEquals("dynamic", echo.source)
        assertEquals("dynamic", echo.scope)

        assertEquals("broken", broken.name)
        assertEquals("Connection closed", broken.error)
        assertNull(broken.tools)

        assertEquals("authy", authy.name)
        val authyConfig = assertNotNull(authy.config)
        assertEquals("http", authyConfig.type)
        assertEquals("http://127.0.0.1:33041/mcp", authyConfig.url)
        assertNull(authy.error)
    }

    // spec: mcp-status: pending before the first turn
    @Test
    fun `mcp-status - pending before the first turn`() {
        assertEquals(
            listOf("echo" to McpStatus.PENDING, "broken" to McpStatus.PENDING, "authy" to McpStatus.NEEDS_AUTH),
            servers("mcp-status", 2).map { it.name to it.status },
        )
    }

    // spec: mcp-status: a toggle success has no payload and a disabled entry has no tools
    @Test
    fun `mcp-status - a toggle success has no payload and a disabled entry has no tools`() {
        val toggle = response("mcp-status", 19)
        assertTrue(toggle.isSuccess && toggle.payload == null)

        val echo = servers("mcp-status", 20).single { it.name == "echo" }
        assertEquals(McpStatus.DISABLED, echo.status)
        assertNull(echo.serverInfo)
        assertNull(echo.tools)
    }

    // spec: mcp-status: reconnect and toggle errors carry the CLI's text
    @Test
    fun `mcp-status - reconnect and toggle errors carry the CLI's text`() {
        val errors = listOf(36, 37, 38).map { response("mcp-status", it) }
        assertTrue(errors.none { it.isSuccess })
        assertEquals(listOf("Connection closed", "Server status: needs-auth", "Server not found: nosuch"), errors.map { it.error })
    }

    // spec: mcp-status: mcp_authenticate answers with an auth url and a callback port
    @Test
    fun `mcp-status - mcp_authenticate answers with an auth url and a callback port`() {
        val start = assertNotNull(ControlPayloads.mcpAuthenticate(response("mcp-status", 40).payload))
        val authUrl = assertNotNull(start.authUrl)
        assertTrue(authUrl.startsWith("http://127.0.0.1:33041/authorize?"), authUrl)
        assertEquals(authUrl, ControlPayloads.browsableUrl(authUrl))
        assertEquals("REDACTED_STATE", start.state)
        assertEquals(true, start.requiresUserAction)
        assertEquals(true, start.callbackExpected)
        assertEquals("localhost", start.redirectScheme)
        assertEquals(53713, start.callbackPort)
    }

    // spec: mcp-status: a sign-in link that is not http is refused
    @Test
    fun `mcp-status - a sign-in link that is not http is refused`() {
        val recorded = response("mcp-status", 40).payload!!
        fun linkOf(authUrl: String): String? =
            ControlPayloads.browsableUrl(ControlPayloads.mcpAuthenticate(JsonObject(recorded + ("authUrl" to JsonPrimitive(authUrl))))?.authUrl)

        val rawLessThan = recorded.getValue("authUrl").jsonPrimitive.content.replace("REDACTED_STATE", "<state>")
        for (refused in listOf("file:///etc/passwd", "vscode://x", "authorize?x=1", "http:///x", rawLessThan)) {
            assertNull(linkOf(refused), refused)
        }
        assertEquals("HTTPS://example.test/a", linkOf("HTTPS://example.test/a"))
        assertNull(ControlPayloads.browsableUrl(null))
    }

    // spec: mcp-status: mcp_set_servers reports added and removed
    @Test
    fun `mcp-status - mcp_set_servers reports added and removed`() {
        val added = assertNotNull(ControlPayloads.mcpSetServers(response("mcp-status", 42).payload))
        assertEquals(listOf("echo2"), added.added)
        assertTrue(added.removed.isEmpty())

        val removed = assertNotNull(ControlPayloads.mcpSetServers(response("mcp-status", 44).payload))
        assertEquals(listOf("echo2"), removed.removed)
        assertTrue(removed.added.isEmpty())
        assertTrue(removed.errors.isEmpty())
    }

    // spec: mcp-status: the redacted initialize answer has no commands
    @Test
    fun `mcp-status - the redacted initialize answer has no commands`() {
        assertTrue(assertNotNull(ControlPayloads.initialize(response("mcp-status", 1).payload)).commands.isEmpty())
    }

    // spec: mcp-slash: the initialize answer lists 73 commands with hints and aliases
    @Test
    fun `mcp-slash - the initialize answer lists 73 commands with hints and aliases`() {
        val commands = assertNotNull(ControlPayloads.initialize(response("mcp-slash", 1).payload)).commands
        assertEquals(73, commands.size)
        assertEquals(55, commands.count { it.builtin })
        val byName = commands.associateBy { it.name }
        assertEquals("[reconnect|enable|disable [<server>|all]]", byName.getValue("mcp").argumentHint)
        assertEquals("<model>", byName.getValue("model").argumentHint)
        assertEquals(listOf("review"), byName.getValue("code-review").aliases)
        val hello = byName.getValue("hello")
        assertTrue(hello.description.endsWith(" (project)"), hello.description)
        assertEquals(false, hello.builtin)
        assertTrue(hello.aliases.isEmpty())
    }

    // spec: mcp-slash: init lists the command names and the terminal ones
    @Test
    fun `mcp-slash - init lists the command names and the terminal ones`() {
        val init = init("mcp-slash", 4)
        val catalogue = assertNotNull(ControlPayloads.initialize(response("mcp-slash", 1).payload)).commands.map { it.name }
        assertEquals(73, init.slashCommands.size)
        assertEquals(catalogue, init.slashCommands)
        assertEquals(listOf("doctor", "color", "focus", "reload-plugins"), init.terminalSlashCommands)
        assertTrue("permissions" !in init.slashCommands && "mode" !in init.slashCommands)
        assertTrue("permissions" !in init.terminalSlashCommands && "mode" !in init.terminalSlashCommands)
    }

    // spec: mcp-slash: a local command is a synthetic assistant line
    @Test
    fun `mcp-slash - a local command is a synthetic assistant line`() {
        val text = "3 MCP server(s): 1 connected, 2 not connected, 0 disabled. Use `/mcp` in the terminal for details."
        val line = assertIs<CliMessage.Assistant>(at("mcp-slash", 5))
        assertEquals("<synthetic>", line.model)
        assertEquals(LocalCommand("mcp", "", null, text), line.localCommand)
        assertEquals(listOf<ContentBlock>(ContentBlock.Text(text)), line.content)
        assertTrue(line.localCommand!!.stdout!!.endsWith(dev.daarkolabs.tandem.chat.MCP_TERMINAL_HINT))

        val result = assertIs<CliMessage.Result>(at("mcp-slash", 6))
        assertEquals("mcp", result.localCommand)
        assertEquals(0, result.numTurns)
        assertEquals(0L, result.durationApiMs)
    }

    // spec: mcp-slash: an unavailable command carries an outcome kind
    @Test
    fun `mcp-slash - an unavailable command carries an outcome kind`() {
        val local = assertNotNull(assertIs<CliMessage.Assistant>(at("mcp-slash", 17)).localCommand)
        assertEquals("unavailable_headless", local.outcomeKind)
        assertNull(local.command)
        assertNull(local.args)
        assertEquals("/help isn't available in this environment.", local.stdout)
        assertNull(assertIs<CliMessage.Result>(at("mcp-slash", 18)).localCommand)
    }

    // spec: mcp-tool-call: the permission request names the server
    @Test
    fun `mcp-tool-call - the permission request names the server`() {
        val ask = request("mcp-tool-call", 35)
        assertEquals(McpServerRef("echo", "dynamic"), ask.mcpServer)
        assertTrue(ask.serverPolicyAskOnly)
        assertEquals("Echo", ask.displayName)
    }

    // spec: mcp-tool-call: the assistant line carries tool_use_meta
    @Test
    fun `mcp-tool-call - the assistant line carries tool_use_meta`() {
        val line = assertIs<CliMessage.Assistant>(at("mcp-tool-call", 34))
        val toolUse = line.content.filterIsInstance<ContentBlock.ToolUse>().single()
        assertEquals(ToolUseMeta(toolUse.id, "Echo", "echo", "echo"), line.toolUseMeta.single())
        assertNull(line.localCommand)
    }

    // spec: mcp-tool-call: the result keeps structuredContent reachable
    @Test
    fun `mcp-tool-call - the result keeps structuredContent reachable`() {
        val result = assertIs<CliMessage.User>(at("mcp-tool-call", 39))
        assertEquals("hello tandem", result.toolUseResult.field("structuredContent").field("result").jsonPrimitive.content)

        val denied = assertIs<CliMessage.User>(at("mcp-tool-call", 62))
        assertTrue(denied.content.filterIsInstance<ContentBlock.ToolResult>().single().isError)
        assertEquals("permission-rule", denied.toolResultMeta[0].nonExecutionKind)
    }

    // spec: bash-mode: the quiet sends end in a zero-turn result naming both uuids
    @Test
    fun `bash-mode - the quiet sends end in a zero-turn result naming both uuids`() {
        val quiet = assertIs<CliMessage.Result>(at("bash-mode", 8))
        assertEquals(0, quiet.numTurns)
        assertEquals(0L, quiet.durationApiMs)
        assertEquals("", quiet.result)
        assertEquals(2, quiet.userMessageUuids.size)
        assertEquals(listOf("<command_uuid-4>", "<command_uuid-6>"), quiet.userMessageUuids)
        val prompt = assertIs<CliMessage.Result>(at("bash-mode", 23))
        assertEquals(1, prompt.numTurns)
        assertEquals(1, prompt.userMessageUuids.size)
    }

    // spec: bash-mode-respond: the output send starts a normal turn
    @Test
    fun `bash-mode-respond - the output send starts a normal turn`() {
        val quiet = assertIs<CliMessage.Result>(at("bash-mode-respond", 6))
        assertEquals(0, quiet.numTurns)
        assertEquals(listOf("<command_uuid-4>"), quiet.userMessageUuids)
        assertEquals("requesting", assertIs<CliMessage.SystemStatus>(at("bash-mode-respond", 11)).status)
        val reply = assertIs<CliMessage.Assistant>(at("bash-mode-respond", 36))
        assertTrue(reply.content.filterIsInstance<ContentBlock.Text>().single().text.contains("merged"))
        val ended = assertIs<CliMessage.Result>(at("bash-mode-respond", 41))
        assertEquals(1, ended.numTurns)
        assertEquals(listOf("<command_uuid-10>"), ended.userMessageUuids)
    }

    @Test
    fun `bash-mode-context - the one context frame ends in a zero-turn result naming its uuid`() {
        val quiet = assertIs<CliMessage.Result>(at("bash-mode-context", 6))
        assertEquals(0, quiet.numTurns)
        assertEquals(0L, quiet.durationApiMs)
        assertEquals("", quiet.result)
        assertEquals(false, quiet.isError)
        assertEquals(listOf("<command_uuid-4>"), quiet.userMessageUuids)
        val prompt = assertIs<CliMessage.Result>(at("bash-mode-context", 20))
        assertEquals(1, prompt.numTurns)
        assertEquals(listOf("<command_uuid-10>"), prompt.userMessageUuids)
        assertEquals("bash-mode-probe", prompt.result)
    }

    private fun contextUsage(line: Int): ContextBreakdown =
        assertNotNull(ControlPayloads.contextUsage(response("parity-context", line).payload))

    private fun usage(line: Int): UsageAnswer = assertNotNull(ControlPayloads.usage(response("parity-usage", line).payload))

    // spec: parity-context: the breakdown after a turn decodes every category
    @Test
    fun `parity-context - the breakdown after a turn decodes every category`() {
        val b17 = contextUsage(17)
        assertEquals(
            listOf(
                "system_prompt", "system_tools", "system_tools_deferred", "memory_files", "skills", "messages", "autocompact_buffer",
                "free_space",
            ),
            b17.categories.map { it.id },
        )
        assertEquals(listOf("used", "used", "deferred", "used", "used", "used", "buffer", "free"), b17.categories.map { it.kind })
        assertEquals(38032L, b17.categories.filter { it.kind == "used" }.sumOf { it.tokens })
        assertEquals(38032L, b17.totalTokens)
        assertEquals(1_000_000L, b17.maxTokens)
        assertEquals(1_000_000L, b17.rawMaxTokens)
        assertEquals(4, b17.percentage)
        assertEquals("claude-sonnet-5-5", b17.model)
        val deferred = b17.categories.single { it.deferred }
        assertEquals("system_tools_deferred" to 22275L, deferred.id to deferred.tokens)
        assertEquals(listOf(MemoryFileUsage("<path>", "Project", 4300)), b17.memoryFiles)
        assertEquals(listOf(McpToolUsage("mcp__echo__echo", "echo", 0, false)), b17.mcpTools)
        assertEquals(36, b17.skills.size)
        assertEquals(3155L, b17.skills.sumOf { it.tokens })
        assertEquals(SkillUsage("probe-kit:greet", "plugin", "probe-kit", 22), b17.skills.single { it.name == "probe-kit:greet" })
        assertTrue(b17.mcpInstructions.isEmpty())
        assertTrue(b17.agents.isEmpty())
    }

    // spec: parity-context: the breakdown before the first turn
    @Test
    fun `parity-context - the breakdown before the first turn`() {
        val b2 = contextUsage(2)
        assertTrue(b2.mcpTools.isEmpty())
        assertEquals(10L, b2.categories.single { it.id == "messages" }.tokens)
        assertEquals(30909L, b2.totalTokens)
        assertEquals(3, b2.percentage)
    }

    // spec: parity-context: summary has no messages row and a deferred MCP row
    @Test
    fun `parity-context - summary has no messages row and a deferred MCP row`() {
        val b18 = contextUsage(18)
        assertTrue(b18.categories.none { it.id == "messages" })
        val mcp = b18.categories.single { it.id == "mcp_tools_deferred" }
        assertTrue(mcp.deferred)
        assertEquals(120L, mcp.tokens)
        assertEquals(42383L, b18.totalTokens)
    }

    // spec: parity-context: an unknown subtype is an error with the CLI's text
    @Test
    fun `parity-context - an unknown subtype is an error with the CLI's text`() {
        val answer = response("parity-context", 19)
        assertEquals(false, answer.isSuccess)
        assertEquals("Unsupported control request subtype: get_context_breakdown", answer.error)
        assertNull(ControlPayloads.contextUsage(answer.payload))
    }

    // spec: parity-usage: get_usage without the scan has the session and no history
    @Test
    fun `parity-usage - get_usage without the scan has the session and no history`() {
        val u16 = usage(16)
        val session = assertNotNull(u16.session)
        assertEquals(0.1523063, session.totalCostUsd)
        assertEquals(4192L, session.apiDurationMs)
        assertEquals(7234L, session.wallDurationMs)
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), session.models.map { it.first })
        assertEquals(ModelTokens(2, 3, 0, 38037, 0.152182, webSearches = 0), session.models[1].second)
        assertEquals(1173L, session.models[0].second.input)
        assertEquals(0.0001243, session.models[0].second.costUsd)
        assertNull(u16.history)
    }

    // spec: parity-usage: get_usage with the scan has both windows
    @Test
    fun `parity-usage - get_usage with the scan has both windows`() {
        val u17 = usage(17)
        val history = assertNotNull(u17.history)
        val day = assertNotNull(history.day)
        assertEquals(23, day.sessionCount)
        assertEquals(8314, day.requestCount)
        assertEquals(listOf("subagent_heavy", "cron", "workflow", "long_context", "large_tool_results"), day.behaviors.map { it.key })
        assertEquals(listOf(100, 100, 80, 65, 11), day.behaviors.map { it.pct })
        assertEquals(UsageTip("subagent_heavy", "Give simple subagents a cheaper model", null), day.tips[0])
        assertEquals("some ran 8 hours or more", day.tips[1].detail)
        assertEquals(listOf(UsageShare("workflow-subagent", 80)), day.agents)
        assertTrue(day.skills.isEmpty())
        assertTrue(day.plugins.isEmpty())
        assertEquals(listOf(UsageShare("server-a", 2), UsageShare("server-b", 2)), day.mcpServers)
        val week = assertNotNull(history.week)
        assertEquals(88, week.sessionCount)
        assertEquals(listOf(UsageShare("plugin-a", 1)), week.plugins)
        assertEquals(listOf(UsageShare("workflow-subagent", 58), UsageShare("general-purpose", 19)), week.agents)
        assertTrue(UsageBehavior("max_effort", 19) in week.behaviors)
        assertEquals(9921L, assertNotNull(u17.session).wallDurationMs)
    }

    // spec: parity-usage: /usage and /cost run the usage command locally
    @Test
    fun `parity-usage - usage and cost run the usage command locally`() {
        for ((line, uuid) in listOf(22 to "<uuid-28>", 28 to "<uuid-36>")) {
            val assistant = assertIs<CliMessage.Assistant>(at("parity-usage", line))
            assertEquals("usage", assertNotNull(assistant.localCommand).command)
            assertNull(assistant.parentToolUseId)
            assertEquals(uuid, assistant.uuid)
        }
        val usage = assertIs<CliMessage.Result>(at("parity-usage", 23))
        assertEquals("usage", usage.localCommand)
        assertEquals(0, usage.numTurns)
        assertEquals("cost", assertIs<CliMessage.Result>(at("parity-usage", 29)).localCommand)
    }

    // spec: parity-add-dir: init lists plugins and additional directories
    @Test
    fun `parity-add-dir - init lists plugins and additional directories`() {
        val first = init("parity-add-dir", 5)
        assertEquals(4, first.plugins.size)
        assertEquals(3, first.plugins.count { it.builtin })
        assertEquals(PluginInfo("probe-kit", "<path>", "probe-kit@inline", "0.1.0"), first.plugins[0])
        assertTrue(first.additionalDirectories.isEmpty())
        assertEquals(listOf("<path>"), init("parity-add-dir", 17).additionalDirectories)
    }

    // spec: parity-add-dir: the catalogue names plugin commands with a prefix
    @Test
    fun `parity-add-dir - the catalogue names plugin commands with a prefix`() {
        val byName = assertNotNull(ControlPayloads.initialize(response("parity-add-dir", 1).payload)).commands.associateBy { it.name }
        assertEquals("(probe-kit) Says hello from a plugin command", byName.getValue("probe-kit:hello").description)
        val greet = byName.getValue("probe-kit:greet")
        assertEquals("(probe-kit) Greets the user by name in one short sentence.", greet.description)
        assertEquals(listOf("greet"), greet.aliases)
    }

    // spec: parity-add-dir: init lists skills apart from commands
    @Test
    fun `parity-add-dir - init lists skills apart from commands`() {
        val skills = init("parity-add-dir", 5).skills
        assertEquals(35, skills.size)
        assertTrue("probe-kit:greet" in skills)
        assertTrue("probe-kit:hello" !in skills)
        assertEquals("actions", skills.first())
    }

    // spec: parity-add-dir-spawn: each init lists the spawn's folders and the resume keeps the id
    @Test
    fun `parity-add-dir-spawn - each init lists the spawn's folders and the resume keeps the id`() {
        val first = init("parity-add-dir-spawn", 4)
        assertEquals(1, first.additionalDirectories.size)
        assertEquals(2, init("parity-add-dir-spawn", 19).additionalDirectories.size)
        assertEquals(first.sessionId, init("parity-add-dir-spawn", 19).sessionId)
        assertEquals(first.sessionId, init("parity-add-dir-spawn", 64).sessionId)
        val messages = Fixtures.decode("parity-add-dir-spawn")
        val results = messages.filterIsInstance<CliMessage.Result>()
        assertEquals(5, results.size)
        // An API error result also has subtype success: a refused turn would pass on the subtype alone.
        assertTrue(results.all { it.subtype == "success" && !it.isError }, results.toString())
        assertTrue(messages.filterIsInstance<CliMessage.Assistant>().all { it.error == null })
    }

    // spec: parity-add-dir-spawn: a fork with --add-dir lists its folder
    @Test
    fun `parity-add-dir-spawn - a fork with --add-dir lists its folder`() {
        val fork = init("parity-add-dir-spawn", 49)
        assertEquals(1, fork.additionalDirectories.size)
        assertNotEquals(init("parity-add-dir-spawn", 4).sessionId, fork.sessionId)
        val ids = Fixtures.transcript("parity-add-dir-spawn").readLines().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject["sessionId"]?.jsonPrimitive?.content }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { it == fork.sessionId }, "the fork's transcript is under the fork's id: $ids")
        assertEquals(false, assertIs<CliMessage.Result>(at("parity-add-dir-spawn", 59)).isError)
    }

    // spec: parity-add-dir-spawn: a resume without a folder leaves it out
    @Test
    fun `parity-add-dir-spawn - a resume without a folder leaves it out`() {
        assertEquals(1, init("parity-add-dir-spawn", 64).additionalDirectories.size)
    }

    // spec: parity-add-dir-spawn: a fork at a reply with --add-dir lists its folder
    @Test
    fun `parity-add-dir-spawn - a fork at a reply with --add-dir lists its folder`() {
        val forkAt = init("parity-add-dir-spawn", 34)
        assertEquals(1, forkAt.additionalDirectories.size)
        assertNotEquals(init("parity-add-dir-spawn", 4).sessionId, forkAt.sessionId)
        assertNotEquals(init("parity-add-dir-spawn", 49).sessionId, forkAt.sessionId)
        assertEquals(false, assertIs<CliMessage.Result>(at("parity-add-dir-spawn", 44)).isError)
    }

    private fun lifecycle(name: String, line: Int): Pair<String, String> =
        assertIs<CliMessage.CommandLifecycle>(at(name, line)).let { it.commandUuid to it.state }

    private fun result(name: String, line: Int): CliMessage.Result = assertIs<CliMessage.Result>(at(name, line))

    private fun JsonObject.rawString(key: String): String? = (this[key] as? JsonPrimitive)?.content

    // spec: queue-fold: lifecycle lines decode with their states
    @Test
    fun `queue-fold - lifecycle lines decode with their states`() {
        val p = "<command_uuid-4>"
        val f = "<command_uuid-25>"
        assertEquals(p to "queued", lifecycle("queue-fold", 3))
        assertEquals(p to "started", lifecycle("queue-fold", 5))
        assertEquals(f to "queued", lifecycle("queue-fold", 19))
        assertEquals(f to "started", lifecycle("queue-fold", 22))
        assertEquals(f to "completed", lifecycle("queue-fold", 52))
        assertEquals(p to "completed", lifecycle("queue-fold", 54))
    }

    // spec: queue-fold: one result names the prompt and the folded message
    @Test
    fun `queue-fold - one result names the prompt and the folded message`() {
        val p = "<command_uuid-4>"
        val f = "<command_uuid-25>"
        val status = assertIs<CliMessage.SystemStatus>(at("queue-fold", 23))
        assertEquals(listOf(p, f), status.raw["user_message_uuids"]!!.jsonArray.map { it.jsonPrimitive.content })
        val result = result("queue-fold", 53)
        assertEquals(3, result.numTurns)
        assertEquals(p, result.raw.rawString("user_message_uuid"))
        assertEquals(listOf(p, f), result.userMessageUuids)
        assertEquals(0, result.raw["queued_turn_count"]!!.jsonPrimitive.int)
        val users = Fixtures.decode("queue-fold").filterIsInstance<CliMessage.User>()
        assertTrue(users.isNotEmpty())
        for (user in users) {
            assertNotEquals(f, user.raw.rawString("uuid"))
            assertTrue(user.content.none { it == ContentBlock.Text("Also say the word KIWI at the end.") })
        }
    }

    // spec: queue-later: the later message runs as its own turn
    @Test
    fun `queue-later - the later message runs as its own turn`() {
        val p = "<command_uuid-4>"
        val f = "<command_uuid-25>"
        assertEquals(f to "queued", lifecycle("queue-later", 19))
        val first = result("queue-later", 51)
        assertEquals(listOf(p), first.userMessageUuids)
        assertEquals(0, first.raw["queued_turn_count"]!!.jsonPrimitive.int)
        assertEquals(f to "started", lifecycle("queue-later", 53))
        assertIs<CliMessage.SystemInit>(at("queue-later", 54))
        val second = result("queue-later", 63)
        assertEquals(f, second.raw.rawString("user_message_uuid"))
        assertEquals(1, second.numTurns)
    }

    // spec: queue-cancel: cancel_async_message answers cancelled true
    @Test
    fun `queue-cancel - cancel_async_message answers cancelled true`() {
        assertEquals("<command_uuid-29>" to "cancelled", lifecycle("queue-cancel", 24))
        assertEquals(true, cancelAsyncAnswer(response("queue-cancel", 25).payload))
        val result = result("queue-cancel", 36)
        assertEquals("success", result.subtype)
        assertEquals(listOf("<command_uuid-4>"), result.userMessageUuids)
    }

    // spec: queue-cancel: interrupt with cancel_queued lists the cancelled message
    @Test
    fun `queue-cancel - interrupt with cancel_queued lists the cancelled message`() {
        val f2 = "<command_uuid-68>"
        assertEquals(f2 to "cancelled", lifecycle("queue-cancel", 55))
        assertEquals(InterruptReceipt(emptyList(), listOf(f2)), interruptReceipt(response("queue-cancel", 56).payload))
        val result = result("queue-cancel", 60)
        assertEquals("error_during_execution", result.subtype)
        assertEquals("aborted_tools", result.terminalReason)
        assertTrue(result.isError)
        assertEquals("<command_uuid-47>" to "cancelled", lifecycle("queue-cancel", 61))
    }

    // spec: the init lists the queue capabilities
    @Test
    fun `the init lists the queue capabilities`() {
        for ((name, line) in listOf("queue-fold" to 6, "queue-cancel" to 5, "plain-reply" to 4)) {
            val capabilities = init(name, line).capabilities
            assertTrue("msg_lifecycle_v1" in capabilities, "$name:$line")
            assertTrue("interrupt_cancel_queued_v1" in capabilities, "$name:$line")
        }
    }

    // spec: fork-at: a fork at the first reply knows only that turn
    @Test
    fun `fork-at - a fork at the first reply knows only that turn`() {
        val a = "<session_id-2>"
        val b = "<session_id-70>"
        assertEquals(listOf(a, a, a, b), listOf(6, 19, 45, 60).map { init("fork-at", it).sessionId })
        assertNotEquals(a, b)
        val cut = assertIs<CliMessage.Assistant>(at("fork-at", 11))
        assertEquals("<uuid-14>", cut.uuid)
        assertNull(cut.parentToolUseId)
        assertEquals(listOf<ContentBlock>(ContentBlock.Text("ALPHA")), assertIs<CliMessage.Assistant>(at("fork-at", 65)).content)
        val result = result("fork-at", 70)
        assertEquals("success", result.subtype)
        assertEquals(1, result.numTurns)
    }

    // spec: fork-at transcripts: the fork copies the chain up to the cut and nothing after
    @Test
    fun `fork-at transcripts - the fork copies the chain up to the cut and nothing after`() {
        fun lines(name: String): List<JsonObject> =
            Fixtures.transcript(name).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
        val fork = lines("fork-at")
        val origin = lines("fork-at-origin")
        assertEquals("assistant", fork[16].rawString("type"))
        assertEquals("<uuid-14>", fork[16].rawString("uuid"))
        assertEquals("user", fork[17].rawString("type"))
        assertEquals("<uuid-14>", fork[17].rawString("parentUuid"))
        val sessionIds = fork.mapNotNull { it.rawString("sessionId") }
        assertEquals(fork.size, sessionIds.size)
        assertTrue(sessionIds.all { it == "<session_id-70>" }, sessionIds.toSet().toString())
        assertEquals("<uuid-14>", origin[19].rawString("uuid"))
        assertEquals(assertIs<CliMessage.Assistant>(at("fork-at", 11)).uuid, origin[19].rawString("uuid"))
        val later = origin.drop(20).filter { it.rawString("type") in setOf("user", "assistant") }.mapNotNull { it.rawString("uuid") }.toSet()
        assertTrue(listOf(27, 35, 37).all { origin[it - 1].rawString("uuid") in later })
        assertTrue(fork.none { it.rawString("uuid") in later })
    }
}
